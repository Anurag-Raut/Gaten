/**
 * The Anki cards page's pending state is a skeleton of the loaded layout, not a lone spinner: it
 * names every section the loaded page has, holds back for a short delay so fast reads never flash a
 * placeholder, and keeps one busy live-region label for screen readers.
 *
 * Run: `node --test tests/anki-settings-skeleton.test.cjs`
 */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const read = (p) => fs.readFileSync(path.join(__dirname, "..", p), "utf8");
const skeleton = read("src/rn/ui/settings/AnkiSettingsSkeleton.tsx");
const screen = read("src/rn/ui/settings/AnkiSettingsScreen.tsx");

test("skeleton shows the loaded page's section captions", () => {
  for (const title of ["Anki", "Study decks", "Export", "Card layout"]) {
    assert.match(skeleton, new RegExp(`<SettingsGroup title="${title}"`));
    assert.match(screen, new RegExp(`<SettingsGroup title="${title}"`));
  }
});

test("skeleton waits 200-300 ms before drawing anything", () => {
  const ms = Number(/SKELETON_DELAY_MS = (\d+)/.exec(skeleton)?.[1]);
  assert.ok(ms >= 200 && ms <= 300, `delay ${ms}`);
  assert.match(skeleton, /\{shown && \(/);
});

test("skeleton keeps the busy, polite 'Reading Anki settings' label", () => {
  assert.match(skeleton, /accessibilityLabel="Reading Anki settings"/);
  assert.match(skeleton, /accessibilityRole="progressbar"/);
  assert.match(skeleton, /accessibilityLiveRegion="polite"/);
  assert.match(skeleton, /busy: true/);
});

test("the pending branch no longer renders a lone LoadingLine", () => {
  assert.doesNotMatch(screen, /LoadingLine text="Reading Anki settings"/);
  assert.match(screen, /<AnkiSettingsSkeleton \/>/);
});
