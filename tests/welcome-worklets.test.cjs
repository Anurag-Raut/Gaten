/**
 * A static guard for the mistake that a headless stub render cannot catch: a function called from
 * inside `useAnimatedStyle` / `useAnimatedProps` / `useDerivedValue` runs on the UI runtime, so it must itself be a
 * worklet. A closure returned from a hook is not one, and calling it there throws
 * "[Worklets] Tried to synchronously call a Remote Function" on the device.
 *
 * The scan starts at every animated callback in `WelcomeScreen.tsx`, collects the identifiers it
 * calls, and follows each one into the module that defines it — a function is accepted only when
 * its body opens with the `"worklet"` directive, and functions it calls are checked the same way.
 * An identifier that is neither a plain built-in nor a declared worklet fails the test, which is
 * what catches a hook-returned closure called by name.
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const SOURCES = [
  'src/rn/ui/onboarding/WelcomeScreen.tsx',
  'src/rn/ui/onboarding/welcome-timeline.ts',
  'src/rn/ui/onboarding/welcome-g-mark.ts',
];
const source = Object.fromEntries(
  SOURCES.map((file) => [file, fs.readFileSync(path.join(root, file), 'utf8')]),
);

/** Everything a worklet may call without being one itself, plus the keywords that take parens. */
const BUILTINS = new Set([
  'Math',
  'Object',
  'Array',
  'Number',
  'String',
  'Boolean',
  'JSON',
  'Date',
  'isNaN',
  'parseInt',
  'parseFloat',
  'setTimeout',
  'clearTimeout',
  'console',
  'for',
  'if',
  'while',
  'switch',
  'catch',
  'function',
  'typeof',
  'new',
  'return',
  'await',
  'delete',
  'void',
]);

/** The text between the parens that open at [open] (which points at the "("). */
function insideParens(text, open) {
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === '(') depth += 1;
    else if (text[i] === ')') {
      depth -= 1;
      if (depth === 0) return text.slice(open + 1, i);
    }
  }
  throw new Error('unbalanced parens in the source under test');
}

/** Every animated callback's own text, in order. */
function animatedCallbacks(text) {
  const found = [];
  const pattern = /use(?:AnimatedStyle|AnimatedProps|DerivedValue)\(/g;
  let match = pattern.exec(text);
  while (match != null) {
    found.push(insideParens(text, match.index + match[0].length - 1));
    match = pattern.exec(text);
  }
  return found;
}

/** The identifiers called in [text]: a property access is not a call. */
function calledNames(text) {
  const names = new Set();
  const pattern = /(?<![.\w$])([A-Za-z_$][\w$]*)\s*\(/g;
  let match = pattern.exec(text);
  while (match != null) {
    names.add(match[1]);
    match = pattern.exec(text);
  }
  return [...names];
}

/** The body of `function [name](` in [text] (braces balanced), or null when it is not defined there. */
function functionBody(text, name) {
  const declaration = new RegExp(`(?:export\\s+)?function\\s+${name}\\s*\\(`);
  const match = declaration.exec(text);
  if (match == null) return null;
  const open = text.indexOf('{', match.index + match[0].length);
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === '{') depth += 1;
    else if (text[i] === '}') {
      depth -= 1;
      if (depth === 0) return text.slice(open + 1, i);
    }
  }
  throw new Error(`unbalanced braces in the body of ${name}`);
}

const isWorklet = (body) => /^\s*["']worklet["']\s*;/.test(body);

/** Walks everything an animated callback calls, collecting the non-worklets it finds. */
function nonWorkletCallees(callbacks) {
  const offenders = [];
  const visited = new Set();
  const stack = callbacks.map((body) => ({body, owner: 'an animated callback'}));
  while (stack.length > 0) {
    const {body, owner} = stack.pop();
    for (const name of calledNames(body)) {
      if (BUILTINS.has(name) || visited.has(name)) continue;
      visited.add(name);
      const definition = SOURCES.map((file) => ({file, body: functionBody(source[file], name)})).find(
        (hit) => hit.body != null,
      );
      if (definition == null) {
        offenders.push(`${name} (called from ${owner}: not a declared function, so not a worklet)`);
        continue;
      }
      if (!isWorklet(definition.body)) {
        offenders.push(`${name} (${definition.file}: missing the "worklet" directive)`);
        continue;
      }
      stack.push({body: definition.body, owner: name});
    }
  }
  return {offenders, visited};
}

test('every function called from an animated style or prop is a worklet', () => {
  const callbacks = animatedCallbacks(source['src/rn/ui/onboarding/WelcomeScreen.tsx']);
  assert.ok(callbacks.length >= 10, `expected the screen's animated callbacks, found ${callbacks.length}`);

  const {offenders, visited} = nonWorkletCallees(callbacks);
  assert.deepEqual(offenders, [], `non-worklet calls on the UI runtime:\n${offenders.join('\n')}`);
  // The scan has to have reached the frame math, or it is proving nothing.
  for (const name of ['welcomeFrameFor', 'welcomeFrame', 'welcomeStaticFrame', 'seg', 'easedOut', 'easedInOut']) {
    assert.ok(visited.has(name), `${name} was never reached from an animated callback`);
  }
});

test('the frame selector is itself a worklet', () => {
  assert.ok(isWorklet(functionBody(source['src/rn/ui/onboarding/welcome-timeline.ts'], 'welcomeFrameFor')));
});
