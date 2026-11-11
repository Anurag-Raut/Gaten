/**
 * The lesson teaching steps, exercised against the real `wanikani-lesson-steps` module. That module
 * has no runtime imports — the lesson subject is a type alone — so it loads here with nothing stubbed
 * and the rules under test are exactly the ones the store and the screen walk.
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

const {
  atTeachStart,
  lessonStepsOf,
  teachBack,
  teachDone,
  teachForward,
  teachPartsOf,
} = loadTypeScript('src/rn/ui/review/wanikani-lesson-steps.ts', (name) => {
  throw new Error(`Unexpected import: ${name}`);
});

/** A lesson subject carrying only what its steps are decided by. */
function subject(overrides) {
  return {
    type: 'vocabulary',
    readings: [],
    components: [],
    contextSentences: [],
    relatedWords: [],
    ...overrides,
  };
}

const drawn = {id: 1, character: '日', name: 'day'};
const unresolved = {id: 2, character: null, name: null};
const sentence = {japanese: '私は日本人です。', english: 'I am Japanese.'};
const word = {characters: '日本', reading: 'にほん', meaning: 'Japan'};

test('a radical is taught its meaning alone', () => {
  assert.deepEqual(lessonStepsOf(subject({type: 'radical'})), ['meaning']);
  // Even with a component list it is never taught as composition: a radical is built from nothing.
  assert.deepEqual(
    lessonStepsOf(subject({type: 'radical', components: [drawn]})),
    ['meaning']
  );
});

test('a kanji is taught meaning, reading, composition and its words', () => {
  assert.deepEqual(
    lessonStepsOf(
      subject({
        type: 'kanji',
        readings: ['にち'],
        components: [drawn],
        relatedWords: [word],
      })
    ),
    ['meaning', 'reading', 'composition', 'examples']
  );
});

test('a vocabulary word is taught meaning, reading, composition and its sentences', () => {
  assert.deepEqual(
    lessonStepsOf(
      subject({
        readings: ['たべる'],
        components: [drawn],
        contextSentences: [sentence],
      })
    ),
    ['meaning', 'reading', 'composition', 'examples']
  );
});

test('kana vocabulary is taught meaning, and its sentences when it has them', () => {
  assert.deepEqual(
    lessonStepsOf(subject({type: 'kana_vocabulary'})),
    ['meaning']
  );
  assert.deepEqual(
    lessonStepsOf(
      subject({type: 'kana_vocabulary', contextSentences: [sentence]})
    ),
    ['meaning', 'examples']
  );
});

test('components the screen cannot draw are not a composition step', () => {
  assert.deepEqual(
    lessonStepsOf(
      subject({readings: ['よむ'], components: [unresolved, unresolved]})
    ),
    ['meaning', 'reading']
  );
  // One drawable component is enough: the step is drawn for what can be shown.
  assert.deepEqual(
    lessonStepsOf(
      subject({readings: ['よむ'], components: [unresolved, drawn]})
    ),
    ['meaning', 'reading', 'composition']
  );
});

test("a kanji's examples are the words it is used in", () => {
  assert.deepEqual(
    lessonStepsOf(subject({type: 'kanji', readings: ['にち'], relatedWords: [word]})),
    ['meaning', 'reading', 'examples']
  );
  // Neither sentences nor words: no empty examples step is ever drawn.
  assert.deepEqual(
    lessonStepsOf(
      subject({readings: ['よむ'], components: [drawn], contextSentences: []})
    ),
    ['meaning', 'reading', 'composition']
  );
});

test('the batch asks for the sum of its subjects’ steps', () => {
  const batch = [
    subject({type: 'radical'}), // 1
    subject({readings: ['にち'], components: [drawn], relatedWords: [word]}), // 4
    subject({type: 'kana_vocabulary', contextSentences: [sentence]}), // 2
  ];
  assert.equal(teachPartsOf(batch), 7);
  assert.equal(teachDone(batch, 0, 0), 0);
  assert.equal(teachDone(batch, 0, 0) + 1, 1);
  assert.equal(teachDone(batch, 1, 0), 1);
  assert.equal(teachDone(batch, 1, 3), 4);
  assert.equal(teachDone(batch, 2, 1), 6);
});

test('next walks every step of every subject and then starts the quiz', () => {
  const batch = [
    subject({type: 'radical'}), // 1 step
    subject({readings: ['にち'], components: [drawn]}), // 3 steps
  ];
  const walked = [];
  let position = {teachIndex: 0, teachStep: 0};
  for (;;) {
    const next = teachForward(batch, position.teachIndex, position.teachStep);
    if (next === 'quiz') break;
    walked.push(`${next.teachIndex}:${next.teachStep}`);
    position = next;
  }
  assert.deepEqual(walked, ['1:0', '1:1', '1:2']);
  // The last step of the last subject is what starts the quiz.
  assert.equal(teachForward(batch, 1, 2), 'quiz');
  assert.equal(teachForward([subject({type: 'radical'})], 0, 0), 'quiz');
});

test('back steps through a subject, then onto the last step of the one before', () => {
  const batch = [
    subject({type: 'radical'}), // 1 step: only 0
    subject({readings: ['にち'], components: [drawn]}), // 3 steps: 0, 1, 2
    subject({type: 'kana_vocabulary', contextSentences: [sentence]}), // 2 steps: 0, 1
  ];
  assert.deepEqual(teachBack(batch, 2, 1), {teachIndex: 2, teachStep: 0});
  assert.deepEqual(teachBack(batch, 2, 0), {teachIndex: 1, teachStep: 2});
  assert.deepEqual(teachBack(batch, 1, 2), {teachIndex: 1, teachStep: 1});
  assert.deepEqual(teachBack(batch, 1, 0), {teachIndex: 0, teachStep: 0});
  // The very first step of the first subject is where there is nothing left to step back to.
  assert.equal(teachBack(batch, 0, 0), null);
  assert.equal(teachBack([], 0, 0), null);
});

test('the very first step is where Back leaves the batch instead', () => {
  assert.equal(atTeachStart({phase: 'teaching', teachIndex: 0, teachStep: 0}), true);
  assert.equal(atTeachStart({phase: 'teaching', teachIndex: 0, teachStep: 1}), false);
  assert.equal(atTeachStart({phase: 'teaching', teachIndex: 1, teachStep: 0}), false);
  // A quiz is never at a teaching start: its Back is the leave question, not a step back.
  assert.equal(atTeachStart({phase: 'quizzing', teachIndex: 0, teachStep: 0}), false);
});
