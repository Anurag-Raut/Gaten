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

const motion = loadTypeScript('src/rn/ui/onboarding/onboarding-motion.ts');

test('the page scene keeps its 340 ms and the emphasized curve', () => {
  assert.equal(motion.STEP_SCENE_MS, 340);
  assert.deepEqual(motion.PAGE_EASING_BEZIER, [0.2, 0, 0, 1]);
});

test('a step row opens in place, shorter than a page change', () => {
  assert.ok(motion.STEP_ROW_MS > 0);
  assert.ok(motion.STEP_ROW_MS < motion.STEP_SCENE_MS);
  assert.equal(motion.stepRowDuration(false), motion.STEP_ROW_MS);
});

test('reduced motion opens a step row at once, at the same end state', () => {
  assert.equal(motion.stepRowDuration(true), 0);
});

test('forward travels one way and back reverses it', () => {
  assert.equal(motion.travelDirection(0, 1), 1);
  assert.equal(motion.travelDirection(1, 2), 1);
  assert.equal(motion.travelDirection(2, 1), -1);
  assert.equal(motion.travelDirection(1, 0), -1);
});
