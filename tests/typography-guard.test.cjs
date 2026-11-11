/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The typography guard: `typeScale` in `src/rn/ui/tokens.ts` is the only place a size or a weight
 * is written as a number. Every surface spreads a role from it — `{...typeScale.labelMedium}` — so
 * the same kind of text is drawn the same way everywhere, and changing a role changes every screen
 * that carries it. A numeric `fontSize:` or `fontWeight: "<n>"` written inside a component is drift:
 * it is the same kind of text drawn differently on one screen.
 *
 * This scans `src/rn/ui/**\/*.ts(x)` line by line and fails on either literal, naming the file and
 * line of every offender. The only files that may keep one are in [ALLOW] below, each with its
 * reason and the kinds of literal it covers; comment lines are skipped, so a doc comment may still
 * say "11 sp".
 *
 * Run: `node --test tests/typography-guard.test.cjs`
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const REPO_ROOT = path.join(__dirname, "..");
const UI_ROOT = path.join(REPO_ROOT, "src", "rn", "ui");

/**
 * What a line may not write. Only a numeric literal trips a rule; `fontSize: centreSize` does not.
 * `kind` is what an [ALLOW] entry licenses.
 */
const RULES = [
  {kind: "sizes", label: "fontSize literal", pattern: /fontSize\s*:\s*[0-9]/},
  {kind: "weights", label: "fontWeight literal", pattern: /fontWeight\s*:\s*"[0-9]+"/},
];

/**
 * Files that may still write a numeric size or weight, and why. Paths are relative to the repo root
 * and use forward slashes. An entry lists the kinds it licenses — `sizes` covers a numeric
 * `fontSize:`, `weights` a numeric `fontWeight: "<n>"`. A file with only `weights` may still keep
 * its emphasis weights but a numeric `fontSize:` written there fails like anywhere else.
 * Nothing else under `src/rn/ui` qualifies; a computed value such as `fontSize: centreSize` never
 * matches and needs no entry.
 */
const ALLOW = {
  "src/rn/ui/tokens.ts": {
    sizes: true,
    weights: true,
    reason: "the role definitions themselves — this is where the numbers live",
  },
  "src/rn/ui/onboarding/WelcomeScreen.tsx": {
    sizes: true,
    weights: true,
    reason: "marketing screen: type is scaled by fontScale and timed with the artwork",
  },
  "src/rn/ui/onboarding/OnboardingFlow.tsx": {
    sizes: true,
    weights: true,
    reason: "onboarding narrative: type is scaled by fontScale and timed with the artwork",
  },
  "src/rn/ui/AppShell.tsx": {
    weights: true,
    reason: "the navigation theme's `fonts` map — the library takes numeric weights, not roles",
  },
  "src/rn/ui/analytics/ink.tsx": {
    weights: true,
    reason:
      "weight-only emphasis laid over a role, and the 13/19/600 status line no single role carries",
  },
  "src/rn/ui/rtk/richText.tsx": {
    weights: true,
    reason: "an inline bold span inside story text — emphasis, not a text role",
  },
  "src/rn/ui/settings/settings-components.tsx": {
    weights: true,
    reason: "an inline emphasis span inside a body paragraph — emphasis, not a text role",
  },
};

/** A documentation line may name a size; only code is guarded. */
function isCommentLine(trimmed) {
  return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}

/** Every `.ts`/`.tsx` file under `src/rn/ui`, as repo-relative forward-slash paths. */
function uiSources(dir = UI_ROOT) {
  const found = [];
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...uiSources(full));
    } else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) {
      found.push(path.relative(REPO_ROOT, full).split(path.sep).join("/"));
    }
  }
  return found.sort();
}

/** Every guarded literal in one file: `{line, kind, label, text}`. */
function offendersIn(relativePath) {
  const lines = fs.readFileSync(path.join(REPO_ROOT, relativePath), "utf8").split(/\r?\n/);
  const offenders = [];
  lines.forEach((line, index) => {
    if (isCommentLine(line.trim())) {
      return;
    }
    for (const rule of RULES) {
      if (rule.pattern.test(line)) {
        offenders.push({line: index + 1, kind: rule.kind, label: rule.label, text: line.trim()});
      }
    }
  });
  return offenders;
}

test("no screen writes a numeric fontSize or fontWeight literal", () => {
  const offenders = [];
  for (const file of uiSources()) {
    const allowed = ALLOW[file];
    for (const offender of offendersIn(file)) {
      if (allowed != null && allowed[offender.kind] === true) {
        continue;
      }
      offenders.push(`${file}:${offender.line}: ${offender.label} — ${offender.text}`);
    }
  }

  assert.deepEqual(
    offenders,
    [],
    offenders.length === 0
      ? ""
      : [
          `${offenders.length} numeric typography literal(s) outside tokens.ts and the allowlist.`,
          "Spread the role instead — e.g. {...typeScale.labelMedium} — or add the file to ALLOW",
          "in this test with the reason and the kinds it cannot take from a role:",
          ...offenders,
        ].join("\n"),
  );
});

test("every ALLOW entry names a file that exists", () => {
  const missing = Object.keys(ALLOW).filter(
    (file) => !fs.existsSync(path.join(REPO_ROOT, file)),
  );
  assert.deepEqual(missing, [], `ALLOW names files that no longer exist: ${missing.join(", ")}`);
});
