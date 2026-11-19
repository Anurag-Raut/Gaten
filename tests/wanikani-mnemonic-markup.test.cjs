const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');
const rel = 'src/rn/ui/reference/mnemonic-markup.ts';
const js = ts.transpileModule(fs.readFileSync(path.join(root, rel), 'utf8'), {
  compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
}).outputText;
const module_ = {exports: {}};
new vm.Script(`(function (module, exports) { ${js}\n})`, {filename: rel})
  .runInThisContext()(module_, module_.exports);
const {parseMnemonic} = module_.exports;

const mark = (kind, text) => `${kind}${text}`;

test('highlighted words become kinded pieces between plain text', () => {
  assert.deepEqual(parseMnemonic(`The ${mark('radical', 'ground')} and ${mark('kanji', 'One')}.`), [
    {text: 'The ', kind: null},
    {text: 'ground', kind: 'radical'},
    {text: ' and ', kind: null},
    {text: 'One', kind: 'kanji'},
    {text: '.', kind: null},
  ]);
});

test('plain text and stray markers never produce a highlight', () => {
  assert.deepEqual(parseMnemonic('plain'), [{text: 'plain', kind: null}]);
  assert.deepEqual(parseMnemonic('a b'), [{text: 'a b', kind: null}]);
  assert.deepEqual(parseMnemonic(''), []);
});
