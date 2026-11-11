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

const model = loadTypeScript('src/rn/ui/analytics/model.ts');
const anki = loadTypeScript('src/rn/ui/analytics/useAnkiStats.ts', (name) => {
  if (name === 'react') {
    return {
      useCallback: () => {},
      useEffect: () => {},
      useRef: () => ({current: 0}),
      useState: () => [null, () => {}],
    };
  }
  if (name === 'react-native') {
    return {AppState: {addEventListener: () => ({remove: () => {}})}};
  }
  if (name === '../../native/GatenAnki') {
    return {};
  }
  return {};
});

function waniKaniStore(bridge) {
  return loadTypeScript('src/rn/ui/analytics/useWaniKaniStats.ts', (name) => {
    if (name === '../../native/GatenWaniKani') return bridge;
    if (name === 'react') return {};
    if (name === 'react-native') return {AppState: {addEventListener: () => ({remove() {}})}};
    throw new Error(`Unexpected import: ${name}`);
  });
}

test('an accepted study write retires an older stats read without discarding saved numbers', async () => {
  let resolveOld;
  const oldRead = new Promise((resolve) => { resolveOld = resolve; });
  const available = (dueNow) => ({status: 'available', snapshot: {dueNow}, stale: false});
  let calls = 0;
  const store = waniKaniStore({
    refreshStats: async () => ++calls === 2 ? oldRead : available(calls === 1 ? 4 : 3),
  });
  await store.loadWaniKaniStats(true);
  const old = store.loadWaniKaniStats(true);
  store.invalidateWaniKaniStats();
  assert.equal(store.getWaniKaniStats().snapshot.dueNow, 4);
  assert.equal(store.getWaniKaniStats().stale, true);
  await store.loadWaniKaniStats(true);
  assert.equal(calls, 3);
  resolveOld(available(4));
  await old;
  assert.equal(store.getWaniKaniStats().snapshot.dueNow, 3);
  assert.equal(store.getWaniKaniStats().stale, false);
});

test('invalidating study stats preserves an account rate-limit deadline and makes no request', async () => {
  let calls = 0;
  const store = waniKaniStore({refreshStats: async () => {
    calls += 1;
    return {status: 'available', snapshot: {dueNow: 4}, stale: true,
      failure: 'RATE_LIMITED', retryAfterSeconds: 60};
  }});
  await store.loadWaniKaniStats(true);
  const deadline = store.getWaniKaniStats().retryAt;
  store.invalidateWaniKaniStats();
  await store.loadWaniKaniStats(true);
  assert.equal(store.getWaniKaniStats().retryAt, deadline);
  assert.equal(calls, 1);
});

/** Local noon on a given day, so subtracting whole days keeps the same calendar day everywhere. */
function noon(year, month, day) {
  return new Date(year, month - 1, day, 12, 0, 0).getTime();
}

/** A twelve-week window, one entry per AnkiDroid study day, daysAgo 0 first. */
function window(days, cardsFor) {
  return Array.from({length: days}, (_, daysAgo) => ({daysAgo, cards: cardsFor(daysAgo)}));
}

/** One day in milliseconds, the unit a level's duration is measured in. */
const DAY = 86_400_000;

/** The instant the level tests measure from, so a row's days read at a glance. */
const NOW = noon(2026, 6, 1);

/**
 * One level progression row, dated in whole days before [NOW]: a negative offset is a date in the
 * future, which is one of the records the page refuses to measure.
 */
function attempt(id, level, startedDaysAgo, passedDaysAgo, abandonedDaysAgo = null) {
  const at = (daysAgo) => (daysAgo == null ? null : NOW - daysAgo * DAY);
  return {
    id,
    level,
    startedAt: at(startedDaysAgo),
    passedAt: at(passedDaysAgo),
    abandonedAt: at(abandonedDaysAgo),
  };
}

test('formatCount groups thousands and dashes a missing figure', () => {
  assert.equal(model.formatCount(null), '—');
  assert.equal(model.formatCount(0), '0');
  assert.equal(model.formatCount(999), '999');
  assert.equal(model.formatCount(1000), '1,000');
  assert.equal(model.formatCount(2480), '2,480');
});

test('collectionTotal sums the four exclusive states and refuses a partial sum', () => {
  assert.equal(
    model.collectionTotal({new: 120, learning: 30, young: 400, mature: 1930}),
    2480,
  );
  assert.equal(model.collectionTotal({new: 0, learning: 0, young: 0, mature: 0}), 0);
  assert.equal(model.collectionTotal({new: 10, learning: null, young: 5, mature: 5}), null);
  assert.equal(model.collectionTotal({new: null, learning: null, young: null, mature: null}), null);
});

test('activeStages drops the not-started bucket and notStartedCount reads it', () => {
  const stages = [
    {key: 'stage0', label: 'Not started', count: 900},
    {key: 'apprentice', label: 'Apprentice', count: 40},
    {key: 'guru', label: 'Guru', count: 12},
  ];
  assert.deepEqual(model.activeStages(stages), [
    {key: 'apprentice', label: 'Apprentice', count: 40},
    {key: 'guru', label: 'Guru', count: 12},
  ]);
  assert.equal(model.notStartedCount(stages), 900);
  assert.equal(model.notStartedCount([{key: 'guru', count: 12}]), null);
});

test('stageShares makes the largest count a full turn and never divides by zero', () => {
  assert.deepEqual(model.stageShares([5, 10, 20]), [0.25, 0.5, 1]);
  assert.deepEqual(model.stageShares([0, 0, 0]), [0, 0, 0]);
  assert.deepEqual(model.stageShares([]), []);
});

test('heatmap draws an all-unknown window as unknown cells and empty slots', () => {
  // Local noon on Monday 5 January 2026: Monday is the first row of a Monday-first column, so the
  // six days after today in the last column are slots the window cannot cover yet.
  const now = noon(2026, 1, 5);
  const grid = model.heatmap(window(84, () => null), now);

  assert.equal(grid.columns.length, 12);
  grid.columns.forEach((column) => assert.equal(column.length, 7));
  assert.equal(grid.activeDays, null);
  assert.equal(grid.cards, null);
  assert.equal(grid.partial, true);

  assert.equal(grid.columns[11][0].daysAgo, 0);
  assert.deepEqual(grid.columns[11].slice(1), [null, null, null, null, null, null]);

  const cells = grid.columns.flat().filter((cell) => cell != null);
  // Twelve Monday-first weeks hold 84 slots: one is today, 77 are the days before it, and the six
  // days older than the grid's first Monday fall outside the window's own twelve weeks.
  assert.equal(cells.length, 78);
  assert.deepEqual(cells.map((cell) => cell.level), Array(78).fill(null));
  assert.ok(cells.every((cell) => cell.spoken.includes('Activity unknown')));
});

test('heatmap counts only the days it can draw', () => {
  // Monday noon: the grid's first Monday is 77 days back, so the window's last six days have no slot
  // at all — and the figures above the grid have to describe the same days the grid draws.
  const now = noon(2026, 1, 5);
  const outside = window(84, () => 0);
  for (let daysAgo = 78; daysAgo < 84; daysAgo += 1) {
    outside[daysAgo] = {daysAgo, cards: 50};
  }
  outside[83] = {daysAgo: 83, cards: null};
  const clipped = model.heatmap(outside, now);

  assert.equal(clipped.activeDays, 0);
  assert.equal(clipped.cards, 0);
  assert.equal(clipped.partial, false);
  assert.ok(
    clipped.columns.flat().filter((cell) => cell != null).every((cell) => cell.level === 0),
  );

  // The same failure inside the grid is a visible gap, and still not a figure of its own.
  const inside = window(84, () => 5);
  inside[77] = {daysAgo: 77, cards: null};
  const partial = model.heatmap(inside, now);

  assert.equal(partial.partial, true);
  assert.equal(partial.activeDays, 77);
  assert.equal(partial.cards, 77 * 5);
});

test('heatmap bands a huge day above several small ones instead of flattening them', () => {
  const now = noon(2026, 1, 5);
  const activity = window(84, () => 0);
  activity[0] = {daysAgo: 0, cards: 1200};
  for (let daysAgo = 1; daysAgo <= 6; daysAgo += 1) {
    activity[daysAgo] = {daysAgo, cards: daysAgo};
  }
  const grid = model.heatmap(activity, now);

  // Today is the last column's Monday; the six small days are the previous column's Sunday back to
  // Tuesday, and the day before them answered a real zero.
  assert.equal(grid.columns[11][0].level, 4);
  assert.deepEqual(
    [6, 5, 4, 3, 2, 1].map((row) => grid.columns[10][row].level),
    [1, 1, 2, 2, 3, 3],
  );
  assert.equal(grid.columns[10][0].level, 0);
  assert.equal(grid.activeDays, 7);
  assert.equal(grid.cards, 1200 + 1 + 2 + 3 + 4 + 5 + 6);
  assert.equal(grid.partial, false);
});

test('heatmap paints a confirmed zero window as zeros, not as unknown', () => {
  const now = noon(2026, 1, 5);
  const grid = model.heatmap(window(84, () => 0), now);

  assert.equal(grid.activeDays, 0);
  assert.equal(grid.cards, 0);
  assert.equal(grid.partial, false);
  const cells = grid.columns.flat().filter((cell) => cell != null);
  assert.equal(cells.length, 78);
  assert.ok(cells.every((cell) => cell.level === 0 && cell.cards === 0));
});

test('heatmap puts today in the last column at the weekday of now', () => {
  // Local noon on Thursday 8 January 2026: Thursday is row 3 of a Monday-first column.
  const now = noon(2026, 1, 8);
  const grid = model.heatmap(window(84, () => 3), now);

  assert.equal(grid.columns[11][3].daysAgo, 0);
  // Monday, Tuesday and Wednesday of this week are the three rows above today's.
  assert.deepEqual(
    [0, 1, 2, 3].map((row) => grid.columns[11][row].daysAgo),
    [3, 2, 1, 0],
  );
  assert.deepEqual(grid.columns[11].slice(4), [null, null, null]);
  // The week before opens with the Monday seven days back and closes with the Sunday four days back.
  assert.equal(grid.columns[10][0].daysAgo, 10);
  assert.equal(grid.columns[10][3].daysAgo, 7);
  assert.equal(grid.columns[10][6].daysAgo, 4);
});

test('heatmap speaks a confirmed zero differently from a day it could not read', () => {
  const now = noon(2026, 1, 5);
  const today = (cards) => model.heatmap([{daysAgo: 0, cards}], now).columns[11][0];

  assert.equal(today(0).spoken, 'Mon 5 Jan, No cards');
  assert.equal(today(null).spoken, 'Mon 5 Jan, Activity unknown');
  assert.equal(today(12).spoken, 'Mon 5 Jan, 12 cards');
  assert.notEqual(today(0).spoken, today(null).spoken);
});

test('freshnessLabel counts up from a read without overstating it', () => {
  const now = noon(2026, 10, 24);
  assert.equal(model.freshnessLabel(null, now), null);
  assert.equal(model.freshnessLabel(now - 10_000, now), 'Updated now');
  assert.equal(model.freshnessLabel(now - 44_000, now), 'Updated now');
  assert.equal(model.freshnessLabel(now - 20 * 60_000, now), 'Updated 20m ago');
  assert.equal(model.freshnessLabel(now - 23 * 3_600_000, now), 'Updated 23h ago');
  assert.equal(model.freshnessLabel(now - 3 * 3_600_000, now), 'Updated 3h ago');
  assert.equal(model.freshnessLabel(now - 3 * 86_400_000, now), 'Updated on 21 Oct');
});

test('accuracyOf states a side as a percentage and dashes one never asked', () => {
  assert.deepEqual(model.accuracyOf({correct: 930, total: 1000}), {share: 0.93, label: '93%'});
  // Nothing answered is a dash rather than a zero percent: there is no accuracy to state.
  assert.deepEqual(model.accuracyOf({correct: 0, total: 0}), {share: null, label: '—'});
  assert.deepEqual(model.accuracyOf(null), {share: null, label: '—'});
});

test('overallAccuracy weights the two sides by the answers behind them', () => {
  // Meaning: 1000 answers at 99 %. Reading: 100 at 50 %. Averaging the two percentages would say
  // 75 %, which is not what the account did.
  const overall = model.overallAccuracy({
    meaning: {correct: 990, total: 1000},
    reading: {correct: 50, total: 100},
  });
  assert.equal(overall.correct, 1040);
  assert.equal(overall.total, 1100);
  assert.equal(overall.wrong, 60);
  assert.equal(overall.label, '95%');
  assert.equal(overall.share, 1040 / 1100);

  // One side answered, one never asked: the record is the side that exists.
  assert.deepEqual(model.overallAccuracy({meaning: null, reading: {correct: 18, total: 20}}), {
    correct: 18,
    total: 20,
    wrong: 2,
    share: 0.9,
    label: '90%',
  });

  // Nothing answered at all is a dash, and no record at all is a third fact, not the same one.
  assert.deepEqual(model.overallAccuracy({meaning: null, reading: null}), {
    correct: 0,
    total: 0,
    wrong: 0,
    share: null,
    label: '—',
  });
  assert.equal(model.overallAccuracy(null), null);
});

test('subjectAccuracies keeps every kind and dashes the ones never asked', () => {
  assert.deepEqual(
    model.subjectAccuracies([
      {key: 'radical', label: 'Radicals', correct: 96, total: 100},
      {key: 'kanji', label: 'Kanji', correct: 0, total: 0},
      {key: 'vocabulary', label: 'Vocabulary', correct: 124, total: 140},
    ]),
    [
      {key: 'radical', name: 'Radicals', value: '96%'},
      {key: 'kanji', name: 'Kanji', value: '—'},
      {key: 'vocabulary', name: 'Vocabulary', value: '89%'},
    ],
  );
  // A read that did not answer the breakdown leaves the group out rather than drawing it empty.
  assert.deepEqual(model.subjectAccuracies(null), []);
  assert.deepEqual(model.subjectAccuracies([]), []);
});

test('levelDurations measures passed levels and the current run, oldest first', () => {
  const history = [
    attempt(1, 15, 40, 33),
    attempt(2, 16, 33, 28),
    attempt(3, 17, 28, 20),
    attempt(4, 18, 20, null),
  ];
  assert.deepEqual(model.levelDurations(history, NOW, 18), [
    {level: 15, days: 7, current: false},
    {level: 16, days: 5, current: false},
    {level: 17, days: 8, current: false},
    {level: 18, days: 20, current: true},
  ]);
  assert.equal(model.daysOnLevel(history, NOW, 18), 20);
  // Only the level the snapshot names is still running: an older unpassed run has no end to measure.
  assert.equal(model.daysOnLevel([attempt(5, 17, 28, null)], NOW, 18), null);
  // The run so far is not a finished level, so it is not part of the average.
  assert.equal(model.averageLevelDays(history, NOW, 18), (7 + 5 + 8) / 3);
});

test('levelDurations drops abandoned runs and keeps the newest attempt at a level', () => {
  // Level 12 was reached, reset out of, and reached again: the abandoned stretch is not the level's
  // duration, and measuring both attempts would say the level took 40 days.
  const history = [
    attempt(1, 12, 60, null, 30),
    attempt(2, 12, 30, 24),
    attempt(3, 13, 24, null),
  ];
  assert.deepEqual(model.levelDurations(history, NOW, 13), [
    {level: 12, days: 6, current: false},
    {level: 13, days: 24, current: true},
  ]);
  assert.equal(model.averageLevelDays(history, NOW, 13), 6);

  // A level whose only attempt was abandoned has no duration and no row.
  const reset = [attempt(4, 9, 60, null, 30), attempt(5, 10, 30, null)];
  assert.deepEqual(model.levelDurations(reset, NOW, 10), [{level: 10, days: 30, current: true}]);
  assert.equal(model.averageLevelDays(reset, NOW, 10), null);
});

test('levelDurations refuses ranges it cannot trust and windows to the newest five', () => {
  const history = [
    attempt(1, 5, null, 4),
    attempt(2, 6, 30, 40),
    attempt(3, 7, -10, -5),
    attempt(4, 8, 60, 50),
    attempt(5, 9, 50, 44),
    attempt(6, 10, 44, 38),
    attempt(7, 11, 38, 32),
    attempt(8, 12, 32, 26),
    attempt(9, 13, 26, null),
    // Above the level the snapshot names: a snapshot's level is the frontier, not the newest row.
    attempt(10, 14, 26, 20),
  ];
  assert.deepEqual(model.levelDurations(history, NOW, 13), [
    {level: 9, days: 6, current: false},
    {level: 10, days: 6, current: false},
    {level: 11, days: 6, current: false},
    {level: 12, days: 6, current: false},
    {level: 13, days: 26, current: true},
  ]);
  // The average is over the finished levels that could be measured: the never-started, the inverted
  // and the future-dated rows are left out, and so is the level the account is standing on.
  assert.equal(model.averageLevelDays(history, NOW, 13), (10 + 6 + 6 + 6 + 6) / 5);
});

test('daysOnLevelLabel states whole days, and a level started today honestly', () => {
  assert.equal(model.daysOnLevelLabel(5.9), '5 days on this level');
  assert.equal(model.daysOnLevelLabel(1), '1 day on this level');
  assert.equal(model.daysOnLevelLabel(0.4), 'Less than a day on this level');
  assert.equal(model.daysOnLevelLabel(null), null);
});

test('ankiStatsQueries pins the four collection-wide card-state searches', () => {
  assert.deepEqual(anki.ankiStatsQueries(), [
    'is:new',
    'is:learn',
    'is:review prop:ivl<21',
    'is:review prop:ivl>=21',
  ]);
});

test('ankiActivityQueries pins twelve weeks of exact study-day searches', () => {
  const queries = anki.ankiActivityQueries();
  assert.equal(queries.length, 84);
  assert.equal(queries.length, anki.ANKI_ACTIVITY_DAYS);
  assert.equal(queries[0], 'prop:rated=0');
  assert.equal(queries[1], 'prop:rated=-1');
  assert.equal(queries[83], 'prop:rated=-83');
  assert.equal(new Set(queries).size, queries.length);
  // The bridge refuses more than ANKI_QUERIES_PER_CALL searches in one call, so the window has to
  // divide into whole chunks: a ragged last chunk would be a read that fails on the final day.
  assert.equal(queries.length % anki.ANKI_QUERIES_PER_CALL, 0);
  assert.ok(queries.every((query) => query.startsWith('prop:rated=')));
});

test('useAnkiStats loads without the bridge and exposes only the refresh action', () => {
  assert.equal(typeof anki.useAnkiStats, 'function');
  assert.equal(anki.ANKI_STATS_TTL_MS, 5 * 60 * 1000);
  assert.equal(anki.ANKI_ACTIVITY_DAYS, 84);
  assert.equal(anki.ANKI_QUERIES_PER_CALL, 12);
});
