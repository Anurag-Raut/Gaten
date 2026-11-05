/**
 * The answered-prompt cues: which session moves count as a judgement, and which do not. Exercised
 * against the real `wanikani-cue` module rather than a copy of its rules. That module carries only
 * types at runtime, so it loads here with no bridge mocks — the one import it names is erased.
 */
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
  run(
    (name) => {
      throw new Error(`Unexpected import: ${name}`);
    },
    module,
    module.exports
  );
  return module.exports;
}

const {reviewJudgement, lessonJudgement} = loadTypeScript('src/rn/ui/review/wanikani-cue.ts');

/** A review session at one prompt, in whatever state the case asks for. */
function review(patch = {}) {
  return {
    items: [],
    order: [],
    cursor: 0,
    totalParts: 2,
    doneParts: 0,
    index: 0,
    part: 'meaning',
    entry: '',
    incorrectMeaning: 0,
    incorrectReading: 0,
    wrong: [{meaning: 0, reading: 0}],
    feedback: null,
    retrying: false,
    phase: 'answering',
    failure: null,
    authRejected: false,
    ...patch,
  };
}

/** A lesson quiz session at one prompt. */
function lesson(patch = {}) {
  return {
    batch: [],
    remaining: 0,
    phase: 'quizzing',
    teachIndex: 0,
    quizIndex: 0,
    part: 'meaning',
    entry: '',
    incorrectMeaning: 0,
    incorrectReading: 0,
    feedback: null,
    retrying: false,
    failure: null,
    authRejected: false,
    doneParts: 5,
    totalParts: 10,
    ...patch,
  };
}

const CORRECT = {tone: 'right', text: '✓ Correct'};
const INCORRECT = {tone: 'wrong', text: 'Incorrect'};

test('a review prompt judged right or wrong cues exactly once', () => {
  const open = review({entry: 'grove'});
  assert.equal(
    reviewJudgement(open, review({phase: 'correct', feedback: CORRECT})).tone,
    'right'
  );
  assert.equal(
    reviewJudgement(open, review({phase: 'reviewed', feedback: INCORRECT, retrying: true})).tone,
    'wrong'
  );
});

test('a judged review prompt is not judged again by every render that follows', () => {
  // The reader typing over a judged answer, and the correct one replacing the wrong line.
  assert.equal(
    reviewJudgement(review({phase: 'correct'}), review({phase: 'correct', entry: 'grove'})),
    null
  );
  // Continue after a wrong answer: the same prompt, the retry it asked for.
  assert.equal(
    reviewJudgement(
      review({phase: 'reviewed', feedback: INCORRECT}),
      review({phase: 'answering', feedback: INCORRECT, entry: ''})
    ),
    null
  );
  // A screen mounting onto an already-judged prompt passes the same state through.
  const judged = review({phase: 'reviewed', feedback: INCORRECT});
  assert.equal(reviewJudgement(judged, judged), null);
});

test('a second wrong answer on the same review prompt cues again, with its own key', () => {
  const first = reviewJudgement(
    review({entry: 'grove'}),
    review({incorrectMeaning: 1, phase: 'reviewed', feedback: INCORRECT})
  );
  const second = reviewJudgement(
    review({incorrectMeaning: 1, phase: 'answering', feedback: INCORRECT}),
    review({incorrectMeaning: 2, phase: 'reviewed', feedback: INCORRECT})
  );
  assert.equal(second.tone, 'wrong');
  assert.notEqual(second.key, first.key);
});

test('the queue moving to another prompt is not a judgement', () => {
  assert.equal(
    reviewJudgement(
      review({phase: 'correct', feedback: CORRECT}),
      review({index: 1, cursor: 1, phase: 'answering', feedback: null})
    ),
    null
  );
});

test('a lesson prompt judged wrong cues on each wrong answer, never on the retyping', () => {
  const first = lessonJudgement(lesson({entry: 'grove'}), lesson({incorrectMeaning: 1, feedback: INCORRECT, retrying: true}));
  assert.equal(first.tone, 'wrong');
  // The reader retyping the answer the wrong one asked for: the counter has not moved.
  assert.equal(
    lessonJudgement(
      lesson({incorrectMeaning: 1, feedback: INCORRECT, retrying: true}),
      lesson({incorrectMeaning: 1, feedback: INCORRECT, retrying: true, entry: 'gr'})
    ),
    null
  );
  const second = lessonJudgement(
    lesson({incorrectMeaning: 1, feedback: INCORRECT, retrying: true}),
    lesson({incorrectMeaning: 2, feedback: INCORRECT, retrying: true})
  );
  assert.equal(second.tone, 'wrong');
  assert.notEqual(second.key, first.key);
});

test('a right lesson answer cues on the move to its reading and on the send, once each way', () => {
  // A correct meaning on a subject that has a reading: WaniKani draws no line for it, so the move
  // from meaning to reading is the cue.
  assert.equal(
    lessonJudgement(
      lesson({part: 'meaning', feedback: null}),
      lesson({part: 'reading', feedback: null, retrying: false})
    ).tone,
    'right'
  );
  // A correct reading (or a subject with none): the finished subject is sent.
  assert.equal(
    lessonJudgement(
      lesson({part: 'reading', feedback: null}),
      lesson({part: 'reading', feedback: CORRECT, phase: 'starting'})
    ).tone,
    'right'
  );
  // Sending that failed and was started again: the right answer is already on the line.
  assert.equal(
    lessonJudgement(
      lesson({part: 'reading', feedback: CORRECT, phase: 'quizzing'}),
      lesson({part: 'reading', feedback: CORRECT, phase: 'starting'})
    ),
    null
  );
  // A retry of the send, batched into the one move it makes.
  assert.equal(
    lessonJudgement(
      lesson({part: 'reading', feedback: CORRECT, phase: 'startFailed'}),
      lesson({part: 'reading', feedback: CORRECT, phase: 'starting'})
    ),
    null
  );
});

test('a lesson batch advancing to its next subject is not a judgement', () => {
  assert.equal(
    lessonJudgement(
      lesson({quizIndex: 0, incorrectMeaning: 1, phase: 'starting', feedback: CORRECT}),
      lesson({quizIndex: 1, part: 'meaning', incorrectMeaning: 0, phase: 'quizzing', feedback: null})
    ),
    null
  );
  // A teaching card never carries a quiz judgement.
  assert.equal(
    lessonJudgement(
      lesson({phase: 'teaching', teachIndex: 0}),
      lesson({phase: 'teaching', teachIndex: 1, doneParts: 6})
    ),
    null
  );
});
