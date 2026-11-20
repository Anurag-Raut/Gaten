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

test('GatenPlatform.openPlayStoreListing delegates when present and falls back to openUrl otherwise', async () => {
  const calls = [];
  const withNative = loadTypeScript('src/rn/native/GatenPlatform.ts', (id) => {
    if (id === 'react-native') {
      return {
        NativeModules: {
          GatenPlatform: {
            openPlayStoreListing: async (pkg, fallback) => {
              calls.push(['listing', pkg, fallback]);
              return true;
            },
            openUrl: async (url) => {
              calls.push(['url', url]);
              return true;
            },
          },
        },
      };
    }
    return {};
  });
  assert.equal(await withNative.openPlayStoreListing('com.gaten', 'https://example.test'), true);
  assert.deepEqual(calls, [['listing', 'com.gaten', 'https://example.test']]);

  calls.length = 0;
  const withoutNative = loadTypeScript('src/rn/native/GatenPlatform.ts', (id) => {
    if (id === 'react-native') {
      return {
        NativeModules: {
          GatenPlatform: {
            openUrl: async (url) => {
              calls.push(['url', url]);
              return true;
            },
          },
        },
      };
    }
    return {};
  });
  assert.equal(await withoutNative.openPlayStoreListing('com.gaten', 'https://example.test'), true);
  assert.deepEqual(calls, [['url', 'https://example.test']]);
});

test('GatenPlatform.requestInAppReview delegates when present and resolves false when absent', async () => {
  let invoked = false;
  const withNative = loadTypeScript('src/rn/native/GatenPlatform.ts', (id) => {
    if (id === 'react-native') {
      return {
        NativeModules: {
          GatenPlatform: {
            requestInAppReview: async () => {
              invoked = true;
              return true;
            },
          },
        },
      };
    }
    return {};
  });
  assert.equal(await withNative.requestInAppReview(), true);
  assert.equal(invoked, true);

  const withoutNative = loadTypeScript('src/rn/native/GatenPlatform.ts', (id) => {
    if (id === 'react-native') {
      return {NativeModules: {GatenPlatform: {}}};
    }
    return {};
  });
  assert.equal(await withoutNative.requestInAppReview(), false);
});

const supportLinks = loadTypeScript('src/rn/ui/support/support-links.ts', () => ({
  openUrl: async () => true,
  openPlayStoreListing: async () => true,
  requestInAppReview: async () => true,
  toast: async () => true,
}));

test('openPlayStoreReview opens com.gaten Play listing and toasts only on failure', async () => {
  const calls = [];
  const ok = await supportLinks.openPlayStoreReview({
    openPlayStoreListing: async (pkg, fallback) => {
      calls.push(['listing', pkg, fallback]);
      return true;
    },
    requestInAppReview: async () => false,
    toast: async (msg) => {
      calls.push(['toast', msg]);
      return true;
    },
  });
  assert.equal(ok, true);
  assert.deepEqual(calls, [['listing', supportLinks.PLAY_STORE_PACKAGE, supportLinks.REVIEW_URL]]);

  calls.length = 0;
  const failed = await supportLinks.openPlayStoreReview({
    openPlayStoreListing: async () => false,
    requestInAppReview: async () => false,
    toast: async (msg) => {
      calls.push(['toast', msg]);
      return true;
    },
  });
  assert.equal(failed, false);
  assert.deepEqual(calls, [['toast', supportLinks.OPEN_FAILED]]);
});

test('requestInAppOrStoreReview uses Play In-App Review first and falls back to store listing', async () => {
  const calls = [];
  const inAppOk = await supportLinks.requestInAppOrStoreReview({
    requestInAppReview: async () => {
      calls.push('inApp');
      return true;
    },
    openPlayStoreListing: async () => {
      calls.push('listing');
      return true;
    },
    toast: async () => {
      calls.push('toast');
      return true;
    },
  });
  assert.equal(inAppOk, true);
  assert.deepEqual(calls, ['inApp']);

  calls.length = 0;
  const fallbackOk = await supportLinks.requestInAppOrStoreReview({
    requestInAppReview: async () => {
      calls.push('inApp');
      return false;
    },
    openPlayStoreListing: async (pkg, url) => {
      calls.push(['listing', pkg, url]);
      return true;
    },
    toast: async () => {
      calls.push('toast');
      return true;
    },
  });
  assert.equal(fallbackOk, true);
  assert.deepEqual(calls, ['inApp', ['listing', 'com.gaten', supportLinks.REVIEW_URL]]);

  calls.length = 0;
  const bothFailed = await supportLinks.requestInAppOrStoreReview({
    requestInAppReview: async () => {
      throw new Error('play unavailable');
    },
    openPlayStoreListing: async () => false,
    toast: async (msg) => {
      calls.push(['toast', msg]);
      return true;
    },
  });
  assert.equal(bothFailed, false);
  assert.deepEqual(calls, [['toast', supportLinks.OPEN_FAILED]]);
});

test('SettingsHub exposes a dedicated Rate Gaten row under App', () => {
  const settingsSource = fs.readFileSync(
    path.join(root, 'src/rn/ui/settings/SettingsScreen.tsx'),
    'utf8',
  );
  assert.match(settingsSource, /title="Rate Gaten"/);
  assert.match(settingsSource, /icon="star"/);
  assert.match(settingsSource, /openPlayStoreReview/);
});
