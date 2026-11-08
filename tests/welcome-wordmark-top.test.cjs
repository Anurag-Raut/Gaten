/**
 * The Welcome wordmark and the Home heading must clear the status bar by the same distance: Home's
 * header column pads by [spacing.m] and its brand row adds an 11 dp margin above the mark's line
 * box, and the Welcome screen repeats that sum above its own copy of the mark
 * ([WORDMARK_TOP_OFFSET]). Nothing else ties the two screens together, so a change to Home's
 * header that forgets the Welcome screen — or the reverse — would silently put the same mark at two
 * different heights under the same status bar.
 *
 * Both sides are read from the sources rather than restated here, so the guard tracks the code
 * instead of freezing today's numbers.
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');

/** The spacing scale, straight from `tokens.ts`. */
function spacingScale() {
  const block = /export const spacing = \{([\s\S]*?)\} as const;/.exec(read('src/rn/ui/tokens.ts'));
  assert.ok(block, 'the spacing scale parses');
  const scale = {};
  for (const [, key, value] of block[1].matchAll(/(\w+):\s*(\d+)/g)) {
    scale[key] = Number(value);
  }
  return scale;
}

/** A sum of dp numbers and `spacing.<key>` terms, e.g. `spacing.m + 11`. */
function dpSum(expression, scale) {
  const terms = expression.split('+').map((term) => term.trim());
  let total = 0;
  for (const term of terms) {
    const token = /^spacing\.(\w+)$/.exec(term);
    if (token) {
      assert.ok(token[1] in scale, `spacing.${token[1]} is a token`);
      total += scale[token[1]];
      continue;
    }
    assert.ok(/^\d+(\.\d+)?$/.test(term), `${term} is a dp number or a spacing token`);
    total += Number(term);
  }
  return total;
}

/** The dp Home puts between the safe-area top and the mark's line box. */
function homeWordmarkTop(scale) {
  const header = /headerColumn:\s*\{([^}]*)\}/.exec(read('src/rn/ui/HomeScreen.tsx'));
  const brand = /brandRow:\s*\{([^}]*)\}/.exec(read('src/rn/ui/HomeScreen.tsx'));
  assert.ok(header && brand, "Home's header styles parse");
  const columnPad = /paddingTop:\s*([^,}]+)/.exec(header[1]);
  const rowMargin = /marginTop:\s*([^,}]+)/.exec(brand[1]);
  assert.ok(columnPad && rowMargin, "Home's header top spacing parses");
  return dpSum(columnPad[1], scale) + dpSum(rowMargin[1], scale);
}

/** The dp the Welcome screen puts above its own copy of the mark. */
function welcomeWordmarkTop(scale) {
  const offset = /const WORDMARK_TOP_OFFSET = ([^;]+);/.exec(
    read('src/rn/ui/onboarding/WelcomeScreen.tsx'),
  );
  assert.ok(offset, 'the Welcome wordmark offset parses');
  return dpSum(offset[1], scale);
}

test('the Welcome wordmark clears the status bar as far as Home’s heading does', () => {
  const scale = spacingScale();
  const home = homeWordmarkTop(scale);
  const welcome = welcomeWordmarkTop(scale);
  assert.ok(home > 0, 'Home gives the mark a top offset');
  assert.equal(welcome, home);
});
