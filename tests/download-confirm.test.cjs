/**
 * The two places that offer the Enhanced download must describe it the same way, and neither may
 * claim a transfer is running before the reader confirms. These tests pin the shared copy, the
 * dialog's action order, and the state transition in both screens.
 */
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

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

/** The text between one function's opening brace and its matching close brace. */
function functionBody(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `missing ${signature}`);
  const open = source.indexOf('{', start);
  assert.notEqual(open, -1, `missing body for ${signature}`);
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  throw new Error(`unclosed body for ${signature}`);
}

const confirmCopy = loadTypeScript('src/rn/ui/download-confirm.ts', (name) => {
  if (name === './onboarding/onboarding-steps') {
    return loadTypeScript('src/rn/ui/onboarding/onboarding-steps.ts');
  }
  throw new Error(`Unexpected import: ${name}`);
});

const recognitionCopy = loadTypeScript('src/rn/ui/recognition-copy.ts', (name) => {
  if (name === '../native/GatenRecognition') return {};
  throw new Error(`Unexpected import: ${name}`);
});

test('the confirmation names the engine, its size, what it gives and the connection note', () => {
  const copy = confirmCopy.downloadConfirmCopy('Enhanced', 76_600_000);
  assert.deepEqual(copy, {
    title: 'Download Enhanced?',
    size: '76.6 MB',
    reason: 'Reads screen and novel text better, then works offline.',
    note: 'Wi-Fi recommended. Mobile data may incur charges.',
  });
});

test('a size the read did not report is left out rather than invented', () => {
  const copy = confirmCopy.downloadConfirmCopy('Enhanced', 0);
  assert.equal(copy.title, 'Download Enhanced?');
  assert.equal(copy.size, null);
  assert.equal(copy.reason, confirmCopy.DOWNLOAD_CONFIRM_REASON);
  assert.equal(copy.note, confirmCopy.DOWNLOAD_CONFIRM_NOTE);
});

test('both entry points raise the one shared dialog, and neither raises a system alert', () => {
  const onboarding = read('src/rn/ui/onboarding/OnboardingFlow.tsx');
  const settings = read('src/rn/ui/settings/RecognitionSettingsScreen.tsx');
  for (const [name, source] of [
    ['onboarding', onboarding],
    ['settings', settings],
  ]) {
    assert.match(source, /<DownloadConfirmDialog/, `${name} must render the shared dialog`);
    assert.doesNotMatch(source, /Alert\.alert/, `${name} must not raise the stock Alert`);
  }
});

test('the dialog is built on DialogShell, with Cancel before the primary Download', () => {
  const dialog = read('src/rn/ui/DownloadConfirmDialog.tsx');
  assert.match(dialog, /<DialogShell/);
  assert.match(dialog, /onDismiss=\{onCancel\}/);
  const cancel = dialog.indexOf('label="Cancel"');
  const download = dialog.indexOf('label="Download"');
  assert.ok(cancel !== -1 && download !== -1, 'both actions are present');
  assert.ok(cancel < download, 'Cancel comes before Download');
  assert.match(dialog, /variant="text"[\s\S]*label="Cancel"/);
  assert.match(dialog, /<Button[\s\S]*label="Download"/);
});

test('Settings marks a download requested only after the reader confirms', () => {
  const settings = read('src/rn/ui/settings/RecognitionSettingsScreen.tsx');
  const request = functionBody(settings, 'const requestDownload = (engine: EngineStatus) => {');
  assert.match(request, /setConfirming\(engine\)/);
  assert.doesNotMatch(request, /setRequested/, 'opening the dialog must not mark a download running');

  const confirm = functionBody(settings, 'const confirmDownload = () => {');
  assert.match(confirm, /setConfirming\(null\)/);
  assert.match(confirm, /startDownload\(engine\.id\)/);
  const start = functionBody(settings, 'const startDownload = (engine: RecognitionEngine) => {');
  assert.match(start, /setRequested\(engine\)/);
});

test('Onboarding marks a download running only after the reader confirms', () => {
  const onboarding = read('src/rn/ui/onboarding/OnboardingFlow.tsx');
  const request = functionBody(onboarding, 'const requestDownload = useCallback(() => {');
  assert.match(request, /setConfirmingDownload\(true\)/);
  assert.doesNotMatch(request, /setMedium\("downloading"\)/);
  assert.doesNotMatch(request, /setProgress/);

  const confirm = functionBody(onboarding, 'const confirmedDownload = useCallback(() => {');
  assert.match(confirm, /setConfirmingDownload\(false\)/);
  assert.match(confirm, /startDownload\(\)/);
  const start = functionBody(onboarding, 'const startDownload = useCallback(() => {');
  assert.match(start, /setMedium\("downloading"\)/);
});

test('neither screen draws a separate Download button under the Enhanced row', () => {
  const settings = read('src/rn/ui/settings/RecognitionSettingsScreen.tsx');
  const onboarding = read('src/rn/ui/onboarding/OnboardingFlow.tsx');
  assert.doesNotMatch(settings, /OutlinedButton/);
  assert.doesNotMatch(settings, /Download · \$\{/);
  assert.doesNotMatch(onboarding, /Download · \$\{/);
});

test('the uninstalled Settings row opens the confirmation, while an installed row selects', () => {
  const settings = read('src/rn/ui/settings/RecognitionSettingsScreen.tsx');
  assert.match(
    settings,
    /engine\.installed\s*\?\s*selectMode\(engine\.id\)\s*:\s*requestDownload\(engine\)/,
  );
  assert.match(settings, /GatenRecognition\.canCancelDownload\(\)/);
  assert.match(settings, /GatenRecognition\.cancelDownload\(\)/);
});

test('the Onboarding row opens the confirmation, and Done/Skip stay outside the row', () => {
  const onboarding = read('src/rn/ui/onboarding/OnboardingFlow.tsx');
  assert.match(onboarding, /medium === "installed" \? selectMedium : requestDownload/);
  // The page actions are the shell's own Done/Skip, not a control inside the Enhanced row.
  assert.match(onboarding, /label: "Done"/);
  assert.match(onboarding, /skipLabel: "Skip"/);
});

test('both screens describe the engines from the same approved, non-technical strings', () => {
  const onboarding = read('src/rn/ui/onboarding/OnboardingFlow.tsx');
  const settings = read('src/rn/ui/settings/RecognitionSettingsScreen.tsx');
  assert.match(onboarding, /STANDARD_ENGINE_SUMMARY/);
  assert.match(onboarding, /ENHANCED_ENGINE_SUMMARY/);
  assert.match(settings, /engineSummary\(engine\.id\)/);

  const standard = recognitionCopy.STANDARD_ENGINE_SUMMARY;
  const enhanced = recognitionCopy.ENHANCED_ENGINE_SUMMARY;
  assert.equal(standard, 'Clear, regular text in standard fonts');
  assert.equal(enhanced, 'Better for stylised fonts, novels and harder-to-read text');
  assert.equal(recognitionCopy.engineSummary('ml_kit'), standard);
  assert.equal(recognitionCopy.engineSummary('medium'), enhanced);
  for (const line of [standard, enhanced]) {
    assert.doesNotMatch(
      line,
      /offline|download|works|bundled|ML Kit|PP-OCR|MB|\u00b7/i,
      `a download, size, state or jargon line in: ${line}`,
    );
  }
});

test('the installed Enhanced row says it is ready, and only when it is', () => {
  assert.equal(
    recognitionCopy.engineReadyLine('medium', true, false),
    'Ready to use.',
  );
  assert.equal(recognitionCopy.engineReadyLine('medium', false, false), null);
  assert.equal(recognitionCopy.engineReadyLine('medium', true, true), null);
  assert.equal(recognitionCopy.engineReadyLine('ml_kit', true, false), null);
  assert.equal(recognitionCopy.engineSummary('ml_kit'), recognitionCopy.STANDARD_ENGINE_SUMMARY);
  assert.equal(recognitionCopy.engineSummary('medium'), recognitionCopy.ENHANCED_ENGINE_SUMMARY);
});
