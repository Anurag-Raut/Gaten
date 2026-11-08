const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');

function loadTypeScript(relativePath, mockedRequire = () => ({})) {
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const javascript = ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
  }).outputText;
  const module = {exports: {}};
  const run = new vm.Script(`(function (require, module, exports) { ${javascript}\n})`, {
    filename: relativePath,
  }).runInThisContext();
  run(mockedRequire, module, module.exports);
  return module.exports;
}

const steps = loadTypeScript('src/rn/ui/onboarding/onboarding-steps.ts');

const LOOKUP_FACTS = {
  checking: {lookup: 'checking', medium: 'checking', mediumSelected: false},
  onWithMedium: {lookup: 'on', medium: 'installed', mediumSelected: true},
  onWithMlKit: {lookup: 'on', medium: 'absent', mediumSelected: false},
  onWithMediumInstalledNotSelected: {lookup: 'on', medium: 'installed', mediumSelected: false},
  off: {lookup: 'off', medium: 'absent', mediumSelected: false},
  failed: {lookup: 'failed', medium: 'absent', mediumSelected: false},
};

test('resuming with lookup already on verifies only when the better reader is installed and selected', () => {
  const resumed = steps.lookupSteps(LOOKUP_FACTS.onWithMedium);
  assert.equal(resumed.step1, 'done');
  assert.equal(resumed.step2, 'done');
  assert.equal(resumed.verified, true);
});

test('the bundled ML Kit default alone never auto-advances the lookup page', () => {
  const onMlKit = steps.lookupSteps(LOOKUP_FACTS.onWithMlKit);
  assert.equal(onMlKit.step1, 'done');
  assert.equal(onMlKit.step2, 'current');
  assert.equal(onMlKit.verified, false);

  // An install that is not the selected mode is not the step either: the reader must choose it.
  const installedNotSelected = steps.lookupSteps(LOOKUP_FACTS.onWithMediumInstalledNotSelected);
  assert.equal(installedNotSelected.verified, false);
});

test('an off or failed lookup read leaves the first step current and the page unverified', () => {
  for (const fact of [LOOKUP_FACTS.off, LOOKUP_FACTS.failed, LOOKUP_FACTS.checking]) {
    const derived = steps.lookupSteps(fact);
    assert.equal(derived.step1, 'current');
    assert.equal(derived.step2, 'todo');
    assert.equal(derived.verified, false);
  }
});

test('the Anki page verifies only when the collection is really read', () => {
  assert.deepEqual(
    pick(steps.ankiSteps({status: 'connected', collectionEmpty: false})),
    {step1: 'done', step2: 'done', verified: true},
  );
  assert.deepEqual(
    pick(steps.ankiSteps({status: 'connected', collectionEmpty: true})),
    {step1: 'done', step2: 'done', verified: true},
  );

  // Installed but not read: allowed-but-not-set-up, a denial, a permission prompt and a failed read
  // all leave the second step current and the page unverified.
  for (const status of ['needsPermission', 'notSetUp', 'denied', 'failure']) {
    assert.deepEqual(
      pick(steps.ankiSteps({status, collectionEmpty: false})),
      {step1: 'done', step2: 'current', verified: false},
      `${status} must not verify the page`,
    );
  }
});

test('a missing AnkiDroid leaves both Anki rows unverified', () => {
  assert.deepEqual(
    pick(steps.ankiSteps({status: 'notInstalled', collectionEmpty: false})),
    {step1: 'current', step2: 'todo', verified: false},
  );
  assert.equal(steps.ankiInstalled('notInstalled'), false);
  assert.equal(steps.ankiInstalled('notSetUp'), true);
  assert.equal(steps.ankiInstalled('connected'), true);
});

test('a rejected token and a network failure never verify the WaniKani page', () => {
  for (const connected of [null, false]) {
    const derived = steps.wanikaniSteps({connected});
    assert.equal(derived.verified, false);
    assert.notEqual(derived.step2, 'done');
  }
  const done = steps.wanikaniSteps({connected: true});
  assert.deepEqual(pick(done), {step1: 'done', step2: 'done', verified: true});
});

test('the connect readout keeps the exact copy and only offers a retry where it helps', () => {
  const labels = {OFFLINE: 'no connection', SERVER: 'WaniKani reported a server error'};

  assert.deepEqual(steps.connectReadout({status: 'connected'}, labels), {
    message: null,
    retry: false,
  });
  assert.deepEqual(steps.connectReadout({status: 'invalidToken'}, labels), {
    message: 'WaniKani rejected that token. Check it was copied in full.',
    retry: false,
  });
  assert.deepEqual(steps.connectReadout({status: 'failed', failure: 'OFFLINE'}, labels), {
    message: 'Could not connect to WaniKani: no connection',
    retry: true,
  });
  assert.deepEqual(steps.connectReadout({status: 'bridge', message: 'bridge broke'}, labels), {
    message: 'Could not connect to WaniKani: bridge broke',
    retry: true,
  });
});

test('the flow never advances a page whose steps are not all verified', () => {
  const pages = [
    steps.lookupSteps(LOOKUP_FACTS.onWithMlKit),
    steps.lookupSteps(LOOKUP_FACTS.off),
    steps.ankiSteps({status: 'notInstalled', collectionEmpty: false}),
    steps.ankiSteps({status: 'denied', collectionEmpty: false}),
    steps.wanikaniSteps({connected: false}),
    steps.wanikaniSteps({connected: null}),
  ];
  for (const derived of pages) {
    assert.equal(derived.verified, false);
    assert.equal(steps.shouldAutoAdvance(gate({verified: derived.verified})), false);
  }

  // Everything else favourable is still not enough without the page's own verification.
  assert.equal(steps.shouldAutoAdvance(gate({verified: false})), false);
  assert.equal(steps.shouldAutoAdvance(gate({verified: true})), true);
});

test('a Back visit never auto-forwards, however done the page already reads', () => {
  assert.equal(steps.shouldAutoAdvance(gate({verified: true, arrivedBackwards: true})), false);
});

test('a screen reader, a pending write, a failed save or a busy scene all hold the beat', () => {
  for (const blocked of [
    {screenReader: true},
    {writePending: true},
    {saveError: true},
    {suspended: true},
    {sceneIdle: false},
    {contentSettled: false},
  ]) {
    assert.equal(steps.shouldAutoAdvance(gate({verified: true, ...blocked})), false);
  }
});

test('the last page finishes onboarding and other pages step forward', () => {
  assert.equal(steps.nextStep(0), 1);
  assert.equal(steps.nextStep(1), 2);
  assert.equal(steps.nextStep(2), 3);
  assert.equal(steps.nextStep(3), 3);
});

test('a download line names its phase, its percent and its megabytes', () => {
  const downloading = {engine: 'medium', phase: 'downloading', bytesDone: 32_200_000, bytesTotal: 76_600_000};
  assert.equal(steps.downloadPercent(downloading), 42);
  assert.equal(steps.downloadProgressText(downloading), 'Downloading\u2026 \u00b7 42% \u00b7 32.2 MB of 76.6 MB');
  assert.equal(
    steps.downloadProgressText({...downloading, phase: 'verifying'}),
    'Verifying\u2026',
  );
  assert.equal(steps.downloadPercent({...downloading, bytesTotal: 0}), 0);
  assert.equal(steps.formatMegabytes(76_600_000), '76.6 MB');
});

test('a row reads its own status word for its one grouped label', () => {
  assert.equal(steps.stepStatusWord('done'), 'done');
  assert.equal(steps.stepStatusWord('current'), 'current');
  assert.equal(steps.stepStatusWord('todo'), 'not started');
});

function pick(derived) {
  return {step1: derived.step1, step2: derived.step2, verified: derived.verified};
}

function gate(overrides) {
  return {
    verified: false,
    arrivedBackwards: false,
    screenReader: false,
    suspended: false,
    writePending: false,
    saveError: false,
    sceneIdle: true,
    contentSettled: true,
    ...overrides,
  };
}
