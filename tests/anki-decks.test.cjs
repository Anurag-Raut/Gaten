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

/** The React surface the store imports but never calls here: only its module state is exercised. */
const reactStub = {
  useCallback: (fn) => fn,
  useEffect: () => {},
  useSyncExternalStore: () => undefined,
};

function deck(id, name = `Deck ${id}`) {
  return {id, name, newCount: id, learnCount: 0, reviewCount: 0, filtered: false};
}

/**
 * The deck store over one mocked AnkiDroid. Every call builds its own copy of the store, so one
 * case's cached list and in-flight read never reach the next.
 */
function storeOver(anki = {}) {
  const bridge = new Proxy(anki, {
    get(target, name) {
      if (name in target) return target[name];
      if (name === 'status') return async () => 'ready';
      return async () => { throw new Error(`Unexpected Anki call: ${String(name)}`); };
    },
  });
  return loadTypeScript('src/rn/ui/anki/useAnkiDecks.ts', (name) => {
    if (name === '../../native/GatenAnki') return bridge;
    if (name === 'react') return reactStub;
    throw new Error(`Unexpected import: ${name}`);
  });
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return {promise, resolve, reject};
}

async function until(predicate, description) {
  for (let i = 0; i < 40; i += 1) {
    await Promise.resolve();
    if (predicate()) return;
  }
  assert.fail(`Timed out waiting for ${description}`);
}

test('one read holds the list, the study selection and the deck Gaten exports to', async () => {
  const store = storeOver({
    decks: async () => [deck(1), deck(2), deck(3)],
    getConfiguredDeckName: async () => 'Deck 1',
    getStudyDeckIds: async () => [3, 1],
  });
  await store.loadAnkiDecks();
  const state = store.getAnkiDecks();
  assert.equal(state.status, 'ready');
  assert.equal(state.failure, null);
  assert.equal(state.refreshing, false);
  assert.equal(state.configuredDeckName, 'Deck 1');
  assert.deepEqual(state.decks.map((item) => item.id), [1, 2, 3]);
  // The selection filters the list without reordering it.
  assert.deepEqual(state.studyDecks.map((item) => item.id), [1, 3]);
});

test('a null study selection means every deck, an empty one means none', async () => {
  const all = storeOver({
    decks: async () => [deck(1), deck(2)],
    getStudyDeckIds: async () => null,
  });
  await all.loadAnkiDecks();
  assert.deepEqual(all.getAnkiDecks().studyDecks.map((item) => item.id), [1, 2]);

  const none = storeOver({
    decks: async () => [deck(1), deck(2)],
    getStudyDeckIds: async () => [],
  });
  await none.loadAnkiDecks();
  assert.deepEqual(none.getAnkiDecks().studyDecks, []);
  // The list itself is still every deck: the picker says the selection filtered it out.
  assert.deepEqual(none.getAnkiDecks().decks.map((item) => item.id), [1, 2]);
});

test('a read already in flight is joined rather than repeated', async () => {
  const pending = deferred();
  let deckReads = 0;
  const store = storeOver({
    decks: async () => { deckReads += 1; return pending.promise; },
    getStudyDeckIds: async () => null,
  });
  const first = store.loadAnkiDecks();
  const second = store.loadAnkiDecks();
  await until(() => deckReads === 1, 'the one deck read both callers wait on');
  assert.equal(deckReads, 1);
  pending.resolve([deck(7)]);
  await Promise.all([first, second]);
  await until(() => store.getAnkiDecks().status === 'ready', 'joined read to land');
  assert.equal(deckReads, 1);
  assert.deepEqual(store.getAnkiDecks().decks.map((item) => item.id), [7]);
});

test('a provider that will not answer keeps the last list and says why', async () => {
  let reads = 0;
  const store = storeOver({
    decks: async () => {
      reads += 1;
      if (reads === 1) return [deck(7)];
      throw Object.assign(new Error('no answer'), {code: 'ANKI_NOT_FOUND'});
    },
    getStudyDeckIds: async () => null,
  });
  await store.loadAnkiDecks();
  await store.loadAnkiDecks();
  const state = store.getAnkiDecks();
  assert.equal(state.status, 'ready');
  assert.equal(state.failure, 'PROVIDER');
  assert.equal(state.refreshing, false);
  // The list is what it was: a read that failed is not a collection that went away.
  assert.deepEqual(state.decks.map((item) => item.id), [7]);
});

test('AnkiDroid going away takes the list with it', async () => {
  let reads = 0;
  const store = storeOver({
    decks: async () => {
      reads += 1;
      if (reads === 1) return [deck(7)];
      throw Object.assign(new Error('permission taken back'), {code: 'ANKI_PERMISSION'});
    },
    getStudyDeckIds: async () => null,
  });
  await store.loadAnkiDecks();
  await store.loadAnkiDecks();
  const state = store.getAnkiDecks();
  assert.equal(state.status, 'unavailable');
  assert.equal(state.failure, 'PERMISSION');
  assert.equal(state.decks, null);
  assert.equal(state.studyDecks, null);
});

test('a collection that is not set up is named, and its decks are never read', async () => {
  let deckReads = 0;
  const store = storeOver({
    status: async () => { throw Object.assign(new Error('no collection'), {code: 'ANKI_NOT_SET_UP'}); },
    decks: async () => { deckReads += 1; return [deck(1)]; },
  });
  await store.loadAnkiDecks();
  const state = store.getAnkiDecks();
  assert.equal(state.status, 'unavailable');
  assert.equal(state.failure, 'NOT_SET_UP');
  assert.equal(deckReads, 0);
});
