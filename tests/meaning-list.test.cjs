/**
 * The meaning-list rules, exercised against the real `meaningList` module: how one dictionary sense
 * becomes the lines a reader counts, and how each line is cut into text and parenthetical notes. The
 * bridge joins a sense's synonyms with ", " and uses commas inside brackets too, so the split is
 * depth-aware. The module imports nothing, so it loads here without the store's bridges.
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

const {
  splitSense,
  splitParentheticals,
  MEANING_PREVIEW_SENSES,
  MEANING_COLLAPSE_ABOVE,
} = loadTypeScript('src/rn/ui/search/meaningList.ts');

/** A run as the tests state it: the text, and whether it is a note. */
const plain = (text) => ({text, note: false});
const note = (text) => ({text, note: true});

/** The runs' text, back in one string — a split must never lose or reorder a character. */
const rejoined = (runs) => runs.map((run) => run.text).join('');

test('the 通う sense becomes four lines, and the parenthetical keeps its commas', () => {
  assert.deepEqual(
    splitSense(
      'to go to and from (a place), to go back and forth between, to run between (e.g. bus, train, etc.), to ply between',
    ),
    [
      'to go to and from (a place)',
      'to go back and forth between',
      'to run between (e.g. bus, train, etc.)',
      'to ply between',
    ],
  );
});

test('a single gloss is one line, unchanged apart from padding', () => {
  assert.deepEqual(splitSense('to cool'), ['to cool']);
  assert.deepEqual(splitSense('  to cool  '), ['to cool']);
  assert.deepEqual(splitSense('to go, to come'), ['to go', 'to come']);
});

test('an empty or whitespace-only sense has no lines', () => {
  assert.deepEqual(splitSense(''), []);
  assert.deepEqual(splitSense('   '), []);
});

test('nested or unbalanced brackets never throw or swallow the sense', () => {
  // A comma inside nested brackets stays in its line.
  assert.deepEqual(splitSense('to be (as in [a, b]), to exist'), [
    'to be (as in [a, b])',
    'to exist',
  ]);
  // A comma inside brackets the sense never closes stays in the line too.
  assert.deepEqual(splitSense('to be (as in a, b'), ['to be (as in a, b']);
  // An unbalanced closer does not make the depth negative and eat the next comma.
  assert.deepEqual(splitSense('to be), to exist'), ['to be)', 'to exist']);
});

test('a comma without a following space is not a separator', () => {
  assert.deepEqual(splitSense('a,b'), ['a,b']);
  assert.deepEqual(splitSense('a, b,c, d'), ['a', 'b,c', 'd']);
});

test('the preview threshold is the three senses the section shows', () => {
  assert.equal(MEANING_PREVIEW_SENSES, 3);
});

test('a list collapses only above four senses, so four show in full', () => {
  assert.equal(MEANING_COLLAPSE_ABOVE, 4);
  assert.ok(MEANING_COLLAPSE_ABOVE > MEANING_PREVIEW_SENSES);
});

test('a trailing parenthetical is a note, and the text before it is plain', () => {
  assert.deepEqual(splitParentheticals('to go to and from (a place)'), [
    plain('to go to and from '),
    note('(a place)'),
  ]);
});

test('a note keeps its own commas and brackets', () => {
  assert.deepEqual(splitParentheticals('to run between (e.g. bus, train, etc.)'), [
    plain('to run between '),
    note('(e.g. bus, train, etc.)'),
  ]);
});

test('nested parentheses are one note, not two', () => {
  assert.deepEqual(splitParentheticals('(a (b) c)'), [note('(a (b) c)')]);
  assert.deepEqual(splitParentheticals('to be (as in [a, (b)]) now'), [
    plain('to be '),
    note('(as in [a, (b)])'),
    plain(' now'),
  ]);
});

test('a note in the middle leaves plain text on both sides', () => {
  assert.deepEqual(splitParentheticals('to go (a place) and back'), [
    plain('to go '),
    note('(a place)'),
    plain(' and back'),
  ]);
});

test('an unclosed "(" dims the rest of the line instead of throwing', () => {
  assert.deepEqual(splitParentheticals('to be (as in'), [plain('to be '), note('(as in')]);
  assert.deepEqual(splitParentheticals('to be ('), [plain('to be '), note('(')]);
  assert.deepEqual(splitParentheticals('('), [note('(')]);
});

test('a stray ")" is plain text, and a line with no parentheses is one plain run', () => {
  assert.deepEqual(splitParentheticals('to be), to exist'), [plain('to be), to exist')]);
  assert.deepEqual(splitParentheticals('to cool'), [plain('to cool')]);
  assert.deepEqual(splitParentheticals(''), []);
});

test('the runs always reproduce the line exactly', () => {
  const lines = [
    'to go to and from (a place)',
    'to run between (e.g. bus, train, etc.)',
    'to live on (e.g. a salary), to live off, to subsist on',
    '(a (b) c)',
    'to be (as in',
    'to be), to exist',
    'to cool',
    '',
  ];
  for (const line of lines) {
    assert.equal(rejoined(splitParentheticals(line)), line);
  }
  // And the split is contiguous: no run is empty, so nothing is drawn twice or as a blank span.
  for (const line of lines) {
    for (const run of splitParentheticals(line)) {
      assert.ok(run.text.length > 0, `an empty run in ${JSON.stringify(line)}`);
    }
  }
});

test('a whole sense splits into lines that each split into runs', () => {
  const [first] = splitSense(
    'to go to and from (a place), to go back and forth between, to run between (e.g. bus, train, etc.), to ply between',
  );
  assert.deepEqual(splitParentheticals(first), [plain('to go to and from '), note('(a place)')]);
});
