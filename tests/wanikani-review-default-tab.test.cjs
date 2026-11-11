/**
 * The WaniKani review's answer panel opens on the tab the prompt is about: a reading prompt shows
 * the Reading details first, a meaning prompt the Meaning ones, and a subject with no readings never
 * defaults to a tab the panel does not offer. The rule is a pure function so it is exercised here
 * without the review screen's renderer.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');

function loadTypeScript(relativePath, mockedRequire) {
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

const {defaultDetailTab} = loadTypeScript('src/rn/ui/review/wanikani-detail-tabs.ts');

/** The tabs the panel offers, mirroring the review screen's own `tabs` memo. */
function offeredTabs(item) {
  const list = ['meaning'];
  if (item != null && item.readings.length > 0) list.push('reading');
  if (item != null && item.contextSentences.length > 0) list.push('context');
  return list;
}

const subject = (patch = {}) => ({readings: [], contextSentences: [], ...patch});

test('a reading prompt opens on Reading', () => {
  assert.equal(defaultDetailTab('reading', subject({readings: ['いち']})), 'reading');
});

test('a meaning prompt opens on Meaning even when the subject carries a reading', () => {
  assert.equal(defaultDetailTab('meaning', subject({readings: ['いち']})), 'meaning');
});

test('a reading prompt on a subject with no readings falls back to Meaning', () => {
  assert.equal(defaultDetailTab('reading', subject()), 'meaning');
});

test('a missing subject falls back to Meaning', () => {
  assert.equal(defaultDetailTab('reading', null), 'meaning');
  assert.equal(defaultDetailTab('reading', undefined), 'meaning');
});

test('the default is always a tab the panel offers', () => {
  const items = [
    subject({readings: ['いち']}),
    subject({readings: [], contextSentences: [{japanese: 'あ', english: 'a'}]}),
    subject(),
    null,
  ];
  for (const item of items) {
    for (const part of ['meaning', 'reading']) {
      assert.ok(
        offeredTabs(item).includes(defaultDetailTab(part, item)),
        `${part} on ${JSON.stringify(item)} defaulted off the offered tab row`
      );
    }
  }
});
