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

test("walkthrough steps define 4 anchored steps with valid icons and non-empty copy", () => {
  const mod = loadTsModule("src/rn/ui/walkthrough/walkthrough-steps.ts");
  assert.equal(mod.WALKTHROUGH_STEPS.length, 4);
  const ids = mod.WALKTHROUGH_STEPS.map((s) => s.id);
  assert.deepEqual(ids, ["lookup", "search", "study", "tabs"]);
  for (const step of mod.WALKTHROUGH_STEPS) {
    assert.ok(step.title.length > 0);
    assert.ok(step.body.length > 0);
    assert.ok(step.tip.length > 0);
  }
  assert.equal(mod.SAMPLE_LOOKUP_QUERY, "猫");
});

test("walkthrough navigation helpers clamp boundaries and report progress labels", () => {
  const mod = loadTsModule("src/rn/ui/walkthrough/walkthrough-steps.ts");

  assert.equal(mod.clampStepIndex(-5), 0);
  assert.equal(mod.clampStepIndex(99), 3);
  assert.equal(mod.nextWalkthroughStep(0), 1);
  assert.equal(mod.nextWalkthroughStep(2), 3);
  assert.equal(mod.nextWalkthroughStep(3), null);
  assert.equal(mod.prevWalkthroughStep(0), 0);
  assert.equal(mod.prevWalkthroughStep(2), 1);
  assert.equal(mod.walkthroughProgressLabel(0), "1 of 4");
  assert.equal(mod.walkthroughProgressLabel(3), "4 of 4");
});

test("walkthrough replay store delivers signals, tracks active state, and consumes pending replay once", () => {
  const store = loadTsModule("src/rn/ui/walkthrough/walkthrough-store.ts", {
    react: {useEffect: () => {}, useState: (v) => [v, () => {}]},
  });

  assert.equal(store.consumePendingWalkthroughReplay(), false);
  assert.equal(store.isWalkthroughActive(), false);
  store.setWalkthroughActive(true);
  assert.equal(store.isWalkthroughActive(), true);
  store.setWalkthroughActive(false);
  assert.equal(store.isWalkthroughActive(), false);

  let notified = 0;
  const unsub = store.subscribeWalkthroughReplay(() => {
    notified += 1;
  });
  store.requestWalkthroughReplay();
  assert.equal(notified, 1);
  assert.equal(store.consumePendingWalkthroughReplay(), true);
  assert.equal(store.consumePendingWalkthroughReplay(), false);
  unsub();
});

test("GatenAppInfo walkthrough persistence delegates to native module and defaults safely", async () => {
  let saved = false;
  const nativeMod = loadTsModule("src/rn/native/GatenAppInfo.ts", {
    "react-native": {
      NativeModules: {
        GatenAppInfo: {
          isWalkthroughComplete: async () => saved,
          setWalkthroughComplete: async (val) => {
            saved = val;
          },
        },
      },
    },
  });
  assert.equal(await nativeMod.isWalkthroughComplete(), false);
  await nativeMod.setWalkthroughComplete(true);
  assert.equal(await nativeMod.isWalkthroughComplete(), true);

  const fallbackMod = loadTsModule("src/rn/native/GatenAppInfo.ts", {
    "react-native": {NativeModules: {}},
  });
  assert.equal(await fallbackMod.isWalkthroughComplete(), true);
  await fallbackMod.setWalkthroughComplete(true);
});

test("AppShell, HomeScreen, and SettingsScreen wire HomeWalkthrough and replay entry", () => {
  const shellSrc = fs.readFileSync(
    path.join(__dirname, "..", "src/rn/ui/AppShell.tsx"),
    "utf8",
  );
  assert.match(shellSrc, /HomeWalkthrough/);
  assert.match(shellSrc, /isWalkthroughComplete/);

  const homeSrc = fs.readFileSync(
    path.join(__dirname, "..", "src/rn/ui/HomeScreen.tsx"),
    "utf8",
  );
  assert.match(homeSrc, /!walkthroughActive/);

  const settingsSrc = fs.readFileSync(
    path.join(__dirname, "..", "src/rn/ui/settings/SettingsScreen.tsx"),
    "utf8",
  );
  assert.match(settingsSrc, /title="How to use Gaten"/);
});
