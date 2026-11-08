const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');

function loadTypeScript(relativePath) {
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const javascript = ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
  }).outputText;
  const module = {exports: {}};
  const run = new vm.Script(`(function (require, module, exports) { ${javascript}\n})`, {
    filename: relativePath,
  }).runInThisContext();
  run(() => ({}), module, module.exports);
  return module.exports;
}

const timeline = loadTypeScript('src/rn/ui/onboarding/welcome-timeline.ts');
const {FALLBACK_WORD, welcomeFrame, welcomeStaticFrame} = timeline;

const near = (actual, expected, label) =>
  assert.ok(Math.abs(actual - expected) < 1e-9, `${label}: ${actual} != ${expected}`);

test('the loop is five two-second scenes in a 316 x 372 dp stage', () => {
  assert.equal(timeline.SCENE_COUNT, 5);
  assert.equal(timeline.SCENE_MS, 2000);
  assert.equal(timeline.LOOP_MS, 10000);
  assert.equal(timeline.STAGE_WIDTH, 316);
  assert.equal(timeline.STAGE_HEIGHT, 372);
  assert.equal(timeline.WELCOME_CAPTIONS.length, 5);
  assert.deepEqual([...timeline.WELCOME_CAPTIONS], [
    "See a word you don't know",
    'Open Gaten',
    'Circle it',
    'See what it means',
    'Add it to Anki',
  ]);
});

test('the first caption is the longest, so it can size the caption slot', () => {
  const lengths = timeline.WELCOME_CAPTIONS.map((c) => c.length);
  assert.equal(Math.max(...lengths), lengths[0]);
});

test('the screen exposes the one required label', () => {
  assert.equal(
    timeline.WELCOME_LABEL,
    'Gaten looks up words you circle on screen and saves them to Anki.',
  );
});

test('the welcome screen is the state before page 1, and never shown once the step advanced', () => {
  assert.equal(timeline.shouldShowWelcome(0), true);
  assert.equal(timeline.shouldShowWelcome(1), false);
  assert.equal(timeline.shouldShowWelcome(2), false);
  assert.equal(timeline.shouldShowWelcome(3), false);
});

test('the sheet fades in over 0.3 s and out over 9.7-10.0 s', () => {
  assert.equal(welcomeFrame(0, FALLBACK_WORD).sheetOpacity, 0);
  near(welcomeFrame(0.15, FALLBACK_WORD).sheetOpacity, 0.875, 'half-way in');
  assert.equal(welcomeFrame(1, FALLBACK_WORD).sheetOpacity, 1);
  near(welcomeFrame(9.85, FALLBACK_WORD).sheetOpacity, 0.5, 'half-way out');
  assert.equal(welcomeFrame(10, FALLBACK_WORD).sheetOpacity, 0);
});

test('the button slides in from 60 dp out and ripples at 2.8 s', () => {
  assert.equal(welcomeFrame(1.9, FALLBACK_WORD).bubbleOpacity, 0);
  const arriving = welcomeFrame(2.2, FALLBACK_WORD);
  near(arriving.bubbleOpacity, 0.875, 'arrival');
  near(arriving.bubbleX, 7.5, 'travel');
  assert.equal(welcomeFrame(3, FALLBACK_WORD).bubbleX, 0);
  const rippling = welcomeFrame(3.1, FALLBACK_WORD);
  near(rippling.rippleOpacity, 0.3, 'ripple fade');
  near(rippling.rippleScale, 1.55, 'ripple scale');
  assert.equal(welcomeFrame(2.5, FALLBACK_WORD).rippleOpacity, 0);
  assert.equal(welcomeFrame(3.4, FALLBACK_WORD).rippleOpacity, 0);
});

test('the ellipse draws over 4.1-5.0 s and the word fills 14% from 5.0 s', () => {
  assert.equal(welcomeFrame(4.0, FALLBACK_WORD).ellipseDraw, 0);
  near(welcomeFrame(4.55, FALLBACK_WORD).ellipseDraw, 0.5, 'half drawn');
  assert.equal(welcomeFrame(5.0, FALLBACK_WORD).ellipseDraw, 1);
  assert.equal(welcomeFrame(6.0, FALLBACK_WORD).ellipseDraw, 0);
  assert.equal(welcomeFrame(4.5, FALLBACK_WORD).highlightOpacity, 0);
  near(welcomeFrame(5.15, FALLBACK_WORD).highlightOpacity, 0.07, 'half filled');
  near(welcomeFrame(5.3, FALLBACK_WORD).highlightOpacity, 0.14, 'filled');
});

test('the result card rises over 6.0-6.5 s', () => {
  assert.equal(welcomeFrame(5.9, FALLBACK_WORD).resultOpacity, 0);
  const rising = welcomeFrame(6.25, FALLBACK_WORD);
  assert.equal(rising.resultOpacity, 1);
  near(rising.resultY, 18.75, 'travel');
  const rest = welcomeFrame(6.5, FALLBACK_WORD);
  assert.equal(rest.resultOpacity, 1);
  assert.equal(rest.resultY, 0);
  assert.equal(rest.added, false);
});

test('the pill turns ✓ Added at 8.4 s and the card arcs into the mark over 8.7-9.3 s', () => {
  assert.equal(welcomeFrame(8.3, FALLBACK_WORD).added, false);
  assert.equal(welcomeFrame(8.4, FALLBACK_WORD).added, true);
  const flying = welcomeFrame(9.0, FALLBACK_WORD);
  near(flying.flyX, 198, 'arc x');
  near(flying.flyY, 73, 'arc y');
  near(flying.flyScale, 0.775, 'arc scale');
  assert.equal(welcomeFrame(8.5, FALLBACK_WORD).flyOpacity, 0);
  assert.equal(welcomeFrame(9.0, FALLBACK_WORD).flyOpacity, 1);
  assert.equal(welcomeFrame(9.4, FALLBACK_WORD).flyOpacity, 0);
  assert.equal(welcomeFrame(9.2, FALLBACK_WORD).plusOpacity, 0);
  assert.equal(welcomeFrame(9.3, FALLBACK_WORD).plusOpacity, 1);
  assert.equal(welcomeFrame(9.9, FALLBACK_WORD).plusOpacity, 0);
});

test('everything fades out over 9.4-10.0 s', () => {
  assert.equal(welcomeFrame(9.3, FALLBACK_WORD).resultOpacity, 1);
  near(welcomeFrame(9.55, FALLBACK_WORD).resultOpacity, 0.5, 'card out');
  near(welcomeFrame(9.85, FALLBACK_WORD).ankiOpacity, 0.5, 'mark out');
  near(welcomeFrame(9.55, FALLBACK_WORD).bubbleOpacity, 0.5, 'button out');
  assert.equal(welcomeFrame(9.85, FALLBACK_WORD).bubbleOpacity, 0);
  assert.equal(welcomeFrame(10, FALLBACK_WORD).resultOpacity, 0);
});

test('captions cross-fade at each scene start and the progress fills left to right', () => {
  const first = welcomeFrame(0.5, FALLBACK_WORD);
  assert.equal(first.captionIndex, 0);
  assert.equal(first.captionOpacity, 1);
  assert.equal(first.captionY, 0);
  assert.deepEqual(first.segments, [0.25, 0, 0, 0, 0]);

  const leaving = welcomeFrame(1.9, FALLBACK_WORD);
  assert.equal(leaving.captionIndex, 0);
  near(leaving.captionOpacity, 0.625, 'leaving');

  const arriving = welcomeFrame(2.05, FALLBACK_WORD);
  assert.equal(arriving.captionIndex, 1);
  near(arriving.captionOpacity, 0.25, 'arriving');
  near(arriving.captionY, 4.5, 'lift');

  const third = welcomeFrame(4.5, FALLBACK_WORD);
  assert.equal(third.captionIndex, 2);
  assert.deepEqual(third.segments, [1, 1, 0.25, 0, 0]);

  const last = welcomeFrame(9.5, FALLBACK_WORD);
  assert.equal(last.captionIndex, 4);
  assert.deepEqual(last.segments, [1, 1, 1, 1, 0.75]);
});

test('reduced motion pins the final state with no transitions', () => {
  const still = welcomeStaticFrame();
  assert.equal(still.sheetOpacity, 1);
  assert.equal(still.bubbleOpacity, 1);
  assert.equal(still.rippleOpacity, 0);
  assert.equal(still.ellipseDraw, 0);
  assert.equal(still.highlightOpacity, 0.14);
  assert.equal(still.resultOpacity, 1);
  assert.equal(still.resultY, 0);
  assert.equal(still.added, true);
  assert.equal(still.addScale, 1);
  assert.equal(still.ankiOpacity, 1);
  assert.equal(still.flyOpacity, 0);
  assert.equal(still.plusOpacity, 1);
  assert.equal(still.captionIndex, 4);
  assert.equal(still.captionOpacity, 1);
  assert.equal(still.captionY, 0);
  assert.deepEqual(still.segments, [1, 1, 1, 1, 1]);
});

test('the word box is the word plus the ellipse\'s own padding, at the fallback metrics', () => {
  const word = timeline.wordGeometry(140, 72, 48, 34);
  assert.equal(word.cx, 164);
  assert.equal(word.cy, 89);
  assert.equal(word.rx, 38);
  assert.equal(word.ry, 24);
  near(word.circumference, timeline.ellipseLength(38, 24), 'perimeter');
  assert.deepEqual(FALLBACK_WORD, word);
});

test('the ring follows the ellipse\'s stroke tip and is gone by 5.3 s', () => {
  const top = welcomeFrame(4.1, FALLBACK_WORD);
  near(top.cursorX, FALLBACK_WORD.cx, 'starts at the top');
  near(top.cursorY, FALLBACK_WORD.cy - FALLBACK_WORD.ry, 'starts at the top');
  assert.equal(top.cursorOpacity, 1);

  // Half the draw is the bottom of the ellipse, and the full draw is back at the top.
  const half = welcomeFrame(4.55, FALLBACK_WORD);
  near(half.cursorX, FALLBACK_WORD.cx, 'half way round');
  near(half.cursorY, FALLBACK_WORD.cy + FALLBACK_WORD.ry, 'half way round');
  assert.equal(half.cursorOpacity, 1);
  const full = welcomeFrame(5.0, FALLBACK_WORD);
  near(full.cursorX, FALLBACK_WORD.cx, 'full circle');
  near(full.cursorY, FALLBACK_WORD.cy - FALLBACK_WORD.ry, 'full circle');
  assert.equal(welcomeFrame(5.3, FALLBACK_WORD).cursorOpacity, 0);
});
