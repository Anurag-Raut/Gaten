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

const {parseEmphasis} = loadTypeScript('src/rn/ui/rtk/emphasis.ts');

// --- Story emphasis -------------------------------------------------------

test('bold and italic markers become styled runs', () => {
  assert.deepEqual(parseEmphasis('**one**'), [{text: 'one', bold: true}]);
  assert.deepEqual(parseEmphasis('__one__'), [{text: 'one', bold: true}]);
  assert.deepEqual(parseEmphasis('_one_'), [{text: 'one', italic: true}]);
  assert.deepEqual(parseEmphasis('plain **bold** tail'), [
    {text: 'plain '},
    {text: 'bold', bold: true},
    {text: ' tail'},
  ]);
});

test('emphasis nests the way the dataset writes it', () => {
  assert.deepEqual(parseEmphasis('**bold _and italic_**'), [
    {text: 'bold ', bold: true},
    {text: 'and italic', bold: true, italic: true},
  ]);
  assert.deepEqual(parseEmphasis('_italic **and bold**_'), [
    {text: 'italic ', italic: true},
    {text: 'and bold', bold: true, italic: true},
  ]);
});

test('a markdown link keeps its label and drops its target', () => {
  const spans = parseEmphasis('see [this here](../v4/2043.html) now');
  assert.equal(spans.map((span) => span.text).join(''), 'see this here now');
  assert.equal(spans.some((span) => span.url != null), false);
});

test('unpaired markers are text, not lost prose', () => {
  assert.deepEqual(parseEmphasis('**unclosed'), [{text: '**unclosed'}]);
  assert.deepEqual(parseEmphasis('a _ b'), [{text: 'a _ b'}]);
});

test('an underscore inside a word does not open italic', () => {
  assert.deepEqual(parseEmphasis('snake_case handle'), [{text: 'snake_case handle'}]);
});

test('unicode and Japanese survive the parse', () => {
  assert.deepEqual(parseEmphasis('日 **_本_** 語'), [
    {text: '日 '},
    {text: '本', bold: true, italic: true},
    {text: ' 語'},
  ]);
  assert.deepEqual(parseEmphasis('「_**鬱**_」'), [
    {text: '「'},
    {text: '鬱', bold: true, italic: true},
    {text: '」'},
  ]);
});

test('an empty story has no runs', () => {
  assert.deepEqual(parseEmphasis(''), []);
});

// --- The chosen reference -------------------------------------------------

// The bridge and React are stood in for, so this is the real store the screens use.
const bridge = {source: 'wanikani'};
const reference = loadTypeScript('src/rn/ui/kanjiReference.ts', (name) => {
  if (name === 'react') {
    return {useEffect() {}, useSyncExternalStore() {}};
  }
  if (name === '../native/GatenRtk') {
    return {
      getReferenceSource: async () => bridge.source,
      setReferenceSource: async (source) => source,
    };
  }
  return {};
});

test('a device that has not been read yet defaults to WaniKani', () => {
  assert.equal(reference.getKanjiReferenceSource(), 'wanikani');
  assert.equal(reference.DEFAULT_KANJI_REFERENCE_SOURCE, 'wanikani');
});

test('the strip shows for RTK with no WaniKani token, and for WaniKani only with one', () => {
  assert.equal(reference.showKanjiStrip('rtk', false), true);
  assert.equal(reference.showKanjiStrip('rtk', true), true);
  assert.equal(reference.showKanjiStrip('wanikani', true), true);
  assert.equal(reference.showKanjiStrip('wanikani', false), false);
});

test('the label names the selected source', () => {
  assert.equal(reference.kanjiReferenceLabel('wanikani'), 'WaniKani kanji');
  assert.equal(reference.kanjiReferenceLabel('rtk'), 'Remembering the Kanji');
});

test('the stored choice is read once, then kept', async () => {
  bridge.source = 'rtk';
  assert.equal(await reference.loadKanjiReferenceSource(), 'rtk');
  assert.equal(reference.getKanjiReferenceSource(), 'rtk');
  // A second read does not go back to the bridge; the value is already in hand.
  bridge.source = 'wanikani';
  assert.equal(await reference.loadKanjiReferenceSource(), 'rtk');
  assert.equal(reference.getKanjiReferenceSource(), 'rtk');
});

test('setting a source adopts what the device stored', async () => {
  assert.equal(await reference.setKanjiReferenceSource('wanikani'), 'wanikani');
  assert.equal(reference.getKanjiReferenceSource(), 'wanikani');
  assert.equal(await reference.setKanjiReferenceSource('rtk'), 'rtk');
  assert.equal(reference.getKanjiReferenceSource(), 'rtk');
});
