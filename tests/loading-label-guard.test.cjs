/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The loading-label guard: a loading state is a spinner beside plain words ("Updating", "Checking",
 * "Saving"), never a label that ends in an ellipsis. The app font sets U+2026 a third of an em above
 * the baseline, so the dots float mid-height. This fails on any capitalised string literal in
 * `src/rn/ui` that ends in U+2026, `…` or "...", and on any user-visible Android string that
 * ends in U+2026. Comments are skipped, and so are placeholders that start lowercase or with kana.
 *
 * Run: `node --test tests/loading-label-guard.test.cjs`
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

const rel = (file) => path.relative(ROOT, file).split(path.sep).join("/");
const LITERAL = /(["'`])[A-Z][^"'`\n]*?(?:…|\\u2026|\.\.\.)\1/;
const JSX_TEXT = /^\s*[A-Z][^<>{}"]*(?:…|\\u2026|\.\.\.)\s*$/;

test("no loading label in the React Native UI ends in an ellipsis", () => {
  const offenders = [];
  for (const file of walk(path.join(ROOT, "src", "rn", "ui"))) {
    if (!/\.tsx?$/.test(file)) continue;
    fs.readFileSync(file, "utf8").split("\n").forEach((line, index) => {
      const code = line.trim();
      if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return;
      if (LITERAL.test(line) || JSX_TEXT.test(line)) offenders.push(`${rel(file)}:${index + 1}: ${code}`);
    });
  }
  assert.deepEqual(offenders, []);
});

test("no Android loading string ends in an ellipsis", () => {
  const offenders = [];
  const dir = path.join(ROOT, "app", "src", "main", "res", "values");
  for (const name of fs.readdirSync(dir).filter((n) => n.startsWith("strings") && n.endsWith(".xml"))) {
    fs.readFileSync(path.join(dir, name), "utf8").split("\n").forEach((line, index) => {
      if (/<string [^>]*>[A-Z][^<]*(…|\.\.\.)<\/string>/.test(line)) offenders.push(`${name}:${index + 1}: ${line.trim()}`);
    });
  }
  assert.deepEqual(offenders, []);
});
