const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function loadTsModule(relPath, mocks = {}) {
  const fullPath = path.join(__dirname, "..", relPath);
  const source = fs.readFileSync(fullPath, "utf8");
  const {outputText} = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
    },
    fileName: fullPath,
  });
  const mod = {exports: {}};
  const sandboxRequire = (spec) => {
    if (Object.prototype.hasOwnProperty.call(mocks, spec)) {
      return mocks[spec];
    }
    throw new Error(`Unexpected require in test: ${spec}`);
  };
  const fn = vm.runInThisContext(
    `(function(require, module, exports) {\n${outputText}\n})`,
  );
  fn(sandboxRequire, mod, mod.exports);
  return mod.exports;
}

const SAMPLE_INFO = {
  appVersion: "0.1.0",
  versionCode: 12,
  androidRelease: "14",
  androidSdk: 34,
  manufacturer: "Google",
  model: "Pixel 7",
};

test("GatenAppInfo.getDiagnosticsInfo delegates when native method exists and falls back safely", async () => {
  const nativeMod = loadTsModule("src/rn/native/GatenAppInfo.ts", {
    "react-native": {
      NativeModules: {
        GatenAppInfo: {
          getAppVersion: async () => "0.1.0",
          getDiagnosticsInfo: async () => SAMPLE_INFO,
        },
      },
    },
  });
  assert.deepEqual(await nativeMod.getDiagnosticsInfo(), SAMPLE_INFO);

  const fallbackMod = loadTsModule("src/rn/native/GatenAppInfo.ts", {
    "react-native": {
      NativeModules: {
        GatenAppInfo: {
          getAppVersion: async () => "0.2.0",
        },
      },
    },
  });
  assert.deepEqual(await fallbackMod.getDiagnosticsInfo(), {
    appVersion: "0.2.0",
    versionCode: 0,
    androidRelease: "",
    androidSdk: 0,
    manufacturer: "",
    model: "",
  });
});

test("formatDiagnostics and diagnosticsSummaryLine render clean non-PII summaries", () => {
  const mod = loadTsModule("src/rn/ui/support/feedback-links.ts", {
    "../../native/GatenPlatform": {},
  });

  assert.equal(
    mod.formatDiagnostics(SAMPLE_INFO),
    "App: Gaten 0.1.0 (12)\nAndroid: 14 (API 34)\nDevice: Google Pixel 7",
  );
  assert.equal(mod.diagnosticsSummaryLine(SAMPLE_INFO), "Gaten 0.1.0 · Android 14 · Pixel 7");
  assert.equal(mod.formatDiagnostics(null), "App: Gaten");
  assert.equal(mod.diagnosticsSummaryLine(null), "Gaten");
});

test("buildFeedbackUrl builds pre-filled GitHub URLs for bug reports and feature requests", () => {
  const mod = loadTsModule("src/rn/ui/support/feedback-links.ts", {
    "../../native/GatenPlatform": {},
  });

  const bugUrl = mod.buildFeedbackUrl("bug", SAMPLE_INFO);
  assert.ok(bugUrl.startsWith(`${mod.GITHUB_NEW_ISSUE_URL}?labels=bug&`));
  assert.ok(decodeURIComponent(bugUrl).includes("App: Gaten 0.1.0 (12)"));
  assert.ok(decodeURIComponent(bugUrl).includes("Android: 14 (API 34)"));
  assert.ok(decodeURIComponent(bugUrl).includes("Device: Google Pixel 7"));

  const featureUrl = mod.buildFeedbackUrl("feature", SAMPLE_INFO);
  assert.ok(featureUrl.startsWith(`${mod.GITHUB_NEW_ISSUE_URL}?labels=enhancement&`));
  assert.ok(decodeURIComponent(featureUrl).includes("[Feature] "));
});

test("openFeedbackDestination opens issue URL and toasts only when opening fails", async () => {
  const mod = loadTsModule("src/rn/ui/support/feedback-links.ts", {
    "../../native/GatenPlatform": {},
  });

  const openedUrls = [];
  const toasts = [];
  const ok = await mod.openFeedbackDestination("bug", SAMPLE_INFO, {
    openUrl: async (url) => {
      openedUrls.push(url);
      return true;
    },
    toast: async (msg) => {
      toasts.push(msg);
      return true;
    },
  });
  assert.equal(ok, true);
  assert.equal(openedUrls.length, 1);
  assert.equal(toasts.length, 0);

  const failed = await mod.openFeedbackDestination("feature", SAMPLE_INFO, {
    openUrl: async () => false,
    toast: async (msg) => {
      toasts.push(msg);
      return true;
    },
  });
  assert.equal(failed, false);
  assert.deepEqual(toasts, [mod.FEEDBACK_OPEN_FAILED]);
});

test("SettingsScreen exposes Send feedback row and FeedbackSheet", () => {
  const src = fs.readFileSync(
    path.join(__dirname, "..", "src/rn/ui/settings/SettingsScreen.tsx"),
    "utf8",
  );
  assert.match(src, /title="Send feedback"/);
  assert.match(src, /FeedbackSheet/);
});
