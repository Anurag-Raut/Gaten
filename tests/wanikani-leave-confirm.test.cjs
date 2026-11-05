/**
 * Which steps ask before they are left. Exercised against the real `review-store` module, so the
 * predicate the review screen calls is the one under test; the harness stands in for the bridges the
 * store names, none of which this rule touches.
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

const stubs = {
  './wanikani-romaji': {romajiToHiragana: (text) => text},
  '../../native/GatenAnki': {},
  '../../native/GatenWaniKani': new Proxy(
    {},
    {
      get: () => async () => {
        throw new Error('Unexpected WaniKani call');
      },
    }
  ),
  '../anki/useAnkiDecks': {getAnkiDecks: () => ({}), loadAnkiDecks: async () => {}},
  '../analytics/useWaniKaniStats': {
    getWaniKaniStats: () => ({status: 'loading', retryAt: null, snapshot: null}),
    invalidateWaniKaniStats: () => {},
    loadWaniKaniStats: async () => {},
    resetWaniKaniStats: () => {},
  },
};

const {shouldConfirmLeaving} = loadTypeScript('src/rn/ui/review/review-store.ts', (name) => {
  if (name in stubs) return stubs[name];
  throw new Error(`Unexpected import: ${name}`);
});

test('a step holding work in progress asks before it is left', () => {
  assert.equal(shouldConfirmLeaving({kind: 'session'}), true);
  assert.equal(shouldConfirmLeaving({kind: 'wkReview', session: {}}), true);
  assert.equal(shouldConfirmLeaving({kind: 'wkLesson', session: {}}), true);
});

test('a step with nothing in progress is left freely', () => {
  const steps = [
    {kind: 'opening', source: 'anki'},
    {kind: 'blocked', failure: 'NOT_INSTALLED'},
    {kind: 'decks', decks: [], configuredDeckName: 'Gaten'},
    {kind: 'done', deckName: 'Gaten'},
    {kind: 'failed', failure: 'PROVIDER'},
    {kind: 'wkConnect', rejected: false},
    {kind: 'wkHome'},
    {kind: 'wkFailed'},
  ];
  for (const step of steps) {
    assert.equal(shouldConfirmLeaving(step), false, `${step.kind} is free to leave`);
  }
});
