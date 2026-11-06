/**
 * The WaniKani review's wrong-answer retry, exercised against the real `review-store`. At the wrong
 * stop the field stays editable, so Enter has to tell the reader's edited answer from the rejected
 * one: an edited entry is judged where it stands, and only an unchanged (or emptied) one is cleared
 * for the retry WaniKani requires. The hub suite's harness has no mock for the kana converter the
 * store imports, so this file carries its own mocks, as the prompt-order suite does.
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

/** The review store over mocked bridges, with every import it has — the kana converter included. */
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
      return loadTypeScript('src/rn/ui/review/wanikani-romaji.ts', () => {
        throw new Error('Unexpected import: wanikani-romaji');
      });
    }
    if (name === './meaning-match') {
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
    snapshot: {dueNow: 1, lessonsNow: 1, nextReviewAt: null, fetchedAt: 0, forecast: []},
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

/** The same, with the lead each pair is drawn with pinned, so a reading prompt can be reached first. */
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

test('a meaning retry judges the edited answer on Enter and clears only the unchanged one', async () => {
  const submissions = [];
  const store = await openReviews({
    reviewQueue: async () => ({status: 'available', items: [radical(1)]}),
    submitReview: async (subjectId, incorrectMeaning, incorrectReading) => {
      submissions.push({subjectId, incorrectMeaning, incorrectReading});
      return {status: 'accepted'};
    },
  });

  // The wrong answer stops the card in red with the miss counted and nothing sent.
  store.wkEntry('wrong');
  store.wkCheck();
  await until(() => session(store).phase === 'reviewed', 'wrong stop');
  assert.equal(session(store).entry, 'wrong');
  assert.equal(session(store).incorrectMeaning, 1);

  // Backspace it down to nothing, then Enter: there is no answer to judge, so the field is only
  // cleared for the retry — the miss stays and nothing new is counted.
  store.wkEntry('wron');
  store.wkEntry('');
  store.wkContinue();
  assert.equal(session(store).phase, 'answering');
  assert.equal(session(store).entry, '');
  assert.equal(session(store).incorrectMeaning, 1);
  assert.equal(submissions.length, 0);

  // A second wrong answer, then an edit that is still wrong: Enter judges the edit where it stands —
  // the text is kept, the prompt does not move, and exactly one more miss is counted.
  store.wkEntry('wrong');
  store.wkCheck();
  await until(() => session(store).phase === 'reviewed', 'second wrong stop');
  assert.equal(session(store).incorrectMeaning, 2);
  store.wkEntry('wrog'); // the rejected text backspaced and replaced, still not a meaning
  store.wkContinue();
  await until(() => session(store).phase === 'reviewed', 'still-wrong edit judged');
  assert.equal(session(store).entry, 'wrog', 'the still-wrong edit is kept, not erased');
  assert.equal(session(store).incorrectMeaning, 3, 'the still-wrong edit counts exactly one more miss');
  assert.equal(session(store).index, 0, 'the judged retry stays on the same card');
  assert.equal(session(store).part, 'meaning');
  assert.equal(submissions.length, 0);

  // Correcting that edit: Enter judges it right, and the one Continue past the subject sends all
  // three recorded misses exactly once.
  store.wkEntry('mountai'); // backspacing the rejected answer…
  store.wkEntry('mountain'); // …then replacing it
  store.wkContinue();
  await until(() => session(store).phase === 'correct', 'edited answer judged');
  assert.equal(session(store).entry, 'mountain', 'the edited answer is judged, not erased');
  assert.equal(session(store).incorrectMeaning, 3);

  // Enter again continues from the correct stop as before, sending the subject once.
  store.wkContinue();
  await until(() => submissions.length === 1, 'subject sent');
  assert.deepEqual(submissions[0], {subjectId: 1, incorrectMeaning: 3, incorrectReading: 0});
});

test('a reading retry judges the edited reading on Enter and keeps a refused correction a retry', async () => {
  const asked = [];
  const store = await openReviewsWithLead(
    {
      reviewQueue: async () => ({status: 'available', items: [item(1)]}),
      readingAnswer: async (typed) => {
        asked.push(typed);
        return typed;
      },
    },
    () => 0.1 // every reading leads, so the first prompt is the reading
  );
  assert.equal(session(store).part, 'reading');

  // A wrong reading stops in red with the reading miss counted.
  store.wkEntry('かわ');
  store.wkCheck();
  await until(() => session(store).phase === 'reviewed', 'wrong reading stop');
  assert.equal(session(store).incorrectReading, 1);

  // The unchanged reading plus Enter is the retry: cleared, nothing judged.
  store.wkContinue();
  assert.equal(session(store).phase, 'answering');
  assert.equal(session(store).entry, '');

  // A second wrong reading, backspaced then replaced with a correction the check refuses, is still
  // the retry: the field is not cleared, the part stays the wrong answer's retry, and nothing counts.
  store.wkEntry('かわ');
  store.wkCheck();
  await until(() => session(store).phase === 'reviewed', 'second wrong reading stop');
  assert.equal(session(store).incorrectReading, 2);
  store.wkEntry('か');
  store.wkEntry('abc'); // latin the converter cannot finish, so the check refuses it
  store.wkContinue();
  assert.equal(session(store).phase, 'answering');
  assert.equal(session(store).entryRefused, true);
  assert.equal(session(store).entry, 'abc', 'a refused correction is not cleared');
  assert.equal(session(store).retrying, true, 'the part is still the wrong answer’s retry');
  assert.equal(session(store).incorrectReading, 2);

  // Fixing the refused field and checking again judges the reading, as the prompt stays open.
  store.wkEntry('やま');
  store.wkCheck();
  await until(() => session(store).phase === 'correct', 'edited reading judged');
  assert.equal(session(store).entry, 'やま', 'the edited reading is judged, not erased');
  assert.equal(session(store).incorrectReading, 2);
  assert.deepEqual(asked.slice(-2), ['かわ', 'やま'], 'both judged readings reached the check');
});

test('a correction typed while a reading is judged is not mistaken for the retry', async () => {
  let resolveFirst;
  const firstJudgement = new Promise((resolve) => {
    resolveFirst = resolve;
  });
  const asked = [];
  const store = await openReviewsWithLead(
    {
      reviewQueue: async () => ({status: 'available', items: [item(1)]}),
      readingAnswer: async (typed) => {
        asked.push(typed);
        // The first judgement is held open so the reader can type while it is in flight.
        return asked.length === 1 ? firstJudgement : typed;
      },
    },
    () => 0.1
  );
  assert.equal(session(store).part, 'reading');

  // The wrong reading is judged against the snapshot it read, not the correction the still-editable
  // field holds by the time the judgement lands.
  store.wkEntry('かわ');
  store.wkCheck();
  store.wkEntry('やま');
  resolveFirst('かわ');
  await until(() => session(store).phase === 'reviewed', 'deferred wrong stop');
  assert.equal(session(store).reviewedEntry, 'かわ', 'the judged snapshot is what is remembered');
  assert.equal(session(store).entry, 'やま', 'the correction typed during the await survives');
  assert.equal(session(store).incorrectReading, 1);

  // Enter judges the correction rather than clearing it, and a second Enter before that judgement
  // settles cannot judge or count it twice.
  store.wkContinue();
  store.wkContinue();
  await until(() => session(store).phase === 'correct', 'correction judged');
  assert.equal(session(store).entry, 'やま');
  assert.equal(session(store).incorrectReading, 1, 'the correction was judged once');
  assert.equal(asked.length, 2, 'the held reading was judged once and the correction once');
});
