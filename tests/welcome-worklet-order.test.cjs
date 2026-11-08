/**
 * The reduced-motion crash had a cause a stub render could not see: the worklets Babel plugin
 * captures a closure array at each worklet's definition site, so a worklet declared *before* the
 * worklet it calls captures `undefined`. `welcomeFrameFor` called `welcomeStaticFrame`, and only the
 * reduced-motion branch reaches it — so the loop worked and the static state threw
 * `welcomeStaticFrame is not a function` on the UI runtime.
 *
 * This test compiles the module with the project's own babel config (the same worklets plugin and
 * preset the bundle uses) and executes both branches in Node. A plain `tsc`/transpile test cannot
 * catch this; only the compiled output reveals the eager capture.
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const babel = require('@babel/core');

const root = path.resolve(__dirname, '..');
const file = path.join(root, 'src/rn/ui/onboarding/welcome-timeline.ts');

/** The module as the app's own Babel pipeline compiles it, evaluated in this process. */
function compiledModule() {
  const {code} = babel.transformSync(fs.readFileSync(file, 'utf8'), {
    filename: file,
    configFile: path.join(root, 'babel.config.js'),
    plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')],
  });
  const module = {exports: {}};
  const run = new Function('exports', 'module', 'require', '__filename', '__dirname', code);
  run(module.exports, module, () => ({}), file, path.dirname(file));
  return module.exports;
}

const timeline = compiledModule();
const word = timeline.wordGeometry(140, 72, 48, 34);

test('the compiled module runs the reduced-motion branch, not just the loop', () => {
  const still = timeline.welcomeFrameFor(0, true, word);
  assert.equal(still.added, true);
  assert.deepEqual([...still.segments], [1, 1, 1, 1, 1]);
  assert.equal(still.resultOpacity, 1);
  assert.equal(still.captionIndex, 4);
});

test('the compiled module runs the loop branch, and it is not the static frame', () => {
  for (const t of [0, 2.3, 5.4, 7, 9.2]) {
    const frame = timeline.welcomeFrameFor(t, false, word);
    assert.equal(typeof frame.sheetOpacity, 'number');
  }
  assert.equal(timeline.welcomeFrameFor(5, false, word).added, false);
  assert.notDeepEqual(
    timeline.welcomeFrameFor(9.2, false, word),
    timeline.welcomeFrameFor(9.2, true, word),
  );
});

test('every frame worklet survives the worklets plugin on its own', () => {
  assert.equal(typeof timeline.welcomeFrame(5, word).resultOpacity, 'number');
  assert.equal(timeline.welcomeStaticFrame().added, true);
  assert.equal(typeof timeline.welcomeFrameFor(9.9, false, word).plusOpacity, 'number');
});