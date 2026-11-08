/**
 * The floating bottom bar and every bottom-padded screen lay out from `useSafeAreaInsets().bottom`.
 * On Android 15's edge-to-edge gesture navigation the library reads a zero navigation-bar inset, so
 * the bar would sit flush on the screen edge (and does not when the gesture handle is shown on some
 * OEM builds). `SystemInsetsProvider` raises the bottom inset to the platform's system-gesture
 * inset, floored at a small minimum, and only the bottom edge is touched. These tests hold the pure
 * merge rule and the wiring that keeps it in the tree.
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');

function loadTypeScript(relativePath) {
  const source = read(relativePath);
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

const {MIN_BOTTOM_INSET, mergeBottomInset} = loadTypeScript('src/rn/ui/systemInsets.ts');

test('the minimum matches the spacing scale\'s s step with room to read as floating', () => {
  const block = /export const spacing = \{([\s\S]*?)\} as const;/.exec(read('src/rn/ui/tokens.ts'));
  assert.ok(block, 'the spacing scale parses');
  const small = /\bs:\s*(\d+)/.exec(block[1]);
  assert.ok(small, 'the spacing scale has an s step');
  assert.equal(MIN_BOTTOM_INSET, Number(small[1]));
});

test('a device with no bottom chrome still keeps the minimum gap', () => {
  assert.equal(mergeBottomInset(0, 0), MIN_BOTTOM_INSET);
});

test('the system-gesture inset wins over a zero navigation-bar inset', () => {
  // The moto g35 measured: navigationBars bottom = 0, mandatorySystemGestures bottom = 32 dp.
  assert.equal(mergeBottomInset(0, 32), 32);
});

test('a real navigation-bar inset is never shrunk by the merge', () => {
  // A three-button device reports 48 dp of navigation bar and no gesture strip.
  assert.equal(mergeBottomInset(48, 0), 48);
  // Whichever is larger wins, wherever they land.
  assert.equal(mergeBottomInset(16, 24), 24);
  assert.equal(mergeBottomInset(24, 16), 24);
});

test('the provider is inside the safe-area provider, above the shell', () => {
  const app = read('App.js');
  const safeArea = app.indexOf('<SafeAreaProvider>');
  const provider = app.indexOf('<SystemInsetsProvider>');
  const shell = app.indexOf('<AppShell />');
  assert.ok(safeArea !== -1 && provider !== -1 && shell !== -1, 'the root renders all three');
  assert.ok(safeArea < provider && provider < shell, 'the provider wraps the shell under the safe area');
});

test('the native shell exposes the system-gesture insets', () => {
  const native = read('app/src/main/java/com/gaten/app/rn/GatenShellModule.kt');
  assert.match(native, /fun getSystemGestureInsets\(promise: Promise\)/);
  assert.match(native, /WindowInsets\.Type\.systemGestures\(\)/);
  assert.match(native, /WindowInsets\.Type\.mandatorySystemGestures\(\)/);
  const bridge = read('src/rn/native/GatenShell.ts');
  assert.match(bridge, /export function getSystemGestureInsets\(\)/);
});

test('only the bottom edge is merged', () => {
  const provider = read('src/rn/ui/SystemInsetsProvider.tsx');
  // The merged object spreads the safe-area insets and overrides only `bottom`.
  assert.match(provider, /\{\.\.\.insets, bottom: mergeBottomInset\(insets\.bottom, gestureBottom\)\}/);
});
