/**
 * The review queue's prompt spacing and its correct-answer stop, exercised against the real
 * `review-store` module. The hub suite's harness has no mock for the kana converter the store imports,
 * so this file carries its own mocks — that one included — and the store loads here.
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

/** The review store over mocked bridges, with every import it has — the missing one included. */
function loadReviewStore(wk = {}) {
  const missing = (name) => async () => {
    throw new Error(`Unexpected WaniKani call: ${String(name)}`);
  };
  const waniKani = new Proxy(wk, {
    get(target, name) {
      if (name in target) return target[name];
      if (name === 'getLearningPreferences') return async () => ({dailyLimit: 15, learnedToday: 0});
      return missing(name);
    },
  });
  // The shared stats store the hub reads its counts through, reduced to the one read it makes.
  let stats = {status: 'loading', retryAt: null, snapshot: null};
  const analytics = {
    getWaniKaniStats: () => stats,
    invalidateWaniKaniStats: () => {},
    loadWaniKaniStats: async () => {
      const answer = await waniKani.refreshStats();
      stats = answer.status === 'available'
        ? {
            status: 'ready', snapshot: answer.snapshot, stale: answer.stale,
            failure: null, message: null, retryAfterSeconds: null, retryAt: null,
          }
        : {
            status: answer.status, snapshot: null, failure: answer.failure ?? null,
            retryAfterSeconds: answer.retryAfterSeconds ?? null, retryAt: null,
          };
    },
    resetWaniKaniStats: () => {
      stats = {status: 'loading', retryAt: null, snapshot: null};
    },
  };
  return loadTypeScript('src/rn/ui/review/review-store.ts', (name) => {
    if (name === './wanikani-romaji') return {romajiToHiragana: (text) => text};
    if (name === './meaning-match') {
      // The real rule, so this suite still exercises the store's own answer check.
      return loadTypeScript('src/rn/ui/review/meaning-match.ts', () => {
        throw new Error('Unexpected import: meaning-match');
      });
    }
    if (name === '../../native/GatenAnki') return {};
    if (name === '../../native/GatenWaniKani') return waniKani;
    if (name === '../anki/useAnkiDecks') {
      return {getAnkiDecks: () => ({}), loadAnkiDecks: async () => {}};
    }
    if (name === '../analytics/useWaniKaniStats') return analytics;
    throw new Error(`Unexpected import: ${name}`);
  });
}

const {
  buildWaniKaniPromptOrder,
  REVIEW_PROMPT_MIN_GAP,
  REVIEW_PROMPT_MAX_GAP,
} = loadReviewStore();

/** The gap between each reading and its own meaning, keyed by subject. */
function readingGaps(order) {
  const meaningAt = new Map();
  const gaps = new Map();
  order.forEach((prompt, position) => {
    if (prompt.part === 'meaning') meaningAt.set(prompt.index, position);
    else gaps.set(prompt.index, position - meaningAt.get(prompt.index));
  });
  return gaps;
}

/** Every subject asked exactly its own parts, so spacing never loses or duplicates a prompt. */
function assertParts(order, parts) {
  const asked = new Map();
  for (const prompt of order) asked.set(prompt.index, (asked.get(prompt.index) ?? 0) + 1);
  assert.equal(order.length, parts.reduce((sum, count) => sum + count, 0));
  parts.forEach((count, index) => assert.equal(asked.get(index), count, `subject ${index}`));
  assert.ok(order.every((prompt) => prompt.part === 'meaning' || prompt.part === 'reading'));
}

test('a reading is asked five to ten prompts after its meaning, never adjacent', () => {
  for (const count of [5, 6, 7, 8, 9, 10, 11, 12, 13, 20, 21, 24, 50, 101]) {
    const order = buildWaniKaniPromptOrder(Array(count).fill(2));
    assertParts(order, Array(count).fill(2));
    const gaps = [...readingGaps(order).values()];
    assert.equal(gaps.length, count);
    for (const gap of gaps) {
      assert.ok(
        gap >= REVIEW_PROMPT_MIN_GAP && gap <= REVIEW_PROMPT_MAX_GAP,
        `count ${count}: gap ${gap} outside the window`
      );
    }
  }
});

test('a queue too short for the window still keeps a subject’s parts apart', () => {
  for (const count of [2, 3, 4]) {
    const order = buildWaniKaniPromptOrder(Array(count).fill(2));
    assertParts(order, Array(count).fill(2));
    for (const gap of readingGaps(order).values()) {
      assert.ok(gap >= 2, `count ${count}: gap ${gap} is adjacent`);
    }
  }
  // A single two-prompt subject has no other order at all.
  assert.deepEqual(buildWaniKaniPromptOrder([2]), [
    {index: 0, part: 'meaning'},
    {index: 0, part: 'reading'},
  ]);
});

test('radicals share the queue without pushing a reading out of the window', () => {
  for (const parts of [
    [1, 1, 1, 1, 1, 2],
    [2, 1, 1, 1, 1, 1],
    [1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 2],
    [2, 2, 1, 2, 2, 2, 2, 2, 2, 2, 2],
  ]) {
    const order = buildWaniKaniPromptOrder(parts);
    assertParts(order, parts);
    for (const gap of readingGaps(order).values()) {
      assert.ok(gap >= 2, `adjacent gap ${gap} for ${JSON.stringify(parts)}`);
      assert.ok(gap <= REVIEW_PROMPT_MAX_GAP, `gap ${gap} past the window for ${JSON.stringify(parts)}`);
    }
  }
});

function item(id, patch = {}) {
  return {
    subjectId: id,
    type: 'kanji',
    characters: '山',
    level: 1,
    meanings: ['mountain'],
    primaryMeaning: 'mountain',
    readings: ['やま'],
    primaryReading: 'やま',
    meaningMnemonic: '',
    readingMnemonic: '',
    contextSentences: [],
    ...patch,
  };
}

function radical(id) {
  return item(id, {type: 'radical', readings: [], primaryReading: null});
}

function availableStats() {
  return {
    status: 'available',
    snapshot: {
      dueNow: 1, lessonsNow: 1, nextReviewAt: null, fetchedAt: 0, forecast: [],
    },
    cached: false,
    stale: false,
  };
}

async function until(predicate, description) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await Promise.resolve();
    if (predicate()) return;
  }
  assert.fail(`Timed out waiting for ${description}`);
}

/** The store at its first review prompt, over the queue the case gives it. */
async function openReviews(wk) {
  const {reviewStore: store} = loadReviewStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
    readingAnswer: async (typed) => typed,
    ...wk,
  });
  store.openWaniKani();
  await until(
    () => store.getState().step.kind === 'wkHome' && !store.getState().busy && !store.getState().step.refreshing,
    'hub'
  );
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview' && !store.getState().busy, 'review queue');
  return store;
}

const session = (store) => store.getState().step.session;

test('a correct answer stops with the answer on screen until the reader continues', async () => {
  const submissions = [];
  const store = await openReviews({
    reviewQueue: async () => ({status: 'available', items: [radical(1)]}),
    submitReview: async (subjectId, incorrectMeaning, incorrectReading) => {
      submissions.push({subjectId, incorrectMeaning, incorrectReading});
      return {status: 'accepted'};
    },
  });

  store.wkEntry('mountain');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'correct stop');
  // The card is held: nothing has advanced and nothing has been sent.
  assert.equal(session(store).entry, 'mountain');
  assert.equal(session(store).cursor, 0);
  assert.equal(session(store).doneParts, 0);
  assert.equal(session(store).feedback.tone, 'right');
  assert.equal(submissions.length, 0);

  // A second check while the answer is held is inert.
  store.wkCheck();
  await until(() => !store.getState().busy, 'ignored re-check');
  assert.equal(session(store).cursor, 0);

  store.wkContinue();
  await until(() => store.getState().step.kind === 'wkHome', 'review finished');
  assert.deepEqual(submissions, [{subjectId: 1, incorrectMeaning: 0, incorrectReading: 0}]);
});

test('a subject’s meaning and reading are asked apart and its meaning miss still counts', async () => {
  const submissions = [];
  const items = [item(1), item(2), item(3), item(4), item(5)];
  const store = await openReviews({
    reviewQueue: async () => ({status: 'available', items}),
    readingAnswer: async (typed) => (typed === 'yama' ? 'やま' : typed),
    submitReview: async (subjectId, incorrectMeaning, incorrectReading) => {
      submissions.push({subjectId, incorrectMeaning, incorrectReading});
      return {status: 'accepted'};
    },
  });

  // Subject 0's meaning is missed, retried, and left; its reading is not next.
  store.wkEntry('wrong');
  store.wkCheck();
  await until(() => session(store).phase === 'reviewed', 'wrong stop');
  store.wkContinue();
  assert.equal(session(store).phase, 'answering');
  assert.equal(session(store).entry, '');
  store.wkEntry('mountain');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'meaning correct');
  store.wkContinue();
  assert.equal(session(store).index, 1, 'the next prompt is another subject, not subject 0’s reading');
  assert.equal(session(store).part, 'meaning');
  assert.equal(session(store).cursor, 1);
  assert.equal(session(store).doneParts, 1);

  // Answer the other four meanings; subject 0's reading is still owed.
  for (let index = 1; index < items.length; index += 1) {
    store.wkEntry('mountain');
    store.wkCheck();
    await until(() => session(store).phase === 'correct', `meaning ${index}`);
    store.wkContinue();
  }
  assert.equal(session(store).index, 0);
  assert.equal(session(store).part, 'reading');
  assert.equal(session(store).incorrectMeaning, 1, 'the meaning miss outlived its own prompt');
  assert.equal(session(store).incorrectReading, 0);

  store.wkEntry('yama');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'reading correct');
  store.wkContinue();
  await until(() => submissions.length === 1, 'subject 0 sent');
  assert.deepEqual(submissions[0], {subjectId: 1, incorrectMeaning: 1, incorrectReading: 0});
});
