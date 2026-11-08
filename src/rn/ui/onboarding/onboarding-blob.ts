/**
 * The liquid blob behind the onboarding artwork. Each page has an organic amoeba (eighteen
 * anchors on a radius that mixes a two-fold and a three-fold swell) joined by a closed Catmull-Rom
 * spline; the three differ in rotation, lean and weight. The page clock (0, 1, 2) picks the pair of shapes and the
 * fraction between them. On top of that the shape reacts to how far the clock has run ahead of a
 * spring that follows it (`lag`): it stretches along the swipe and squeezes across it, the trailing
 * side more than the leading side, then wobbles back to rest as the spring settles.
 *
 * Everything is a worklet on the UI thread: no scratch arrays, one string per call. Keyframes are
 * built once at module level. At lag 0 the deformation is the identity.
 */

const CX = 170;
const CY = 88;
const ANCHORS = 18;

/** Anchors per blob. */
export const BLOB_ANCHORS = ANCHORS;

/** Per page: radius, swell phases (deg) for the 2-fold and 3-fold terms, x/y scale and centre offset. */
const SHAPES = [
  {R: 96.6, p2: 192.1, p3: 300.4, sx: 1.169, sy: 0.922, dx: -7.9, dy: -0.5},
  {R: 97.9, p2: 354.3, p3: 79.6, sx: 1.167, sy: 0.915, dx: 3.8, dy: -2.7},
  {R: 97.9, p2: 345.6, p3: 235.4, sx: 1.168, sy: 0.895, dx: -7.3, dy: -2.8},
];

/** Anchors as x0, y0, x1, y1 ... per page. */
export const BLOB_KEYS: number[][] = SHAPES.map((s) => {
  const out: number[] = [];
  for (let i = 0; i < BLOB_ANCHORS; i += 1) {
    const t = (i * 2 * Math.PI) / BLOB_ANCHORS;
    const r = s.R * (1 + 0.07 * Math.cos(2 * (t - (s.p2 * Math.PI) / 180)) + 0.06 * Math.cos(3 * (t - (s.p3 * Math.PI) / 180)));
    out.push(CX + s.dx + s.sx * r * Math.cos(t), CY + s.dy + s.sy * r * Math.sin(t));
  }
  return out;
});

/**
 * Two overdamped followers trail the page clock, so the blob never bounces: the gap each leaves
 * rises with the swipe's speed and drains away smoothly (damping ratio above 1, no overshoot). The
 * `body` follower settles in about a second, `slosh` is the long wake and takes about two: once the liquid is set moving it keeps drifting and unwinding well after the swipe has landed. Every anchor feels a blend of the two,
 * the trailing side leaning on the slow one, so the edge twists and smears like a viscous liquid:
 * the blob swirls round its centre by an angle that grows with the lag, more at the trailing side
 * than the leading one, then unwinds slowly.
 */
const REST = {restDisplacementThreshold: 0.0003, restSpeedThreshold: 0.002};
export const BLOB_BODY_SPRING = {mass: 1, stiffness: 40, damping: 13, ...REST};
export const BLOB_SLOSH_SPRING = {mass: 1, stiffness: 9, damping: 6.3, ...REST};

/** cos / sin of three times each anchor's angle, the swell's mode shape. */
const RIPPLE_COS: number[] = [];
const RIPPLE_SIN: number[] = [];
for (let i = 0; i < BLOB_ANCHORS; i += 1) {
  RIPPLE_COS.push(Math.cos((3 * i * 2 * Math.PI) / BLOB_ANCHORS));
  RIPPLE_SIN.push(Math.sin((3 * i * 2 * Math.PI) / BLOB_ANCHORS));
}

/** Page units of lag at which the deformation saturates. */
const LAG_FULL = 1;
/** Swirl (rad) at full lag, stretch along x, squeeze along y, uniform contraction, swell depth. */
const SWIRL = 0.34;
const STRETCH = 0.1;
const SQUEEZE = 0.06;
const CONTRACT = 0.09;
const RIPPLE = 0.03;

function blobClamp(v: number, lo: number, hi: number): number {
  'worklet';
  return v < lo ? lo : v > hi ? hi : v;
}

function blobRound(v: number): number {
  'worklet';
  return Math.round(v * 10) / 10;
}

/** Anchor [i] deformed (any integer; wraps): x when [wantY] is 0, y when 1. */
function blobCoord(a: number[], b: number[], f: number, i: number, body: number, slosh: number, dir: number, wantY: number): number {
  'worklet';
  const j = ((i % ANCHORS) + ANCHORS) % ANCHORS;
  const bx = a[2 * j] + (b[2 * j] - a[2 * j]) * f - 170;
  const by = a[2 * j + 1] + (b[2 * j + 1] - a[2 * j + 1]) * f - 88;
  // Trailing anchors lean on the slow follower, leading ones on the quick one.
  const w = 0.5 + 0.5 * blobClamp(bx / 100, -1, 1) * dir;
  const mb = blobClamp(body / LAG_FULL, -1, 1);
  const ms = blobClamp(slosh / LAG_FULL, -1, 1);
  const m = mb + (ms - mb) * w;
  const am = m < 0 ? -m : m;
  const swell = 1 + RIPPLE * (ms * RIPPLE_COS[j] + (mb - ms) * RIPPLE_SIN[j]);
  const k = swell * (1 - CONTRACT * am);
  const px = bx * k * (1 + STRETCH * am);
  const py = by * k * (1 - SQUEEZE * am);
  // Small-angle rotation about the centre: the swirl.
  const ang = SWIRL * m;
  const c = 1 - (ang * ang) / 2;
  const sn = ang - (ang * ang * ang) / 6;
  // Whatever the lag, an anchor never leaves the stage, so the edge is never clipped.
  return wantY === 0 ? blobClamp(170 + px * c - py * sn, 1, 339) : blobClamp(88 + px * sn + py * c, 1, 175);
}

/** One Bezier segment from anchor [i] to i + 1 (Catmull-Rom, tension 1/6). */
function blobSegment(a: number[], b: number[], f: number, i: number, body: number, slosh: number, dir: number): string {
  'worklet';
  const x0 = blobCoord(a, b, f, i - 1, body, slosh, dir, 0);
  const y0 = blobCoord(a, b, f, i - 1, body, slosh, dir, 1);
  const x1 = blobCoord(a, b, f, i, body, slosh, dir, 0);
  const y1 = blobCoord(a, b, f, i, body, slosh, dir, 1);
  const x2 = blobCoord(a, b, f, i + 1, body, slosh, dir, 0);
  const y2 = blobCoord(a, b, f, i + 1, body, slosh, dir, 1);
  const x3 = blobCoord(a, b, f, i + 2, body, slosh, dir, 0);
  const y3 = blobCoord(a, b, f, i + 2, body, slosh, dir, 1);
  return (
    'C' + blobRound(x1 + (x2 - x0) / 6) + ' ' + blobRound(y1 + (y2 - y0) / 6) +
    ' ' + blobRound(x2 - (x3 - x1) / 6) + ' ' + blobRound(y2 - (y3 - y1) / 6) +
    ' ' + blobRound(x2) + ' ' + blobRound(y2)
  );
}

/**
 * The outline at page clock [scene] (0 Lookup, 1 Anki, 2 WaniKani; clamped) while the body and
 * slosh springs sit at [bodyFollow] and [sloshFollow]. Pass all three equal for the resting shape.
 */
export function blobPath(scene: number, bodyFollow: number, sloshFollow: number): string {
  'worklet';
  const t = blobClamp(scene, 0, 2);
  const k = t < 1 ? 0 : 1;
  const f = t - k;
  const a = BLOB_KEYS[k];
  const b = BLOB_KEYS[k + 1];
  const body = blobClamp(scene - bodyFollow, -1, 1);
  const slosh = blobClamp(scene - sloshFollow, -1, 1);
  const dir = blobClamp(slosh * 20, -1, 1);
  let d = 'M' + blobRound(blobCoord(a, b, f, 0, body, slosh, dir, 0)) + ' ' + blobRound(blobCoord(a, b, f, 0, body, slosh, dir, 1));
  for (let i = 0; i < ANCHORS; i += 1) d += blobSegment(a, b, f, i, body, slosh, dir);
  return d + 'Z';
}
