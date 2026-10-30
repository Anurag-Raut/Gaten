const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

const source = fs.readFileSync(path.join(__dirname, '../src/rn/ui/review/wanikani-romaji.ts'), 'utf8');
const javascript = ts.transpileModule(source, {
  compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
}).outputText;
const compiled = {exports: {}};
new Function('module', 'exports', javascript)(compiled, compiled.exports);
const {romajiToHiragana} = compiled.exports;

assert.equal(romajiToHiragana('tsu'), 'つ');
assert.equal(romajiToHiragana('kanji'), 'かんじ');
assert.equal(romajiToHiragana('kanni'), 'かんに');
assert.equal(romajiToHiragana('gakkou'), 'がっこう');
assert.equal(romajiToHiragana("shin'you"), 'しんよう');
assert.equal(romajiToHiragana('漢字ねこ'), '漢字ねこ');
assert.equal(romajiToHiragana('TSU'), 'つ');
assert.equal(romajiToHiragana(''), '');

let typed = '';
for (const character of 'tsu') typed = romajiToHiragana(typed + character);
assert.equal(typed, 'つ');
assert.equal(romajiToHiragana('ts'), 'ts');
assert.equal(romajiToHiragana('つ'), 'つ');
assert.equal(romajiToHiragana('tsu'.slice(0, 2)), 'ts');
console.log('wanikani-romaji tests passed');

assert.equal(romajiToHiragana("ほn", true), "ほん");
assert.equal(romajiToHiragana("hon", true), "ほん");
