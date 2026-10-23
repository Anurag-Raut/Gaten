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

const {deriveWaniKaniHub} = loadTypeScript('src/rn/ui/review/wanikani-hub-model.ts', (name) =>
  name === './review-store' ? {LESSON_BATCH_SIZE: 5} : {},
);
const now = 1_800_000_000_000;

function hub(patch = {}) {
  return {
    kind: 'wkHome',
    summary: null,
    failed: false,
    failure: null,
    connection: 'connected',
    refreshing: false,
    lessonsLocked: false,
    pausedLesson: null,
    pausedReview: null,
    reviewFailure: null,
    lessonFailure: null,
    ...patch,
  };
}

function snapshot(patch = {}) {
  return {
    dueNow: 3,
    lessonsNow: 4,
    nextReviewAt: now + 15 * 60_000,
    nextHourCount: 6,
    fetchedAt: now,
    sync: 'fresh',
    retryAfterSeconds: null,
    ...patch,
  };
}

function models(value) {
  return deriveWaniKaniHub(value, now);
}

/**
 * Every word a row shows, wherever the reshaped model puts it. Cases whose exact field is not pinned
 * by the V4 spec assert against this, so the copy is still covered without guessing its new home.
 */
function moduleText(value) {
  const trailing = value.trailing == null ? '' : `${value.trailing.value} ${value.trailing.label}`;
  return [value.title, value.subline, value.extra, trailing, value.notice]
    .filter((part) => typeof part === 'string' && part !== '')
    .join(' · ');
}

test('initial hub shows loading without presenting missing counts as zero', () => {
  const [reviews, lessons] = models(hub());
  for (const module of [reviews, lessons]) {
    assert.equal(module.loading, true);
    assert.equal(module.subline, '');
    assert.equal(module.trailing, null);
    assert.equal(module.tap, null);
    assert.equal(module.complete, false);
  }
});

test('ready snapshot uses real review detail and a preference-backed lesson batch', () => {
  const [reviews, lessons] = models(hub({
    summary: snapshot({nextHourCount: null}),
    learningPreferences: {dailyLimit: null, learnedToday: 0},
  }));
  assert.equal(reviews.subline, '3 reviews due');
  assert.deepEqual(reviews.trailing, {value: '3', label: 'due'});
  assert.equal(reviews.extra, 'Next review · In 15 min');
  assert.equal(reviews.tap, 'reviews');
  assert.equal(reviews.complete, false);
  assert.equal(lessons.subline, '4 lessons available');
  assert.deepEqual(lessons.trailing, {value: '4', label: 'ready'});
  assert.equal(lessons.extra, 'Next batch · 4 cards');
  assert.equal(lessons.tap, 'lessons');
  assert.equal(lessons.complete, false);

  const [dueNow] = models(hub({summary: snapshot({nextHourCount: null, nextReviewAt: now})}));
  assert.equal(dueNow.extra, 'Next review · Due now');
});

test('lesson batch detail stays unknown until learning preferences load', () => {
  const [, lessons] = models(hub({summary: snapshot()}));
  assert.equal(lessons.subline, '4 lessons available');
  assert.deepEqual(lessons.trailing, {value: '4', label: 'ready'});
  assert.equal(lessons.extra, null);
  assert.equal(lessons.tap, 'lessons');
});

test('failed preference read keeps a known lesson count actionable without a batch estimate', () => {
  const [, lessons] = models(hub({
    summary: snapshot({lessonsNow: 3}),
    learningPreferencesFailed: true,
  }));
  assert.equal(lessons.subline, '3 lessons available');
  assert.deepEqual(lessons.trailing, {value: '3', label: 'ready'});
  assert.equal(lessons.extra, null);
  assert.equal(lessons.tap, 'lessons');
  assert.equal(lessons.notice, 'Daily limit unavailable. Try again.');
});

test('review and lesson completion are independent when both queues are empty', () => {
  const [reviews, lessons] = models(hub({
    summary: snapshot({dueNow: 0, lessonsNow: 0, nextHourCount: null}),
  }));
  assert.equal(reviews.subline, 'All caught up');
  assert.equal(reviews.extra, 'No reviews due · Next in 15 min');
  assert.equal(reviews.complete, true);
  assert.equal(reviews.trailing, null);
  assert.equal(reviews.tap, null);
  assert.equal(lessons.subline, 'All caught up');
  assert.equal(lessons.extra, 'No new lessons available');
  assert.equal(lessons.complete, true);
  assert.equal(lessons.trailing, null);
  assert.equal(lessons.tap, null);
});

test('a cleared reviews row states the next time only when WaniKani has one', () => {
  const [withTime] = models(hub({summary: snapshot({dueNow: 0, nextHourCount: null})}));
  const [noTime] = models(hub({
    summary: snapshot({dueNow: 0, nextHourCount: null, nextReviewAt: null}),
  }));
  assert.equal(withTime.extra, 'No reviews due · Next in 15 min');
  assert.equal(noTime.extra, 'No reviews due');
  assert.equal(noTime.subline, 'All caught up');
  assert.equal(noTime.complete, true);
});

test('zero reviews do not mark nonempty lessons complete', () => {
  const [reviews, lessons] = models(hub({summary: snapshot({dueNow: 0, lessonsNow: 2})}));
  assert.equal(reviews.subline, 'All caught up');
  assert.equal(reviews.complete, true);
  assert.equal(lessons.complete, false);
  assert.deepEqual(lessons.trailing, {value: '2', label: 'ready'});
  assert.equal(lessons.tap, 'lessons');
});

test('zero lessons do not mark nonempty reviews complete', () => {
  const [reviews, lessons] = models(hub({summary: snapshot({dueNow: 1, lessonsNow: 0})}));
  assert.equal(reviews.complete, false);
  assert.equal(reviews.subline, '1 review due');
  assert.deepEqual(reviews.trailing, {value: '1', label: 'due'});
  assert.equal(reviews.tap, 'reviews');
  assert.equal(lessons.subline, 'All caught up');
  assert.equal(lessons.extra, 'No new lessons available');
  assert.equal(lessons.complete, true);
});

test('refresh keeps the confirmed counts visible and marks each queue refreshing', () => {
  const [reviews, lessons] = models(hub({summary: snapshot(), refreshing: true}));
  assert.equal(reviews.subline, '3 reviews due');
  assert.deepEqual(reviews.trailing, {value: '3', label: 'due'});
  assert.equal(lessons.subline, '4 lessons available');
  assert.deepEqual(lessons.trailing, {value: '4', label: 'ready'});
  assert.equal(reviews.refreshing, true);
  assert.equal(lessons.refreshing, true);
  assert.equal(reviews.loading, false);
});

test('disconnected and expired accounts show connect rows without stale numbers', () => {
  for (const connection of ['disconnected', 'expired']) {
    const [reviews, lessons] = models(hub({connection, summary: snapshot()}));
    for (const module of [reviews, lessons]) {
      assert.equal(module.tap, 'connect');
      assert.equal(module.trailing, null);
      assert.equal(module.complete, false);
      assert.equal(module.loading, false);
    }
  }
});

test('stale offline zero is not completion and offers sync retry rather than queue actions', () => {
  const [reviews, lessons] = models(hub({summary: snapshot({dueNow: 0, lessonsNow: 0, sync: 'offline'})}));
  for (const module of [reviews, lessons]) {
    assert.equal(module.stale, true);
    assert.equal(module.complete, false);
    assert.notEqual(module.subline, 'All caught up');
    assert.equal(module.tap, 'sync');
    assert.match(module.notice, /Offline/);
  }
});

test('stale failed snapshot offers sync retry while retaining known counts', () => {
  const [reviews, lessons] = models(hub({summary: snapshot({sync: 'failed'})}));
  assert.match(moduleText(reviews), /\b3\b/);
  assert.match(moduleText(lessons), /\b4\b/);
  assert.equal(reviews.stale, true);
  assert.equal(lessons.stale, true);
  assert.equal(reviews.tap, 'sync');
  assert.equal(lessons.tap, 'sync');
});

test('failed initial read exposes retry and rate-limit guidance', () => {
  const [reviews, lessons] = models(hub({failed: true, failure: {failure: 'RATE_LIMITED', retryAfterSeconds: 30}}));
  for (const module of [reviews, lessons]) {
    assert.equal(module.tap, 'sync');
    assert.equal(module.trailing, null);
    assert.equal(module.complete, false);
    assert.equal(module.loading, false);
  }
  assert.equal(reviews.notice, 'Try again in 30 seconds.');
});

test('locked lessons remain unavailable while the reviews queue stays actionable', () => {
  const [reviews, lessons] = models(hub({summary: snapshot(), lessonsLocked: true}));
  assert.equal(reviews.tap, 'reviews');
  assert.deepEqual(reviews.trailing, {value: '3', label: 'due'});
  assert.equal(reviews.complete, false);
  assert.equal(lessons.subline, 'Lessons unavailable');
  assert.equal(lessons.notice, 'Lessons are locked by your current level or plan.');
  assert.equal(lessons.tap, null);
  assert.equal(lessons.complete, false);
  assert.equal(lessons.trailing, null);
});

test('one queue failure leaves the other queue actionable', () => {
  const [reviews, lessons] = models(hub({summary: snapshot(), reviewFailure: {failure: 'OFFLINE', retryAfterSeconds: null}}));
  // A failed queue read retries its own queue (`reviews`); a hub-level failure retries `sync`.
  assert.ok(reviews.tap === 'reviews' || reviews.tap === 'sync');
  assert.equal(reviews.trailing, null);
  assert.equal(reviews.complete, false);
  assert.equal(reviews.notice, "You're offline. Try again when connected.");
  assert.equal(lessons.tap, 'lessons');
  assert.deepEqual(lessons.trailing, {value: '4', label: 'ready'});
  assert.equal(lessons.subline, '4 lessons available');
});

test('paused review session takes priority and reports remaining queue and progress', () => {
  const pausedReview = {items: [{}, {}, {}], index: 1, doneParts: 2, totalParts: 6};
  const [reviews] = models(hub({summary: snapshot(), pausedReview}));
  assert.equal(reviews.tap, 'reviews');
  assert.equal(reviews.complete, false);
  assert.equal(reviews.loading, false);
  assert.match(moduleText(reviews), /reviews left in session/);
  assert.match(moduleText(reviews), /2 of 6 parts/);
});

test('paused lesson session reports progress and actual mixed batch types', () => {
  const pausedLesson = {
    batch: [{type: 'radical'}, {type: 'radical'}, {type: 'kanji'}, {type: 'vocabulary'}, {type: 'kana_vocabulary'}],
    quizIndex: 2,
    doneParts: 3,
    totalParts: 11,
  };
  const [, lessons] = models(hub({summary: snapshot({lessonsNow: 0}), pausedLesson}));
  assert.equal(lessons.tap, 'lessons');
  assert.equal(lessons.complete, false);
  assert.match(moduleText(lessons), /lessons left in batch/);
  assert.match(moduleText(lessons), /3 of 11 parts/);
  assert.match(moduleText(lessons), /2 radicals · 1 kanji · 1 vocab · 1 kana vocab/);
});

test('singular queue labels and next-batch detail are grammatically singular', () => {
  const [reviews, lessons] = models(hub({
    summary: snapshot({dueNow: 1, lessonsNow: 1}),
    learningPreferences: {dailyLimit: null, learnedToday: 0},
  }));
  assert.equal(reviews.subline, '1 review due');
  assert.deepEqual(reviews.trailing, {value: '1', label: 'due'});
  assert.equal(lessons.subline, '1 lesson available');
  assert.deepEqual(lessons.trailing, {value: '1', label: 'ready'});
  assert.equal(lessons.extra, 'Next batch · 1 card');
});

test('reaching the daily lesson limit is distinct from an empty queue', () => {
  const [, lessons] = models(hub({
    summary: snapshot({lessonsNow: 4}),
    learningPreferences: {dailyLimit: 5, learnedToday: 5},
  }));
  assert.equal(lessons.tap, null);
  assert.notEqual(lessons.subline, 'All caught up');
  assert.match(moduleText(lessons), /Daily limit reached/);
  assert.doesNotMatch(moduleText(lessons), /No new lessons available/);
});

test('next lesson batch respects the queue, five-card cap and remaining daily budget', () => {
  for (const [queueCount, limit, learnedToday, expected] of [
    [12, 20, 18, 'Next batch · 2 cards'],
    [3, 20, 18, 'Next batch · 2 cards'],
    [12, 20, 0, 'Next batch · 5 cards'],
  ]) {
    const [, lessons] = models(hub({
      summary: snapshot({lessonsNow: queueCount}),
      learningPreferences: {dailyLimit: limit, learnedToday},
    }));
    assert.equal(lessons.extra, expected);
  }
});

test('a reached daily limit hides a paused lesson resume action', () => {
  const [, lessons] = models(hub({
    summary: snapshot({lessonsNow: 2}),
    learningPreferences: {dailyLimit: 3, learnedToday: 3},
    pausedLesson: {batch: [{type: 'kanji'}], quizIndex: 0, doneParts: 1, totalParts: 3},
  }));
  assert.equal(lessons.tap, null);
  assert.match(moduleText(lessons), /Daily limit reached/);
});

test('unset daily lesson limit leaves the normal five-card batch behavior unchanged', () => {
  const [, lessons] = models(hub({
    summary: snapshot({lessonsNow: 9}),
    learningPreferences: {dailyLimit: null, learnedToday: 14},
  }));
  assert.equal(lessons.subline, '9 lessons available');
  assert.equal(lessons.extra, 'Next batch · 5 cards');
});

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return {promise, resolve, reject};
}

/** The React surface the stores import but never call here: only their module state is exercised. */
const reactStub = {
  useCallback: (fn) => fn,
  useEffect: () => {},
  useSyncExternalStore: () => undefined,
};

/**
 * The three stores the flow is built from, over one pair of mocked bridges: the review store as the
 * flow sees it, and the two stores it reads its numbers through, for the cases that assert what has
 * to stay shared between the flow and Home.
 */
function buildStores(wkOverrides = {}, ankiOverrides = {}) {
  const wk = new Proxy(wkOverrides, {
    get(target, name) {
      if (name in target) return target[name];
      if (name === 'getLearningPreferences') return async () => ({dailyLimit: null, learnedToday: 0});
      return async () => { throw new Error(`Unexpected WaniKani call: ${String(name)}`); };
    },
  });
  const anki = new Proxy(ankiOverrides, {get: (target, name) => {
    if (name in target) return target[name];
    if (name === 'status') return async () => 'ready';
    return async () => { throw new Error(`Unexpected Anki call: ${String(name)}`); };
  }});
  // The stores the flow reads its numbers through, loaded for real over the same mocked bridges: the
  // flow's behaviour is what these cases pin, and the stores are part of how it now behaves. Each
  // call builds its own copies, so one case's reads and cached state never reach the next.
  const ankiDecks = loadTypeScript('src/rn/ui/anki/useAnkiDecks.ts', (name) => {
    if (name === '../../native/GatenAnki') return anki;
    if (name === 'react') return reactStub;
    throw new Error(`Unexpected import: ${name}`);
  });
  const waniKaniStats = loadTypeScript('src/rn/ui/analytics/useWaniKaniStats.ts', (name) => {
    if (name === '../../native/GatenWaniKani') return wk;
    if (name === 'react') return reactStub;
    if (name === 'react-native') return {AppState: {addEventListener: () => ({remove() {}})}};
    throw new Error(`Unexpected import: ${name}`);
  });
  const {reviewStore} = loadTypeScript('src/rn/ui/review/review-store.ts', (name) => {
    if (name === '../../native/GatenAnki') return anki;
    if (name === '../../native/GatenWaniKani') return wk;
    if (name === '../anki/useAnkiDecks') return ankiDecks;
    if (name === '../analytics/useWaniKaniStats') return waniKaniStats;
    throw new Error(`Unexpected import: ${name}`);
  });
  return {reviewStore, waniKaniStats, ankiDecks};
}

/** The review store alone, which is what most of these cases exercise. */
function mockStore(wkOverrides = {}, ankiOverrides = {}) {
  return buildStores(wkOverrides, ankiOverrides).reviewStore;
}

function availableStats(patch = {}) {
  return {
    status: 'available',
    snapshot: {
      dueNow: 1, lessonsNow: 1, nextReviewAt: null, fetchedAt: now,
      forecast: [], ...patch,
    },
    cached: false,
    stale: false,
  };
}

async function until(predicate, description) {
  for (let i = 0; i < 40; i += 1) {
    await Promise.resolve();
    if (predicate()) return;
  }
  assert.fail(`Timed out waiting for ${description}`);
}

test('store opens a connected hub with loaded counts', async () => {
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  assert.equal(store.getState().step.summary.dueNow, 1);
  assert.equal(store.getState().step.summary.lessonsNow, 1);
  assert.equal(store.getState().step.refreshing, false);
});

test("the hub's counts are the ones Home's card reads, from one read", async () => {
  let statsReads = 0;
  const {reviewStore, waniKaniStats} = buildStores({
    isConfigured: async () => true,
    refreshStats: async () => { statsReads += 1; return availableStats({dueNow: 2, lessonsNow: 3}); },
  });
  reviewStore.openWaniKani();
  await until(() => reviewStore.getState().step.kind === 'wkHome' && !reviewStore.getState().busy, 'hub snapshot');
  assert.equal(statsReads, 1);
  const shared = waniKaniStats.getWaniKaniStats();
  assert.equal(shared.status, 'ready');
  assert.equal(shared.snapshot.dueNow, 2);
  // The hub shows that same read rather than one of its own, which is what keeps the two surfaces
  // from disagreeing about what is due.
  assert.equal(reviewStore.getState().step.summary.dueNow, 2);
  assert.equal(reviewStore.getState().step.summary.lessonsNow, 3);
});

test("the picker shows the shared deck list, so Home's card cannot disagree with it", async () => {
  let deckReads = 0;
  const {reviewStore, ankiDecks} = buildStores({}, {
    status: async () => 'ready',
    decks: async () => { deckReads += 1; return [deck(1), deck(2)]; },
    getConfiguredDeckName: async () => 'Deck 1',
    getStudyDeckIds: async () => null,
  });
  reviewStore.openAnki();
  await until(() => reviewStore.getState().step.kind === 'decks' && !reviewStore.getState().busy, 'Anki deck picker');
  assert.equal(deckReads, 1);
  const shared = ankiDecks.getAnkiDecks();
  assert.equal(shared.status, 'ready');
  assert.equal(shared.configuredDeckName, 'Deck 1');
  // The picker's rows are the shared list itself, not a copy of it.
  assert.equal(reviewStore.getState().step.decks, shared.studyDecks);
});

test('store retains confirmed counts while a refresh is pending', async () => {
  const nextRead = deferred();
  let calls = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => (++calls === 1 ? availableStats() : nextRead.promise),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'initial hub snapshot');
  store.retryWaniKaniSync();
  await until(() => store.getState().step.kind === 'wkHome' && store.getState().step.refreshing, 'refresh start');
  assert.equal(store.getState().step.summary.dueNow, 1);
  assert.equal(store.getState().step.summary.lessonsNow, 1);
  nextRead.resolve(availableStats({dueNow: 2, lessonsNow: 3}));
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'refresh finish');
  assert.equal(store.getState().step.summary.dueNow, 2);
  assert.equal(store.getState().step.summary.lessonsNow, 3);
});

test('rejected refresh keeps an old zero snapshot stale and clears refreshing', async () => {
  let reads = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => {
      reads += 1;
      if (reads === 1) return availableStats({dueNow: 0, lessonsNow: 0});
      throw Object.assign(new Error('bridge unavailable'), {code: 'WK_SERVER'});
    },
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'initial zero snapshot');
  store.retryWaniKaniSync();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy && !store.getState().step.refreshing, 'failed refresh');

  const state = store.getState().step;
  assert.equal(state.failed, true);
  assert.equal(state.summary.dueNow, 0);
  assert.equal(state.summary.lessonsNow, 0);
  assert.equal(state.summary.sync, 'failed');
  const [reviews, lessons] = models(state);
  assert.equal(reviews.complete, false);
  assert.notEqual(reviews.subline, 'All caught up');
  assert.equal(reviews.tap, 'sync');
  assert.equal(lessons.complete, false);
  assert.equal(lessons.tap, 'sync');
});

test('a saved final review stays acknowledged through refresh failure and clears after fresh counts', async () => {
  const failedRefresh = deferred();
  let statsReads = 0;
  let savedReviews = 0;
  const item = {
    subjectId: 91, type: 'radical', characters: '山', meanings: ['mountain'],
    primaryMeaning: 'mountain', readings: [], primaryReading: null,
  };
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => {
      statsReads += 1;
      if (statsReads === 1) return availableStats({dueNow: 1, lessonsNow: 2});
      if (statsReads === 2) return failedRefresh.promise;
      return availableStats({dueNow: 0, lessonsNow: 2});
    },
    reviewQueue: async () => ({status: 'available', items: [item]}),
    submitReview: async () => { savedReviews += 1; return {status: 'accepted'}; },
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'initial WaniKani snapshot');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview' && !store.getState().busy, 'single review');
  store.wkEntry('mountain');
  store.wkCheck();
  await until(() => savedReviews === 1 && store.getState().step.kind === 'wkHome' && store.getState().step.refreshing, 'saved review refresh');
  assert.equal(store.getState().step.sessionFinished, true);
  // The queue is re-read before the hub presents completion: the rows are updating, not celebrating.
  const [updatingReviews, updatingLessons] = models(store.getState().step);
  assert.equal(updatingReviews.refreshing, true);
  assert.equal(updatingLessons.refreshing, true);
  assert.equal(updatingReviews.complete, false);
  assert.notEqual(updatingReviews.subline, 'All caught up');

  failedRefresh.reject(Object.assign(new Error('bridge unavailable'), {code: 'WK_SERVER'}));
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy && !store.getState().step.refreshing, 'failed post-review refresh');
  assert.equal(store.getState().step.sessionFinished, true);
  assert.equal(store.getState().step.summary.sync, 'failed');
  // The update failed, so the rows stay neutral: no completion, retry offered, nothing invented.
  const [staleReviews, staleLessons] = models(store.getState().step);
  assert.equal(staleReviews.refreshing, false);
  assert.equal(staleLessons.refreshing, false);
  assert.equal(staleReviews.stale, true);
  assert.equal(staleReviews.complete, false);
  assert.equal(staleReviews.tap, 'sync');
  assert.notEqual(staleReviews.subline, 'All caught up');
  assert.equal(staleLessons.stale, true);
  assert.equal(staleLessons.tap, 'sync');

  store.retryWaniKaniSync();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy && !store.getState().step.refreshing, 'fresh post-review refresh');
  assert.equal(store.getState().step.summary.dueNow, 0);
  assert.equal(store.getState().step.sessionFinished, false);
  const [finishedReviews, finishedLessons] = models(store.getState().step);
  assert.equal(finishedReviews.subline, 'All caught up');
  assert.equal(finishedReviews.extra, 'No reviews due');
  assert.equal(finishedReviews.complete, true);
  assert.equal(finishedReviews.tap, null);
  assert.equal(finishedLessons.subline, '2 lessons available');
  assert.deepEqual(finishedLessons.trailing, {value: '2', label: 'ready'});
});

test('store resumes a paused review at the saved answer', async () => {
  const item = {
    subjectId: 9, type: 'radical', characters: '口', meanings: ['mouth'],
    primaryMeaning: 'mouth', readings: [], primaryReading: null,
  };
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
    reviewQueue: async () => ({status: 'available', items: [item]}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview', 'review session');
  store.wkEntry('my saved answer');
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'return to hub');
  assert.equal(store.getState().step.pausedReview.entry, 'my saved answer');
  store.startReviews();
  assert.equal(store.getState().step.kind, 'wkReview');
  assert.equal(store.getState().step.session.entry, 'my saved answer');
});

test('auth rejection clears the saved review session and account queues', async () => {
  const item = {
    subjectId: 11, type: 'radical', characters: '川', meanings: ['river'],
    primaryMeaning: 'river', readings: [], primaryReading: null,
  };
  let statsReads = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => (++statsReads < 3 ? availableStats() : {status: 'authRejected'}),
    reviewQueue: async () => ({status: 'available', items: [item]}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview', 'review session');
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'paused review in hub');
  assert.ok(store.getState().step.pausedReview);
  store.retryWaniKaniSync();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy && store.getState().step.connection === 'expired', 'auth rejection');
  assert.equal(store.getState().step.pausedReview, null);
});

test('an in-flight hub read cannot restore account data after an account reset', async () => {
  const stats = deferred();
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => stats.promise,
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && store.getState().step.refreshing, 'pending hub read');
  store.resetWaniKaniAccount();
  stats.resolve(availableStats({dueNow: 99, lessonsNow: 88}));
  await until(() => !store.getState().busy, 'discarded hub read');
  assert.equal(store.getState().step.kind, 'opening');
});

test('a pending configuration check cannot reopen WaniKani after an account reset', async () => {
  const configured = deferred();
  let statsReads = 0;
  const store = mockStore({
    isConfigured: async () => configured.promise,
    refreshStats: async () => { statsReads += 1; return availableStats(); },
  });
  store.openWaniKani();
  store.resetWaniKaniAccount();
  configured.resolve(true);
  await until(() => !store.getState().busy, 'stale configuration result');
  assert.equal(store.getState().step.kind, 'opening');
  assert.equal(statsReads, 0);
});

test('a late review submission cannot advance a review session from a reset account', async () => {
  const oldSubmission = deferred();
  const oldItem = {
    subjectId: 31, type: 'radical', characters: '口', meanings: ['mouth'],
    primaryMeaning: 'mouth', readings: [], primaryReading: null,
  };
  const newItems = [
    {...oldItem, subjectId: 41},
    {...oldItem, subjectId: 42},
  ];
  let queueReads = 0;
  let submissionCalls = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
    reviewQueue: async () => ({status: 'available', items: ++queueReads === 1 ? [oldItem] : newItems}),
    submitReview: async () => (++submissionCalls === 1 ? oldSubmission.promise : {status: 'accepted'}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'first account hub');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview' && !store.getState().busy, 'first review session');
  store.wkEntry('mouth');
  store.wkCheck();
  await until(() => submissionCalls === 1 && !store.getState().busy, 'pending old review submission');

  store.resetWaniKaniAccount();
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'replacement account hub');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview' && !store.getState().busy, 'replacement review session');
  assert.equal(store.getState().step.session.index, 0);

  oldSubmission.resolve({status: 'accepted'});
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(store.getState().step.kind, 'wkReview');
  assert.equal(store.getState().step.session.items[0].subjectId, 41);
  assert.equal(store.getState().step.session.index, 0);
});

test('a late lesson start cannot advance a lesson session from a reset account', async () => {
  const oldStart = deferred();
  let queueReads = 0;
  let startCalls = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats({lessonsNow: 2}),
    getLearningPreferences: async () => ({dailyLimit: null, learnedToday: 0}),
    lessonsQueue: async () => ({
      status: 'available',
      items: queueReads++ === 0 ? [lessonItem(31)] : [lessonItem(41), lessonItem(42)],
    }),
    startLesson: async () => (++startCalls === 1 ? oldStart.promise : {status: 'started'}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'first account hub');
  store.startLessons();
  await until(() => store.getState().step.kind === 'wkLesson' && !store.getState().busy, 'first lesson batch');
  store.lessonNext();
  store.lessonEntry('mountain');
  store.lessonCheck();
  await until(() => startCalls === 1 && !store.getState().busy, 'pending old lesson start');

  store.resetWaniKaniAccount();
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'replacement account hub');
  store.startLessons();
  await until(() => store.getState().step.kind === 'wkLesson' && !store.getState().busy, 'replacement lesson batch');
  store.lessonNext();
  store.lessonNext();
  assert.equal(store.getState().step.session.quizIndex, 0);

  oldStart.resolve({status: 'started'});
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(store.getState().step.kind, 'wkLesson');
  assert.equal(store.getState().step.session.batch[0].assignmentId, 41);
  assert.equal(store.getState().step.session.quizIndex, 0);
});

test('successful queue retry clears the earlier queue failure when returning to the hub', async () => {
  const item = {
    subjectId: 10, type: 'radical', characters: '山', meanings: ['mountain'],
    primaryMeaning: 'mountain', readings: [], primaryReading: null,
  };
  let queueReads = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
    reviewQueue: async () => (++queueReads === 1
      ? {status: 'failed', failure: 'OFFLINE'}
      : {status: 'available', items: [item]}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkHome' && store.getState().step.reviewFailure, 'queue failure');
  store.startReviews();
  await until(() => store.getState().step.kind === 'wkReview', 'successful queue retry');
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'return to hub');
  assert.equal(store.getState().step.reviewFailure, null);
});

function deck(id, name = `Deck ${id}`) {
  return {id, name, newCount: id, learnCount: 0, reviewCount: 0, filtered: false};
}

function lessonItem(id, type = 'kanji') {
  return {
    assignmentId: id, subjectId: id, type, characters: '山', level: 1,
    meanings: ['mountain'], primaryMeaning: 'mountain', readings: [], primaryReading: null,
    meaningMnemonic: '', readingMnemonic: '', components: [], contextSentences: [],
  };
}

async function openAnkiPicker(studyDeckIds) {
  const decks = [deck(1), deck(2), deck(3)];
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => decks,
    getConfiguredDeckName: async () => 'Deck 1',
    getStudyDeckIds: async () => studyDeckIds,
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'Anki deck picker');
  return {store, decks};
}

async function finishSingleAnkiCard(gradeResult) {
  let visibleDeck = deck(7);
  let queueReads = 0;
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => [visibleDeck],
    getConfiguredDeckName: async () => 'Deck 7',
    getStudyDeckIds: async () => null,
    nextCard: async () => ++queueReads === 1
      ? {kind: 'due', noteId: 71, ord: 0, token: 1, buttonLabels: ['Again', 'Good'], mediaFiles: []}
      : {kind: 'empty'},
    card: async () => ({
      noteId: 71, modelId: 1, ord: 0, deckName: 'Deck 7', question: 'Q', answer: 'A', css: '',
      fieldNames: [], fields: [], typedAnswer: false,
    }),
    getPreferences: async () => null,
    // AnkiDroid's own answer: a submitted grade takes the card off the deck it came from.
    grade: async () => {
      if (gradeResult === 'submitted') {
        visibleDeck = {...deck(7), newCount: 0, learnCount: 0, reviewCount: 0};
      }
      return gradeResult;
    },
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'Anki deck picker');
  store.startDeck(deck(7));
  await until(() => store.getState().step.kind === 'session' && !store.getState().busy, 'Anki card');
  store.grade(store.getState().step, 3);
  await until(() => store.getState().step.kind === 'done' && !store.getState().busy, 'empty Anki queue');
  return {store, setVisibleDeck: (value) => { visibleDeck = value; }};
}

test('Anki null study selection means all decks, while an empty selection means no decks', async () => {
  const all = await openAnkiPicker(null);
  assert.deepEqual(all.store.getState().step.decks.map((item) => item.id), [1, 2, 3]);
  const none = await openAnkiPicker([]);
  assert.deepEqual(none.store.getState().step.decks, []);
  assert.equal(none.store.getState().step.filteredEmpty, true);
});

test('Anki specific study ids filter the picker in collection order and missing ids stay empty', async () => {
  const selected = await openAnkiPicker([3, 1]);
  assert.deepEqual(selected.store.getState().step.decks.map((item) => item.id), [1, 3]);
  const missing = await openAnkiPicker([999]);
  assert.deepEqual(missing.store.getState().step.decks, []);
});

test('one selected Anki deck stays in the picker until started', async () => {
  const queue = deferred();
  let nextCardCalls = 0;
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => [deck(7)],
    getConfiguredDeckName: async () => 'Deck 7',
    getStudyDeckIds: async () => [7],
    nextCard: async (id) => { nextCardCalls += 1; assert.equal(id, 7); return queue.promise; },
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'single-deck picker');
  assert.deepEqual(store.getState().step.decks.map((item) => item.id), [7]);
  assert.equal(nextCardCalls, 0);
  store.startDeck(deck(7));
  await until(() => nextCardCalls === 1, 'single-deck queue read');
  assert.equal(store.getState().busy, true);
  assert.equal(nextCardCalls, 1);
  queue.resolve({kind: 'empty'});
  await until(() => store.getState().step.kind === 'done' && !store.getState().busy, 'single-deck queue result');
  assert.equal(store.getState().step.deckName, 'Deck 7');
  assert.equal(nextCardCalls, 1);
});

test('starting a deck with no due cards does not read AnkiDroid queue', async () => {
  let nextCardCalls = 0;
  const emptyDeck = {...deck(7), newCount: 0, learnCount: 0, reviewCount: 0};
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => [emptyDeck],
    getConfiguredDeckName: async () => 'Deck 7',
    getStudyDeckIds: async () => null,
    nextCard: async () => { nextCardCalls += 1; return {kind: 'empty'}; },
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'empty-deck picker');
  store.startDeck(emptyDeck);
  await Promise.resolve();
  assert.equal(nextCardCalls, 0);
  assert.equal(store.getState().step.kind, 'decks');
  assert.equal(store.getState().busy, false);
});

test('resuming while the Anki picker is open refreshes its deck list', async () => {
  let deckReads = 0;
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => ++deckReads === 1 ? [deck(7)] : [deck(7), deck(8)],
    getConfiguredDeckName: async () => 'Deck 7',
    getStudyDeckIds: async () => null,
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'initial Anki picker');
  assert.equal(store.getState().step.decks.length, 1);
  store.onResumed();
  await until(() => deckReads === 2 && store.getState().step.kind === 'decks' && !store.getState().busy, 'refreshed Anki picker');
  assert.equal(store.getState().step.decks.length, 2);
});

test('a submitted Anki grade marks an empty deck complete until fresh counts become positive', async () => {
  // The grade empties the deck and the shared list is read again with it, so the picker opens on the
  // finished deck rather than on the counts it had one card ago.
  const {store, setVisibleDeck} = await finishSingleAnkiCard('submitted');
  store.openDecks();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'completed Anki deck');
  assert.deepEqual(store.getState().step.completedDeckIds, [7]);

  setVisibleDeck(deck(7));
  store.openDecks();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'Anki deck with fresh due cards');
  assert.deepEqual(store.getState().step.completedDeckIds, []);
});

test('ignored or missing Anki grades do not mark an empty deck complete', async () => {
  for (const gradeResult of ['ignored', 'notFound']) {
    const {store} = await finishSingleAnkiCard(gradeResult);
    store.openDecks();
    await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, `${gradeResult} Anki result`);
    assert.deepEqual(store.getState().step.completedDeckIds, []);
  }
});

test('changing study decks preserves an included Anki session and clears an excluded one', async () => {
  async function activeStore() {
    const store = mockStore({}, {
      status: async () => 'ready',
      decks: async () => [deck(7), deck(8)],
      getConfiguredDeckName: async () => 'Deck 7',
      getStudyDeckIds: async () => null,
      nextCard: async () => ({kind: 'due', noteId: 71, ord: 0, token: 1, buttonLabels: [], mediaFiles: []}),
      card: async () => ({
        noteId: 71, modelId: 1, ord: 0, deckName: 'Deck 7', question: 'Q', answer: 'A', css: '',
        fieldNames: [], fields: [], typedAnswer: false,
      }),
      getPreferences: async () => null,
    });
    store.openAnki();
    await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'Anki deck picker');
    store.startDeck(deck(7));
    await until(() => store.getState().step.kind === 'session' && !store.getState().busy, 'Anki card session');
    return store;
  }

  const included = await activeStore();
  const originalSession = included.getState().step;
  included.studyDeckSelectionChanged([7, 8]);
  assert.equal(included.getState().step, originalSession);
  included.studyDeckSelectionChanged(null);
  assert.equal(included.getState().step, originalSession);
  included.openAnki();
  await until(() => included.getState().step.kind === 'decks' && !included.getState().busy, 'hub after prior session');
  assert.equal(included.getState().step.decks.length, 2);
  assert.equal(included.getState().deck, null);

  const excluded = await activeStore();
  excluded.studyDeckSelectionChanged([8]);
  assert.equal(excluded.getState().step.kind, 'opening');
  assert.equal(excluded.getState().step.source, 'anki');
  assert.equal(excluded.getState().deck, null);
});

test('store limits a fetched lesson batch to the remaining daily budget', async () => {
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats({lessonsNow: 7}),
    getLearningPreferences: async () => ({dailyLimit: 4, learnedToday: 2}),
    lessonsQueue: async () => ({status: 'available', items: Array.from({length: 7}, (_, i) => lessonItem(i + 1))}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot and learning preferences');
  store.startLessons();
  await until(() => store.getState().step.kind === 'wkLesson' && !store.getState().busy, 'budgeted lesson batch');
  assert.equal(store.getState().step.session.batch.length, 2);
  assert.equal(store.getState().step.session.remaining, 5);
});

test('store refuses to start lessons when a freshly read daily budget is exhausted', async () => {
  let lessonQueueCalls = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats({lessonsNow: 3}),
    getLearningPreferences: async () => ({dailyLimit: 2, learnedToday: 2}),
    lessonsQueue: async () => { lessonQueueCalls += 1; return {status: 'available', items: [lessonItem(1)]}; },
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot and learning preferences');
  store.startLessons();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy && store.getState().step.learningPreferences.learnedToday === 2, 'daily limit check');
  assert.equal(lessonQueueCalls, 0);
  assert.equal(store.getState().step.pausedLesson, null);
});

test('native daily-limit response returns to hub without losing the pending lesson quiz', async () => {
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
    getLearningPreferences: async () => ({dailyLimit: 5, learnedToday: 4}),
    lessonsQueue: async () => ({status: 'available', items: [lessonItem(22, 'radical')]}),
    startLesson: async () => ({status: 'dailyLimitReached', dailyLimit: 5, learnedToday: 5}),
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  store.startLessons();
  await until(() => store.getState().step.kind === 'wkLesson' && !store.getState().busy, 'lesson session');
  store.lessonNext();
  store.lessonEntry('mountain');
  store.lessonCheck();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy && store.getState().step.learningPreferences.learnedToday === 5, 'native daily-limit response');
  const pending = store.getState().step.pausedLesson;
  assert.equal(pending.phase, 'quizzing');
  assert.equal(pending.entry, 'mountain');
  assert.equal(pending.quizIndex, 0);
  assert.equal(pending.doneParts, 1);
});

test('an AnkiDroid status that cannot be used blocks review instead of showing an empty deck list', async () => {
  for (const [status, failure] of [['needsPermission', 'PERMISSION'], ['notInstalled', 'NOT_INSTALLED']]) {
    let deckReads = 0;
    const store = mockStore({}, {
      status: async () => status,
      decks: async () => { deckReads += 1; return [deck(1)]; },
    });
    store.openAnki();
    await until(() => store.getState().step.kind === 'blocked' && !store.getState().busy, `${status} block`);
    assert.equal(store.getState().step.failure, failure);
    assert.equal(deckReads, 0);
    assert.equal(store.getState().step.decks, undefined);
  }
});

test('a rejected AnkiDroid status blocks review with its own failure', async () => {
  let deckReads = 0;
  const store = mockStore({}, {
    status: async () => { throw Object.assign(new Error('collection not set up'), {code: 'ANKI_NOT_SET_UP'}); },
    decks: async () => { deckReads += 1; return [deck(1)]; },
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'blocked' && !store.getState().busy, 'not-set-up block');
  assert.equal(store.getState().step.failure, 'NOT_SET_UP');
  assert.equal(deckReads, 0);
  assert.equal(store.getState().step.decks, undefined);
});

test('all-clear Anki still lists every deck and never opens an empty session', async () => {
  const decks = [1, 2, 3].map((id) => ({...deck(id), newCount: 0, learnCount: 0, reviewCount: 0}));
  let queueReads = 0;
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => decks,
    getConfiguredDeckName: async () => 'Deck 1',
    getStudyDeckIds: async () => null,
    nextCard: async () => { queueReads += 1; return {kind: 'empty'}; },
  });
  store.openAnki();
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'all-clear Anki picker');
  const step = store.getState().step;
  assert.deepEqual(step.decks.map((item) => item.id), [1, 2, 3]);
  assert.ok(step.decks.every((item) => item.newCount + item.learnCount + item.reviewCount === 0));
  assert.equal(step.filteredEmpty, false);
  // No session was tracked in this store, so nothing may claim evidence-backed completion.
  assert.deepEqual(step.completedDeckIds, []);
  // A cleared deck row does not read the scheduler, so no empty session can be reached from it.
  store.startDeck(step.decks[0]);
  await Promise.resolve();
  assert.equal(queueReads, 0);
  assert.equal(store.getState().step.kind, 'decks');
  assert.equal(store.getState().busy, false);
});

test('a pending first Anki decks read stays in the opening step, never a zero-count picker', async () => {
  const deckRead = deferred();
  let deckReads = 0;
  const store = mockStore({}, {
    status: async () => 'ready',
    decks: async () => { deckReads += 1; return deckRead.promise; },
    getConfiguredDeckName: async () => 'Deck 1',
    getStudyDeckIds: async () => null,
  });
  store.openAnki();
  await until(() => deckReads === 1 && store.getState().busy, 'pending decks read');
  assert.equal(store.getState().step.kind, 'opening');
  assert.equal(store.getState().step.source, 'anki');
  assert.equal(store.getState().step.decks, undefined);
  deckRead.resolve([deck(7)]);
  await until(() => store.getState().step.kind === 'decks' && !store.getState().busy, 'loaded deck picker');
  assert.deepEqual(store.getState().step.decks.map((item) => item.id), [7]);
});

test('a second start while the first review read is in flight fires no second read', async () => {
  const reviewRead = deferred();
  let reviewReads = 0;
  const item = {
    subjectId: 12, type: 'radical', characters: '口', meanings: ['mouth'],
    primaryMeaning: 'mouth', readings: [], primaryReading: null,
  };
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats(),
    reviewQueue: async () => { reviewReads += 1; return reviewRead.promise; },
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  store.startReviews();
  store.startReviews();
  await until(() => reviewReads === 1 && store.getState().busy, 'pending review queue read');
  store.startReviews();
  assert.equal(reviewReads, 1);
  reviewRead.resolve({status: 'available', items: [item]});
  await until(() => store.getState().step.kind === 'wkReview' && !store.getState().busy, 'review session');
  assert.equal(reviewReads, 1);
});

test('a second start while the first lessons read is in flight fires no second read', async () => {
  const lessonsRead = deferred();
  let lessonReads = 0;
  const store = mockStore({
    isConfigured: async () => true,
    refreshStats: async () => availableStats({lessonsNow: 2}),
    getLearningPreferences: async () => ({dailyLimit: null, learnedToday: 0}),
    lessonsQueue: async () => { lessonReads += 1; return lessonsRead.promise; },
  });
  store.openWaniKani();
  await until(() => store.getState().step.kind === 'wkHome' && !store.getState().busy, 'hub snapshot');
  store.startLessons();
  store.startLessons();
  await until(() => lessonReads === 1 && store.getState().busy, 'pending lesson queue read');
  store.startLessons();
  assert.equal(lessonReads, 1);
  lessonsRead.resolve({status: 'available', items: [lessonItem(1)]});
  await until(() => store.getState().step.kind === 'wkLesson' && !store.getState().busy, 'lesson batch');
  assert.equal(lessonReads, 1);
});
