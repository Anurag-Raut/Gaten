/**
 * The meaning-answer rule, exercised against the real `meaning-match` module: which typed answers are
 * accepted for a subject's meanings, and — the half that matters — which are refused. The module
 * imports nothing, so it loads here without the store's bridges.
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
  run(() => {
    throw new Error(`Unexpected import: ${relativePath}`);
  }, module, module.exports);
  return module.exports;
}

const {normalizeMeaning, meaningMatches} = loadTypeScript('src/rn/ui/review/meaning-match.ts');

test('the normaliser drops punctuation, padding and case, and collapses whitespace', () => {
  assert.equal(normalizeMeaning('Cool'), 'cool');
  assert.equal(normalizeMeaning('  cool  '), 'cool');
  assert.equal(normalizeMeaning('late  at   night'), 'late at night');
  assert.equal(normalizeMeaning('Late at night.'), 'late at night');
  assert.equal(normalizeMeaning('(to) be, cool!'), 'to be cool');
  assert.equal(normalizeMeaning("don't"), 'dont');
  // Punctuation is dropped, never turned into a space, so a hyphen does not join two words.
  assert.equal(normalizeMeaning('cool-cold'), 'coolcold');
  assert.equal(normalizeMeaning(''), '');
});

test('an exact meaning is accepted however it is typed', () => {
  assert.equal(meaningMatches('cool', ['Cool']), true);
  assert.equal(meaningMatches('Cool', ['Cool']), true);
  assert.equal(meaningMatches('  cool  ', ['Cool']), true);
  assert.equal(meaningMatches('late  at night', ['late at night']), true);
  assert.equal(meaningMatches('late at night.', ['late at night']), true);
  assert.equal(meaningMatches('Late at night!', ['late at night']), true);
});

test('the accepted meanings read as one joined answer are accepted', () => {
  assert.equal(meaningMatches('cool cold', ['Cool', 'Cold']), true);
  assert.equal(meaningMatches('Cool, cold', ['Cool', 'Cold']), true);
  // The join is in the accepted meanings' own order, which is how WaniKani asks a pair.
  assert.equal(meaningMatches('cold cool', ['Cool', 'Cold']), false);
});

test('a near miss is accepted only when both sides are long enough and open alike', () => {
  // One edit, eight characters: the separator gap a reader can drop.
  assert.equal(meaningMatches('mountan', ['mountain']), true);
  // Two edits, twelve characters: the longer side is at the two-edit budget.
  assert.equal(meaningMatches('conversatoin', ['conversation']), true);
  // Two edits, thirteen characters: over the twelve-character line, so still two.
  assert.equal(meaningMatches('aproximatly', ['approximately']), true);
  // The miss is judged against every accepted meaning, not only the first.
  assert.equal(meaningMatches('mountan', ['peak', 'mountain']), true);
});

test('a near miss that changes the opening character is refused', () => {
  assert.equal(meaningMatches('light', ['night']), false);
  assert.equal(meaningMatches('fountain', ['mountain']), false);
});

test('a near miss too far for its length is refused', () => {
  // Four characters: one edit is all the budget there is, so a two-edit miss is out.
  assert.equal(meaningMatches('cold', ['cool']), false);
  // Eleven characters is still under the twelve-character line, so the budget is still one.
  assert.equal(meaningMatches('enviornment', ['environment']), false);
});

test('a word too short to fuzz is refused even one edit away', () => {
  assert.equal(meaningMatches('cat', ['cats']), false);
  assert.equal(meaningMatches('cats', ['cat']), false);
  assert.equal(meaningMatches('cot', ['cat']), false);
  // Three characters against four is the same shape as `cat`/`cats`, so it is refused too. (The
  // brief that asked for this rule listed `col` for `cool` as an accept, but that cannot hold
  // together with refusing `cat` for `cats`: same lengths, same distance, same opening character.
  // The rule's four-character floor is what the module implements, and this pins it.)
  assert.equal(meaningMatches('col', ['cool']), false);
});

test('an unrelated, empty or absent answer is refused', () => {
  assert.equal(meaningMatches('night', ['late at night']), false);
  assert.equal(meaningMatches('banana', ['cool']), false);
  assert.equal(meaningMatches('', ['cool']), false);
  assert.equal(meaningMatches('  ', ['cool']), false);
  assert.equal(meaningMatches('cool', []), false);
});
