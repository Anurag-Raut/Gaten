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

test('the bundled Standard default alone never auto-advances the lookup page', () => {
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

test('the WaniKani page opens on the token step, with token creation an available row', () => {
  // The reader pastes first: the token field is the current step, and "Create a token" is offered
  // as an available (openable) row rather than being the step that holds the page.
  for (const connected of [null, false]) {
    const derived = steps.wanikaniSteps({connected});
    assert.equal(derived.step1, 'available');
    assert.equal(derived.step2, 'current');
    assert.equal(derived.verified, false);
  }
  const connected = steps.wanikaniSteps({connected: true});
  assert.deepEqual(pick(connected), {step1: 'done', step2: 'done', verified: true});
});

test('the WaniKani primary is never a dead tap: an empty token still crosses', () => {
  // A stored token being read, and a check in flight, are the only disabled states.
  assert.deepEqual(
    steps.wanikaniPrimary({connected: null, connecting: false, retry: false, token: ''}),
    {label: 'Checking\u2026', disabled: true, intent: 'none'},
  );
  const checkingToken = steps.wanikaniPrimary({
    connected: false,
    connecting: true,
    retry: false,
    token: 'abc',
  });
  assert.deepEqual(checkingToken, {label: 'Checking token\u2026', disabled: true, intent: 'none'});

  // Empty field, not connected: enabled, and the press asks the reader for a token.
  for (const token of ['', '   ']) {
    assert.deepEqual(
      steps.wanikaniPrimary({connected: false, connecting: false, retry: false, token}),
      {label: 'Connect WaniKani', disabled: false, intent: 'prompt'},
    );
  }

  // A token typed: the same button checks it, and a failed attempt keeps its own label.
  assert.deepEqual(
    steps.wanikaniPrimary({connected: false, connecting: false, retry: false, token: 'abc'}),
    {label: 'Connect WaniKani', disabled: false, intent: 'connect'},
  );
  assert.deepEqual(
    steps.wanikaniPrimary({connected: false, connecting: false, retry: true, token: 'abc'}),
    {label: 'Try again', disabled: false, intent: 'connect'},
  );

  // Connected: the button finishes the page.
  assert.deepEqual(
    steps.wanikaniPrimary({connected: true, connecting: false, retry: false, token: ''}),
    {label: 'Done', disabled: false, intent: 'advance'},
  );
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

test('a download line reads the brief: phase, percent, the figure done and the total', () => {
  const downloading = {engine: 'medium', phase: 'downloading', bytesDone: 32_200_000, bytesTotal: 76_600_000};
  assert.equal(steps.downloadPercent(downloading), 42);
  assert.equal(steps.downloadProgressText(downloading), 'Downloading\u2026 42% \u00b7 32.2 of 76.6 MB');
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
  assert.equal(steps.stepStatusWord('available'), 'available');
  assert.equal(steps.stepStatusWord('todo'), 'not started');
});

test('the rail is only solid under a step that is really done', () => {
  assert.equal(steps.connectorStateAfter('done'), 'filled');
  for (const status of ['current', 'available', 'todo']) {
    assert.equal(steps.connectorStateAfter(status), 'pending', `${status} must leave a pale rail`);
  }
});

test('a step open before it was ever measured lays itself out at its natural height', () => {
  // The device failure: an open step measured zero inside its own zero-height clip and stayed shut.
  // It is laid out naturally until a real height exists, so the clip is never consulted at zero.
  assert.equal(steps.shouldMeasureStepContent(true, null), true);
  assert.equal(steps.shouldMeasureStepContent(true, 120), false);
  assert.equal(steps.shouldMeasureStepContent(false, null), false);
  assert.equal(steps.shouldMeasureStepContent(false, 120), false);
});

test('a clipped or missing measurement is never taken as the row height', () => {
  for (const bad of [0, -1, Number.NaN, Infinity]) {
    assert.equal(steps.acceptStepContentHeight(bad), null, `${bad} must not become a height`);
  }
  assert.equal(steps.acceptStepContentHeight(49), 49);
});

test('reduced motion lands a stepper on its end state at once, never on a zero-length clock', () => {
  // The device case: a step read as expanded — chevron up, container sized — while a zero-duration
  // withTiming never landed, so its opacity and height stayed at zero and the how-to stayed invisible.
  for (const reduced of [true, false]) {
    assert.deepEqual(steps.stepTarget(true, reduced), {value: 1, instant: reduced});
    assert.deepEqual(steps.stepTarget(false, reduced), {value: 0, instant: reduced});
  }
  // The rule the stepper relies on: an instant target is written straight in, so it always lands.
  assert.deepEqual(steps.stepTarget(true, true), {value: 1, instant: true});
});

test('a step opens, closes and opens again, however often the reader taps', () => {
  // The device case: open worked, close worked, and the second open never came. The shrink of the
  // close fed its own frames back in as measurements, overwriting the content's height with a sliver.
  const read = (state, height, open) => steps.stepContentReducer(state, {height, open});

  // Mounted shut, the content still lays itself out and is measured at its own height.
  let state = read({measured: null}, 196, false);
  assert.equal(state.measured, 196);

  for (let cycle = 0; cycle < 3; cycle += 1) {
    // Open: the readings are the content's own, and they cannot take the height away.
    state = read(state, 196, true);
    // Closing: the clip shrinks through a sliver to nothing; none of it is the content's height.
    for (const clipFrame of [120, 40, 0]) {
      state = read(state, clipFrame, false);
    }
    assert.equal(state.measured, 196, `cycle ${cycle + 1} must keep the content's height`);
  }

  // A change inside the open row (the hint line appearing) still raises the height.
  assert.equal(read(state, 240, true).measured, 240);
  // And a clipped zero while shut never takes it away again.
  assert.equal(read(read(state, 240, true), 0, false).measured, 240);
  // A reading that says nothing new is the same state object, so a layout event never loops.
  const settled = {measured: 196};
  assert.equal(read(settled, 196, true), settled);
  assert.equal(read(settled, 196, false), settled);
  assert.equal(read(settled, 0, true), settled);
});

test('the page moves up by exactly the field-to-keyboard overlap, and never the other way', () => {
  // Field bottom at 800 with the keyboard's top at 700 and 12 dp of room → 112 up.
  assert.equal(steps.keyboardShift(800, 700, 12), 112);
  // Already clear of the keyboard: no shift, and never a negative one.
  assert.equal(steps.keyboardShift(600, 700, 12), 0);
  assert.equal(steps.keyboardShift(688, 700, 12), 0);
  assert.equal(steps.keyboardShift(689, 700, 12), 1);
});

test('the action-area key carries the page\'s verified flag, so a done flip is never dropped', () => {
  // The reader's own case: lookup already on, Enhanced absent, primary "Done" enabled. The
  // install lands and selects itself — the label, the disabled state and the skip labels are all
  // identical, so only [done] can tell the shell its gate has opened.
  const facts = {
    step: 0,
    label: 'Done',
    disabled: false,
    done: false,
    saveError: false,
    skipLabel: 'Skip',
    skipAccessibilityLabel: 'Skip lookup mode setup',
  };
  const beforeInstall = steps.lookupSteps({lookup: 'on', medium: 'absent', mediumSelected: false});
  const afterInstall = steps.lookupSteps({lookup: 'on', medium: 'installed', mediumSelected: true});
  assert.equal(beforeInstall.verified, false);
  assert.equal(afterInstall.verified, true);
  assert.notEqual(
    steps.actionAreaKey({...facts, done: beforeInstall.verified}),
    steps.actionAreaKey({...facts, done: afterInstall.verified}),
  );

  // Every other thing the shell reads is in the key too, and identical facts are one key.
  const key = steps.actionAreaKey(facts);
  assert.equal(steps.actionAreaKey({...facts}), key);
  assert.notEqual(key, steps.actionAreaKey({...facts, disabled: true}));
  assert.notEqual(key, steps.actionAreaKey({...facts, saveError: true}));
  assert.notEqual(key, steps.actionAreaKey({...facts, step: 1}));
  assert.notEqual(key, steps.actionAreaKey({...facts, label: 'Continue'}));
});

test('a connect handler the shell keeps reads the token at press time, not at capture time', () => {
  let token = '';
  let busy = false;
  // Made once — the shell stores the page's primary and keeps it — and every press must see what the
  // reader has typed since. A handler that closed over its first snapshot would return "a" here.
  const storedHandler = () => steps.tokenToCheck(() => ({token, busy}));

  assert.equal(storedHandler(), null);
  token = 'a';
  assert.equal(storedHandler(), 'a');
  token = 'a-token-typed-in-full';
  assert.equal(storedHandler(), 'a-token-typed-in-full');
  token = '  spaced  ';
  assert.equal(storedHandler(), 'spaced');
  busy = true;
  assert.equal(storedHandler(), null);
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

test('a download that reports no bytes reads as working, not as a false 0%', () => {
  const quiet = {engine: 'medium', phase: 'downloading', bytesDone: 0, bytesTotal: 76_600_000};
  assert.equal(steps.downloadProgressText(quiet), 'Downloading…');
});
