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
    if (name === './wanikani-romaji') {
      // The real converter and the real kana rule, loaded for real: the guard runs on finished kana,
      // so a case that hands the store the field's own text exercises the conversion it depends on.
      return loadTypeScript('src/rn/ui/review/wanikani-romaji.ts', () => {
        throw new Error('Unexpected import: wanikani-romaji');
      });
    }
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

/** How far apart a subject's two prompts are, keyed by subject — whichever of them is asked first. */
function pairGaps(order) {
  const at = new Map();
  order.forEach((prompt, position) => {
    const parts = at.get(prompt.index) ?? {};
    parts[prompt.part] = position;
    at.set(prompt.index, parts);
  });
  const gaps = new Map();
  for (const [index, parts] of at) {
    if (parts.meaning != null && parts.reading != null) {
      gaps.set(index, Math.abs(parts.reading - parts.meaning));
    }
  }
  return gaps;
}

/** A deterministic stand-in for `Math.random`, so a lead is drawn the same way every run. */
function seededDraw(seed) {
  let value = seed >>> 0;
  return () => (value = (value * 1103515245 + 12345) % 2147483648) / 2147483648;
}

/** The leads the spacing cases draw with: mixed, all-meaning, all-reading. */
const DRAWS = [seededDraw(1), seededDraw(2), seededDraw(9), () => 0.9, () => 0.1];

/** Every subject asked exactly its own parts, so spacing never loses or duplicates a prompt. */
function assertParts(order, parts) {
  const asked = new Map();
  for (const prompt of order) asked.set(prompt.index, (asked.get(prompt.index) ?? 0) + 1);
  assert.equal(order.length, parts.reduce((sum, count) => sum + count, 0));
  parts.forEach((count, index) => assert.equal(asked.get(index), count, `subject ${index}`));
  assert.ok(order.every((prompt) => prompt.part === 'meaning' || prompt.part === 'reading'));
}

test('a subject’s two parts are asked five to ten prompts apart, never adjacent', () => {
  for (const count of [5, 6, 7, 8, 9, 10, 11, 12, 13, 20, 21, 24, 50, 101]) {
    for (const draw of DRAWS) {
      const order = buildWaniKaniPromptOrder(Array(count).fill(2), draw);
      assertParts(order, Array(count).fill(2));
      const gaps = [...pairGaps(order).values()];
      assert.equal(gaps.length, count);
      for (const gap of gaps) {
        assert.ok(
          gap >= REVIEW_PROMPT_MIN_GAP && gap <= REVIEW_PROMPT_MAX_GAP,
          `count ${count}: gap ${gap} outside the window`
        );
      }
    }
  }
});

test('a queue too short for the window still keeps a subject’s parts apart', () => {
  for (const count of [2, 3, 4]) {
    for (const draw of DRAWS) {
      const order = buildWaniKaniPromptOrder(Array(count).fill(2), draw);
      assertParts(order, Array(count).fill(2));
      for (const gap of pairGaps(order).values()) {
        assert.ok(gap >= 2, `count ${count}: gap ${gap} is adjacent`);
      }
    }
  }
  // A single two-prompt subject has no other order than asking its two parts, either way round.
  assert.deepEqual(buildWaniKaniPromptOrder([2], () => 0.9), [
    {index: 0, part: 'meaning'},
    {index: 0, part: 'reading'},
  ]);
  assert.deepEqual(buildWaniKaniPromptOrder([2], () => 0.1), [
    {index: 0, part: 'reading'},
    {index: 0, part: 'meaning'},
  ]);
});

test('radicals share the queue without pushing a reading out of the window', () => {
  for (const parts of [
    [1, 1, 1, 1, 1, 2],
    [2, 1, 1, 1, 1, 1],
    [1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 2],
    [2, 2, 1, 2, 2, 2, 2, 2, 2, 2, 2],
  ]) {
    for (const draw of DRAWS) {
      const order = buildWaniKaniPromptOrder(parts, draw);
      assertParts(order, parts);
      for (const gap of pairGaps(order).values()) {
        assert.ok(gap >= 2, `adjacent gap ${gap} for ${JSON.stringify(parts)}`);
        assert.ok(gap <= REVIEW_PROMPT_MAX_GAP, `gap ${gap} past the window for ${JSON.stringify(parts)}`);
      }
    }
  }
});

test('which part of a subject is asked first is drawn, not fixed', () => {
  const parts = Array(8).fill(2);
  const meaningFirst = buildWaniKaniPromptOrder(parts, () => 0.9);
  const readingFirst = buildWaniKaniPromptOrder(parts, () => 0.1);
  assert.equal(meaningFirst[0].part, 'meaning');
  assert.equal(readingFirst[0].part, 'reading');
  // Turning a pair round moves neither prompt any closer to the other, so the spacing is one spacing.
  assert.deepEqual([...pairGaps(readingFirst)], [...pairGaps(meaningFirst)]);
  // A mixed draw leads with a reading for some subjects and a meaning for others.
  const mixed = buildWaniKaniPromptOrder(parts, seededDraw(3));
  const leads = new Set();
  const seen = new Set();
  for (const prompt of mixed) {
    if (seen.has(prompt.index)) continue;
    seen.add(prompt.index);
    leads.add(prompt.part);
  }
  assert.equal(leads.size, 2, `expected both leads, got ${JSON.stringify([...leads])}`);
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

/** The same, with the lead each pair is drawn with pinned: the queue is built once, at start. */
async function openReviewsWithLead(wk, draw) {
  const real = Math.random;
  Math.random = draw;
  try {
    return await openReviews(wk);
  } finally {
    Math.random = real;
  }
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
  // Meaning first for every pair, so the run the case walks is the one it names.
  const store = await openReviewsWithLead({
    reviewQueue: async () => ({status: 'available', items}),
    readingAnswer: async (typed) => typed,
    submitReview: async (subjectId, incorrectMeaning, incorrectReading) => {
      submissions.push({subjectId, incorrectMeaning, incorrectReading});
      return {status: 'accepted'};
    },
  }, () => 0.9);

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

  store.wkEntry('やま');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'reading correct');
  store.wkContinue();
  await until(() => submissions.length === 1, 'subject 0 sent');
  assert.deepEqual(submissions[0], {subjectId: 1, incorrectMeaning: 1, incorrectReading: 0});
});

test('a queue that draws a reading first asks it first and keeps the subject for its meaning', async () => {
  const submissions = [];
  const store = await openReviewsWithLead({
    reviewQueue: async () => ({status: 'available', items: [item(1), item(2)]}),
    readingAnswer: async (typed) => typed,
    submitReview: async (subjectId, incorrectMeaning, incorrectReading) => {
      submissions.push({subjectId, incorrectMeaning, incorrectReading});
      return {status: 'accepted'};
    },
  }, () => 0.1);

  // Both readings lead, and both meanings are still owed behind them.
  assert.deepEqual(
    session(store).order.map((prompt) => `${prompt.index}:${prompt.part[0]}`),
    ['0:r', '1:r', '0:m', '1:m']
  );
  assert.equal(session(store).part, 'reading');

  store.wkEntry('やま');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'first reading');
  store.wkContinue();
  assert.equal(session(store).index, 1, 'the next prompt is the other subject, not this one’s meaning');

  store.wkEntry('やま');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'second reading');
  store.wkContinue();
  assert.equal(session(store).index, 0, 'subject 0’s meaning is still owed');
  assert.equal(session(store).part, 'meaning');

  // Finishing that meaning finishes the subject, and its review is sent once.
  store.wkEntry('mountain');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'first meaning');
  store.wkContinue();
  await until(() => submissions.length === 1, 'subject 0 sent');
  assert.deepEqual(submissions[0], {subjectId: 1, incorrectMeaning: 0, incorrectReading: 0});
});

test('a reading that is not kana is refused rather than judged', async () => {
  const asked = [];
  const store = await openReviewsWithLead({
    reviewQueue: async () => ({status: 'available', items: [item(1), item(2)]}),
    readingAnswer: async (typed) => {
      asked.push(typed);
      return typed;
    },
  }, () => 0.1);
  assert.equal(session(store).part, 'reading');

  // The latin the converter could not finish is not an answer: WaniKani is asked nothing, and the
  // reading is not counted wrong.
  store.wkEntry('sひゅう');
  store.wkCheck();
  await until(() => !store.getState().busy, 'refusal');
  assert.deepEqual(asked, [], 'nothing was sent to WaniKani');
  assert.equal(session(store).phase, 'answering');
  assert.equal(session(store).entryRefused, true);
  assert.equal(session(store).incorrectReading, 0);

  // A keystroke clears the note, and kana is judged as it always was.
  store.wkEntry('しゅう');
  assert.equal(session(store).entryRefused, false);
  store.wkCheck();
  await until(() => session(store).phase === 'reviewed', 'judged wrong');
  assert.deepEqual(asked, ['しゅう']);
});

test('a reading whose trailing n is not finished yet is judged, not refused', async () => {
  const asked = [];
  const store = await openReviewsWithLead({
    reviewQueue: async () => ({
      status: 'available',
      items: [item(1, {readings: ['ほん'], primaryReading: 'ほん'})],
    }),
    readingAnswer: async (typed) => {
      asked.push(typed);
      return typed;
    },
  }, () => 0.1);
  assert.equal(session(store).part, 'reading', 'the draw led with the reading');

  // Typing `hon` leaves `ほn` in the field: the trailing n is only finished when the answer is
  // judged, so the field's own text is not junk — it is the reading it spells, and the kana WaniKani
  // is asked is the finished one.
  store.wkEntry('ほn');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'trailing n judged');
  assert.deepEqual(asked, ['ほん']);
  assert.equal(session(store).entryRefused, false);
});

const {reviewCue, reviewRefusal} = loadTypeScript('src/rn/ui/review/wanikani-cue.ts', (name) => {
  throw new Error(`Unexpected import: ${String(name)}`);
});

/** One reading prompt mid-answer, as a refusal moves between its states. */
function answeringReading(patch = {}) {
  return {
    phase: 'answering',
    index: 0,
    part: 'reading',
    entry: 'sひゅう',
    entryRefused: false,
    incorrectMeaning: 0,
    incorrectReading: 0,
    ...patch,
  };
}

test("a refused reading earns a wrong answer's cue, once, and only it", () => {
  const open = answeringReading();
  const refused = answeringReading({entryRefused: true});
  const judged = answeringReading({phase: 'reviewed', incorrectReading: 1});

  // The tap did not go through: the tone a wrong answer plays, under a key of its own.
  assert.equal(reviewRefusal(open, refused)?.tone, 'wrong');
  assert.notEqual(reviewRefusal(open, refused).key, '0:reading:wrong:0');

  // Once — not on the renders, the keystroke that clears it, or the Continue that follow.
  assert.equal(reviewCue(refused, refused), null);
  assert.equal(reviewCue(refused, answeringReading()), null);
  assert.equal(reviewCue(refused, answeringReading({entry: 'しゅう'})), null);

  // A judgement still wins when the same move carries one.
  const cue = reviewCue(refused, judged);
  assert.equal(cue.tone, 'wrong');
  assert.match(cue.key, /:wrong:1$/);

  // Typed again and refused again: cued again.
  assert.equal(reviewCue(answeringReading(), refused)?.tone, 'wrong');
});
