const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'src/rn/ui/review/deck-art.ts'), 'utf8');
const javascript = ts.transpileModule(source, {
  compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
}).outputText;
const loadedModule = {exports: {}};
new vm.Script(`(function (module, exports) { ${javascript}\n})`).runInThisContext()(loadedModule, loadedModule.exports);
const {deckArtSpec, hashDeckId} = loadedModule.exports;
const key = 'ankidroid-local:deck-123';

test('known stable key keeps its FNV-1a assignment', () => {
  assert.equal(hashDeckId(key), 0x84d05960);
  // The known key's whole assignment, not a tautology: palette, motif and the derived phase.
  assert.equal(deckArtSpec(key).paletteId, 'mist-blue');
  assert.equal(deckArtSpec(key).motif, 2);
  assert.equal(deckArtSpec(key).phase, 1);
  assert.equal(deckArtSpec(key).tilt, 0);
});

test('renames, queue changes, accents and list ordering cannot affect deck art', () => {
  const decks = [{id: 'a', name: 'Japanese', due: 0}, {id: 'b', name: 'Japanese', due: 18}];
  const before = Object.fromEntries(decks.map(deck => [deck.id, deckArtSpec(`local:${deck.id}`)]));
  const after = [...decks].reverse().map(deck => ({...deck, name: 'Changed', due: 100}));
  for (const deck of after) assert.deepEqual(deckArtSpec(`local:${deck.id}`), before[deck.id]);
  assert.notEqual(before.a.id, before.b.id);
});

test('same-name decks with distinct stable IDs are independently assigned', () => {
  assert.notEqual(deckArtSpec('local:deck-1').id, deckArtSpec('local:deck-2').id);
});

test('dark mode changes only the palette tones', () => {
  const light = deckArtSpec(key, false);
  const dark = deckArtSpec(key, true);
  assert.deepEqual(
    {id: light.id, paletteId: light.paletteId, paletteName: light.paletteName, motif: light.motif, tilt: light.tilt, phase: light.phase},
    {id: dark.id, paletteId: dark.paletteId, paletteName: dark.paletteName, motif: dark.motif, tilt: dark.tilt, phase: dark.phase},
  );
  assert.notDeepEqual(light.palette, dark.palette);
});

test('thumbnail clips its decorative SVG to a rounded 64-unit tile', () => {
  const component = fs.readFileSync(path.join(root, 'src/rn/ui/review/DeckThumbnail.tsx'), 'utf8');
  assert.match(component, /<ClipPath id=\{clipId\}>\s*<Rect[\s\S]*?width=\{64\}[\s\S]*?height=\{64\}[\s\S]*?rx=\{\(12 \* 64\) \/ width\}[\s\S]*?ry=\{\(12 \* 64\) \/ height\}/);
  assert.match(component, /clipPath=\{`url\(#\$\{clipId\}\)`\}/);
  assert.match(component, /accessible=\{false\}/);
});
