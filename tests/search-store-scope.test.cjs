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

/** The React surface the store imports: `createContext` runs at load, the rest is never called. */
const reactStub = {
  createContext: (defaultValue) => ({defaultValue}),
  useContext: () => undefined,
  useSyncExternalStore: () => undefined,
};

/**
 * The dictionary the store talks to. Results are per query so a leak between stores would show up
 * as the wrong rows, not just the wrong text.
 */
function loadSearchStore() {
  return loadTypeScript('src/rn/ui/search/searchStore.ts', (name) => {
    if (name === 'react') return reactStub;
    if (name === '../../native/GatenDict') {
      return {
        isSearchable: async (query) => query !== 'blocked',
        search: async (query) => [{id: query === 'ねこ' ? 10 : 20, primary: query}],
        recentSearches: async () => [],
        rememberSearch: async () => {},
      };
    }
    throw new Error(`Unexpected import: ${name}`);
  });
}

/** Lets the store's dictionary promises settle. */
async function flush() {
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
}

test('an overlay store never writes Home search active, query or results', async () => {
  const store = loadSearchStore();

  // Home is already searching before the overlay opens.
  store.setSearchActive(true);
  store.onQueryChange('ねこ');
  store.searchNow();
  await flush();
  const homeBefore = store.getSearchState();
  assert.equal(homeBefore.searchActive, true);
  assert.equal(homeBefore.query, 'ねこ');
  assert.deepEqual(homeBefore.hits.map((hit) => hit.id), [10]);

  // The overlay opens its own store, types, searches and closes.
  const overlay = store.createSearchStore();
  overlay.onQueryChange('いぬ');
  overlay.searchNow();
  await flush();
  assert.equal(overlay.getState().query, 'いぬ');
  assert.deepEqual(overlay.getState().hits.map((hit) => hit.id), [20]);

  // Home is exactly where it was: still searching its own query with its own rows.
  const homeAfter = store.getSearchState();
  assert.equal(homeAfter.searchActive, true);
  assert.equal(homeAfter.query, 'ねこ');
  assert.deepEqual(homeAfter.hits.map((hit) => hit.id), [10]);
  assert.equal(homeAfter.hitsQuery, 'ねこ');
});

test('closing the overlay leaves Home byte-for-byte unchanged', async () => {
  const store = loadSearchStore();

  store.setSearchActive(true);
  store.onQueryChange('ねこ');
  store.searchNow();
  await flush();
  const homeBefore = JSON.stringify(store.getSearchState());

  // One whole overlay session: open, search, exit.
  {
    const overlay = store.createSearchStore();
    overlay.setSearchActive(true);
    overlay.onQueryChange('いぬ');
    overlay.searchNow();
    await flush();
  }

  assert.equal(JSON.stringify(store.getSearchState()), homeBefore);
});

test('recent searches are per store even though the dictionary persists them', () => {
  const store = loadSearchStore();
  const overlay = store.createSearchStore();

  overlay.rememberSearchedQuery('いぬ');
  assert.deepEqual(overlay.getState().recentSearches, ['いぬ']);
  assert.deepEqual(store.getSearchState().recentSearches, []);

  store.rememberSearchedQuery('ねこ');
  assert.deepEqual(store.getSearchState().recentSearches, ['ねこ']);
  assert.deepEqual(overlay.getState().recentSearches, ['いぬ']);
});

test('a scoped store starts empty and its own flag does not open Home search', () => {
  const store = loadSearchStore();
  const overlay = store.createSearchStore();

  overlay.setSearchActive(true);
  assert.equal(overlay.getState().searchActive, true);
  assert.equal(store.getSearchState().searchActive, false);
  assert.equal(store.getSearchState().query, '');
  assert.deepEqual(store.getSearchState().hits, []);
});
