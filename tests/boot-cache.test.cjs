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

/**
 * A fresh copy of boot.ts, with its own module-level prime slot, over a bridge read the case drives.
 * Each load is a new module, so the single-flight slot never leaks between cases.
 */
function loadBoot(getOnboardingStep) {
  const calls = [];
  const boot = loadTypeScript('src/rn/ui/boot.ts', (name) => {
    if (name === '../native/GatenAppInfo') {
      return {
        getOnboardingStep: () => {
          calls.push(calls.length + 1);
          return getOnboardingStep(calls.length);
        },
      };
    }
    return {};
  });
  return {boot, calls};
}

test('the first-run read is started once and shared by every caller', async () => {
  const {boot, calls} = loadBoot(async () => 1);
  const first = boot.primeOnboardingStep();
  const second = boot.primeOnboardingStep();
  assert.equal(first, second, 'the same in-flight read is handed to both callers');
  assert.equal(await first, 1);
  await Promise.resolve();
  assert.equal(calls.length, 1, 'the bridge is asked once');
});

test('a rejected read surfaces to the caller and is cleared for a fresh retry', async () => {
  const {boot, calls} = loadBoot(async (n) => {
    if (n === 1) throw new Error('bridge unavailable');
    return 3;
  });
  const first = boot.primeOnboardingStep();
  await assert.rejects(first, /bridge unavailable/);
  const second = boot.primeOnboardingStep();
  assert.notEqual(first, second, 'the cleared slot starts a new read rather than replaying the failure');
  assert.equal(await second, 3);
  assert.equal(calls.length, 2, 'the retry asks the bridge again');
});

test('resetting after a stored step drops the cached read', async () => {
  let stored = 0;
  const {boot, calls} = loadBoot(async () => stored);
  assert.equal(await boot.primeOnboardingStep(), 0);
  // The shell persisted step 3: the cached 0 must not answer a later remount under this runtime.
  stored = 3;
  boot.resetOnboardingStepPrime();
  assert.equal(await boot.primeOnboardingStep(), 3);
  assert.equal(calls.length, 2, 'the reset forces a fresh read');
});
