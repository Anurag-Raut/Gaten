/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');
const COMPONENT = 'src/rn/ui/settings/settings-components.tsx';

function loadTypeScript(relativePath, mockedRequire = () => ({})) {
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const javascript = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
      jsx: ts.JsxEmit.React,
    },
  }).outputText;
  const module = {exports: {}};
  const run = new vm.Script(`(function (require, module, exports) { ${javascript}\n})`, {
    filename: relativePath,
  }).runInThisContext();
  run(mockedRequire, module, module.exports);
  return module.exports;
}

// A tiny stand-in for React and React Native: JSX becomes plain objects, and useState keeps its
// slots in a list that a test can re-render against, so the dialog's own logic runs unchanged.
let fontScale = 1;
const hookSlots = [];
let hookIndex = 0;
const fakeReact = {
  createElement: (type, props, ...children) => ({
    type,
    props: {...(props || {}), children: children.length === 1 ? children[0] : children},
  }),
  Fragment: 'Fragment',
  useState: (initial) => {
    const i = hookIndex++;
    if (!(i in hookSlots)) hookSlots[i] = initial;
    return [hookSlots[i], (next) => { hookSlots[i] = typeof next === 'function' ? next(hookSlots[i]) : next; }];
  },
  useRef: (initial) => ({current: initial}),
  useEffect: () => {},
  useLayoutEffect: () => {},
  useMemo: (fn) => fn(),
  useCallback: (fn) => fn,
};
const anyObject = new Proxy({}, {get: () => 8});
const mockModules = {
  react: fakeReact,
  'react-native': {
    Pressable: 'Pressable',
    ScrollView: 'ScrollView',
    Switch: 'Switch',
    Text: 'Text',
    TextInput: 'TextInput',
    View: 'View',
    useWindowDimensions: () => ({width: 360, height: 640, fontScale}),
  },
  'react-native-safe-area-context': {useSafeAreaInsets: () => ({top: 0, bottom: 0})},
  '../../native/GatenPlatform': {},
  '../components': {Button: 'Button', Icon: 'Icon', IconButton: 'IconButton', SectionLabel: 'SectionLabel'},
  '../DialogShell': {DialogShell: 'DialogShell'},
  '../Collapsible': {DisclosureChevron: 'DisclosureChevron'},
  '../PressableScale': {PressableScale: 'PressableScale'},
  '../../native/GatenHaptics': {},
  '../InkIcon': {InkIcon: 'InkIcon'},
  '../ScreenHeader': {ScreenHeader: 'ScreenHeader'},
  '../theme': {useTheme: () => ({colors: new Proxy({}, {get: () => '#000000'})})},
  '../tokens': {
    CONTENT_COLUMN: {},
    contentInsetFor: () => 0,
    shape: anyObject,
    spacing: new Proxy({}, {get: () => 8}),
    typeScale: new Proxy({}, {get: () => ({})}),
  },
  '../icons': {},
};
const component = loadTypeScript(COMPONENT, (name) => {
  if (name === 'react') return {...fakeReact, default: fakeReact};
  if (!(name in mockModules)) throw new Error(`unexpected import in the test: ${name}`);
  return mockModules[name];
});
const {
  DISCLOSURE_SUMMARY_LEAD,
  DISCLOSURE_SUMMARY_ROWS,
  disclosureLineText,
  LookupModeDisclosureDialog,
} = component;

/** Every element in a tree, depth first, including the dialog's action and footer slots. */
function elementsIn(node, found = []) {
  if (node == null || typeof node !== 'object') return found;
  if (Array.isArray(node)) {
    for (const child of node) elementsIn(child, found);
    return found;
  }
  if ('type' in node && 'props' in node) {
    found.push(node);
    elementsIn(node.props.children, found);
    elementsIn(node.props.actions, found);
    elementsIn(node.props.footer, found);
  }
  return found;
}

/** Renders the dialog once, as the component would on a fresh screen or after a state change. */
function renderDialog(onAccept = () => {}) {
  hookIndex = 0;
  return LookupModeDisclosureDialog({onAccept, onDismiss: () => {}});
}

const plainWords = (text) => text.split(/\s+/).filter(Boolean).length;

test('the component module loads with the summary exports', () => {
  assert.equal(typeof DISCLOSURE_SUMMARY_LEAD, 'string');
  assert.ok(DISCLOSURE_SUMMARY_LEAD.length > 0);
  assert.equal(DISCLOSURE_SUMMARY_ROWS.length, 3, 'the summary must have exactly three rows');
});

test('the rendered dialog shows the lead and exactly three check rows', () => {
  const dialog = renderDialog();
  const labelled = elementsIn(dialog).filter((el) => typeof el.props.accessibilityLabel === 'string');
  const rowLabels = labelled.map((el) => el.props.accessibilityLabel);
  assert.deepEqual(
    rowLabels,
    DISCLOSURE_SUMMARY_ROWS.map(disclosureLineText),
    'the rows must be the three summary rows, in order, and nothing else carries a row label',
  );
});

test('the summary is short: no more than 53 words', () => {
  const words = [DISCLOSURE_SUMMARY_LEAD, ...DISCLOSURE_SUMMARY_ROWS]
    .map(disclosureLineText)
    .map(plainWords)
    .reduce((sum, count) => sum + count, 0);
  assert.ok(words <= 53, `summary is ${words} words`);
});

test('the dialog has no details toggle and no detail copy', () => {
  const rendered = JSON.stringify(renderDialog());
  assert.ok(!/details/i.test(rendered), 'the dialog still mentions the removed details');
  assert.ok(!rendered.includes('OCR model'), 'detail copy is still rendered');
  for (const heading of ['Screen access', 'On this device', 'Optional connections', 'Next step']) {
    assert.ok(!rendered.includes(heading), `a detail heading is still rendered: ${heading}`);
  }
});

test('no screen-reader label carries a ** marker', () => {
  const labels = elementsIn(renderDialog())
    .map((el) => el.props.accessibilityLabel)
    .filter((label) => typeof label === 'string');
  assert.ok(labels.length > 0, 'the dialog has no labelled text to check');
  for (const label of labels) {
    assert.ok(!label.includes('*'), `a marker reached a label: ${JSON.stringify(label)}`);
  }
});

test('Open settings stays disabled until the checkbox is ticked, then fires onAccept', () => {
  hookSlots.length = 0;
  let accepted = 0;
  const openSettings = () =>
    elementsIn(renderDialog(() => { accepted += 1; })).find(
      (el) => el.props.label === 'Open settings',
    );
  const checkbox = () =>
    elementsIn(renderDialog()).find((el) => el.props.accessibilityRole === 'checkbox');

  assert.equal(openSettings().props.disabled, true, 'Open settings must start disabled');
  checkbox().props.onPress();
  assert.equal(checkbox().props.accessibilityState.checked, true);
  assert.equal(openSettings().props.disabled, false, 'ticking the checkbox must enable Open settings');
  openSettings().props.onPress();
  assert.equal(accepted, 1, 'Open settings must call onAccept once enabled');
});

test('the checkbox and both actions are always rendered, pinned outside the scrolling body', () => {
  const dialog = renderDialog();
  assert.ok(dialog.props.footer, 'the checkbox must be the pinned footer');
  assert.ok(dialog.props.actions, 'the actions must be pinned');
  assert.equal(dialog.props.scrollBody, true);
  assert.equal(dialog.props.fitToViewport, true, 'the card must fit the viewport so the actions stay reachable');
});
