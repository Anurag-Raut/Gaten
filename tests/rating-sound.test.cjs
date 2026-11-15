/**
 * The Anki rating cues: which sound a rating button plays, read positionally from the deck's own
 * button count. Exercised against the real `rating-sound` module rather than a copy of its rules.
 * That module carries only a type at runtime, so it loads here with no bridge mocks — the one
 * import it names is erased.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');

function loadTypeScript(relativePath) {
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const javascript = ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
  }).outputText;
  const module = {exports: {}};
  const run = new vm.Script(`(function (require, module, exports) { ${javascript}\n})`, {
    filename: relativePath,
  }).runInThisContext();
  run(
    (name) => {
      throw new Error(`Unexpected import: ${name}`);
    },
    module,
    module.exports
  );
  return module.exports;
}

const {ratingSoundFor} = loadTypeScript('src/rn/ui/review/rating-sound.ts');

test('four rating buttons play Again, Hard, Good and Easy in order', () => {
  assert.equal(ratingSoundFor(1, 4), 'rateAgain');
  assert.equal(ratingSoundFor(2, 4), 'rateHard');
  assert.equal(ratingSoundFor(3, 4), 'rateGood');
  assert.equal(ratingSoundFor(4, 4), 'rateEasy');
});

test('three rating buttons play Again, Good and Easy in order', () => {
  assert.equal(ratingSoundFor(1, 3), 'rateAgain');
  assert.equal(ratingSoundFor(2, 3), 'rateGood');
  assert.equal(ratingSoundFor(3, 3), 'rateEasy');
});

test('two rating buttons play Again and Good in order', () => {
  assert.equal(ratingSoundFor(1, 2), 'rateAgain');
  assert.equal(ratingSoundFor(2, 2), 'rateGood');
});

test('a button count outside 2..4 is clamped the way the rating row is', () => {
  assert.equal(ratingSoundFor(4, 5), 'rateEasy');
  assert.equal(ratingSoundFor(1, 1), 'rateAgain');
  assert.equal(ratingSoundFor(2, 1), 'rateGood');
  assert.equal(ratingSoundFor(3, 1), null);
});

test('an ease outside the clamped row, or not an integer, plays nothing', () => {
  assert.equal(ratingSoundFor(0, 4), null);
  assert.equal(ratingSoundFor(5, 4), null);
  assert.equal(ratingSoundFor(2.5, 4), null);
  assert.equal(ratingSoundFor(1, 2.5), 'rateAgain');
});

test('a judged answer plays its pattern and the sound that matches its tone', () => {
  const source = fs.readFileSync(path.join(root, 'src/rn/ui/review/useJudgedAnswer.ts'), 'utf8');
  assert.match(source, /Haptics\.play\(judged\.tone\)\.catch\(\(\) => \{\}\)/);
  assert.match(
    source,
    /Haptics\.play\(judged\.tone === "right" \? "answerRight" : "answerWrong"\)\.catch\(\(\) => \{\}\)/
  );
});
