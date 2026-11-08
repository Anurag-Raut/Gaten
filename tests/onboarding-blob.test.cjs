/**
 * The onboarding blob: three keyframes with one topology that interpolate into valid outlines.
 * The module is compiled with the app's own Babel config (worklets plugin included) so the
 * worklet declaration order is exercised exactly as on device, and its source is scanned so every
 * function `blobPath` calls carries the "worklet" directive.
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const babel = require('@babel/core');

const root = path.resolve(__dirname, '..');
const file = path.join(root, 'src/rn/ui/onboarding/onboarding-blob.ts');
const text = fs.readFileSync(file, 'utf8');

const {code} = babel.transformSync(text, {
  filename: file,
  configFile: path.join(root, 'babel.config.js'),
  plugins: [require.resolve('@babel/plugin-transform-modules-commonjs')],
});
const mod = {exports: {}};
new Function('exports', 'module', 'require', code)(mod.exports, mod, () => ({}));
const {BLOB_KEYS, BLOB_ANCHORS, BLOB_BODY_SPRING, BLOB_SLOSH_SPRING, blobPath} = mod.exports;

const numbers = (d) => d.match(/-?\d+(?:\.\d+)?/g).map(Number);
const near = (a, b) => Math.abs(a - b) <= 0.06;

/** The resting outline of anchors [key] (x0, y0, ...), built independently of the worklet. */
function rest(key) {
  const n = key.length / 2;
  const P = (i) => [key[2 * ((i + n) % n)], key[2 * ((i + n) % n) + 1]];
  const out = [...P(0)];
  for (let i = 0; i < n; i += 1) {
    const [p0, p1, p2, p3] = [P(i - 1), P(i), P(i + 1), P(i + 2)];
    out.push(p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6, p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6, p2[0], p2[1]);
  }
  return out;
}

test('three keyframes share one anchor count and are finite', () => {
  assert.equal(BLOB_KEYS.length, 3);
  for (const key of BLOB_KEYS) {
    assert.equal(key.length, 2 * BLOB_ANCHORS);
    assert.ok(key.every(Number.isFinite));
  }
  assert.notDeepEqual(BLOB_KEYS[0], BLOB_KEYS[1]);
  assert.notDeepEqual(BLOB_KEYS[1], BLOB_KEYS[2]);
});

test('the outline is a closed path of 1 + anchors segments at any lag', () => {
  for (const [s, f] of [[0, 0], [0.5, 0], [1, 0.3], [1.7, 2], [2, 2], [0.2, -4]]) {
    const d = blobPath(s, f, f);
    assert.ok(d.startsWith('M') && d.endsWith('Z'));
    assert.equal((d.match(/C/g) || []).length, BLOB_ANCHORS);
    assert.equal(numbers(d).length, 2 + 6 * BLOB_ANCHORS);
  }
});

test('at lag 0 the deformation is the identity: the outline is the keyframe spline', () => {
  BLOB_KEYS.forEach((key, i) => numbers(blobPath(i, i, i)).forEach((v, j) => assert.ok(near(v, rest(key)[j]), `stop ${i} #${j}`)));
  const mid = BLOB_KEYS[0].map((v, j) => (v + BLOB_KEYS[1][j]) / 2);
  numbers(blobPath(0.5, 0.5, 0.5)).forEach((v, j) => assert.ok(near(v, rest(mid)[j]), `mid #${j}`));
});

test('the deformation is bounded and finite for extreme lag and scene', () => {
  for (const scene of [-5, 0, 0.5, 1, 1.5, 2, 9]) {
    for (const follow of [-1e6, -3, -0.4, -0.05, 0, 0.05, 0.4, 3, 1e6, scene]) {
      const v = numbers(blobPath(scene, follow, -follow / 2));
      assert.ok(v.every(Number.isFinite), `${scene}/${follow}`);
      v.forEach((n, j) => {
        if (j % 2 === 0) assert.ok(n > 0 && n < 340, `x ${n}`);
        else assert.ok(n > -2 && n < 178, `y ${n} (${scene}/${follow})`);
      });
    }
  }
});

test('every function blobPath reaches is a worklet, declared before its callers', () => {
  const order = ['blobClamp', 'blobRound', 'blobCoord', 'blobSegment', 'blobPath'];
  let last = -1;
  for (const name of order) {
    const m = new RegExp(`function\\s+${name}\\s*\\([^)]*\\)[^{]*\\{\\s*['"]worklet['"]`).exec(text);
    assert.ok(m, `${name} is missing the worklet directive`);
    assert.ok(m.index > last, `${name} must be declared after its callees`);
    last = m.index;
  }
});

test('the artwork draws no ground shadow', () => {
  const art = fs.readFileSync(path.join(root, 'src/rn/ui/onboarding/OnboardingArtwork.tsx'), 'utf8');
  assert.ok(!/Ellipse/.test(art));
});

test('both followers are overdamped, so the blob cannot bounce', () => {
  for (const sp of [BLOB_BODY_SPRING, BLOB_SLOSH_SPRING]) {
    assert.ok(sp.damping * sp.damping >= 4 * sp.mass * sp.stiffness, JSON.stringify(sp));
  }
});

test('lag swirls the blob: the outline rotates, direction follows the lag sign', () => {
  const ang = (d) => {
    const v = numbers(d);
    return Math.atan2(v[3] - 88, v[2] - 170);
  };
  const r0 = ang(blobPath(1, 1, 1));
  assert.notEqual(ang(blobPath(1, 0.7, 0.6)), r0);
  assert.ok(ang(blobPath(1, 0.7, 0.6)) * 1 !== ang(blobPath(1, 1.3, 1.4)));
});
