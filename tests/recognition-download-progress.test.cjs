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
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
  }).outputText;
  const module = {exports: {}};
  const run = new vm.Script(`(function (require, module, exports) { ${javascript}\n})`, {
    filename: relativePath,
  }).runInThisContext();
  run(mockedRequire, module, module.exports);
  return module.exports;
}

/**
 * A fresh copy of GatenRecognition.ts over a mocked react-native. The module-level event emitter is
 * built at import, so each case gets its own: [native] is what NativeModules exposes (undefined
 * models the module being absent), and [Emitter] records construction and listeners.
 */
function loadRecognition({native, Emitter}) {
  return loadTypeScript('src/rn/native/GatenRecognition.ts', (name) => {
    if (name === 'react-native') {
      return {
        NativeModules: native === undefined ? {} : {GatenRecognition: native},
        NativeEventEmitter: Emitter,
      };
    }
    throw new Error(`Unexpected import: ${name}`);
  });
}

class RecordingEmitter {
  constructor(nativeModule) {
    this.nativeModule = nativeModule;
    this.events = [];
    this.subscriptions = [];
    RecordingEmitter.last = this;
  }

  addListener(eventType, listener) {
    const record = {eventType, listener, removed: false};
    this.events.push(record);
    this.subscriptions.push({
      remove: () => {
        record.removed = true;
      },
    });
    return this.subscriptions[this.subscriptions.length - 1];
  }
}

function errorWithCode(code) {
  const error = new Error('native rejection');
  error.code = code;
  return error;
}

test('classifyDownloadError maps every code the native side can send', () => {
  const recognition = loadRecognition({native: {}, Emitter: RecordingEmitter});
  assert.equal(recognition.classifyDownloadError(errorWithCode('DOWNLOAD_OFFLINE')), 'offline');
  assert.equal(recognition.classifyDownloadError(errorWithCode('DOWNLOAD_NO_SPACE')), 'storage');
  assert.equal(
    recognition.classifyDownloadError(errorWithCode('DOWNLOAD_VERIFY_FAILED')),
    'verify',
  );
  assert.equal(recognition.classifyDownloadError(errorWithCode('DOWNLOAD_CANCELLED')), 'cancelled');
});

test('classifyDownloadError answers unknown for anything else, never a guess', () => {
  const recognition = loadRecognition({native: {}, Emitter: RecordingEmitter});
  assert.equal(recognition.classifyDownloadError(errorWithCode('RECOGNITION_DOWNLOAD_FAILED')), 'unknown');
  assert.equal(recognition.classifyDownloadError(errorWithCode('DOWNLOAD_IN_PROGRESS')), 'unknown');
  assert.equal(recognition.classifyDownloadError(new Error('no code')), 'unknown');
  assert.equal(recognition.classifyDownloadError('a string'), 'unknown');
  assert.equal(recognition.classifyDownloadError(null), 'unknown');
  assert.equal(recognition.classifyDownloadError(undefined), 'unknown');
});

test('subscribeDownloadProgress forwards the event and unsubscribes', () => {
  const recognition = loadRecognition({native: {}, Emitter: RecordingEmitter});
  const seen = [];
  const unsubscribe = recognition.subscribeDownloadProgress((progress) => seen.push(progress));
  const emitter = RecordingEmitter.last;
  assert.equal(emitter.events[0].eventType, 'GatenRecognitionDownloadProgress');
  const payload = {engine: 'medium', phase: 'downloading', bytesDone: 32, bytesTotal: 64};
  emitter.events[0].listener(payload);
  assert.deepEqual(seen, [payload]);
  assert.equal(emitter.events[0].removed, false);
  unsubscribe();
  assert.equal(emitter.events[0].removed, true);
});

test('subscribeDownloadProgress is safe and silent when the native module is absent', () => {
  let constructed = 0;
  class ExplodingEmitter {
    constructor() {
      constructed += 1;
      throw new Error('the emitter must not be built without a module');
    }
  }
  const recognition = loadRecognition({native: undefined, Emitter: ExplodingEmitter});
  const seen = [];
  const unsubscribe = recognition.subscribeDownloadProgress((progress) => seen.push(progress));
  assert.equal(constructed, 0);
  assert.equal(typeof unsubscribe, 'function');
  assert.doesNotThrow(() => unsubscribe());
  assert.deepEqual(seen, []);
});

test('cancelDownload delegates to the native module', async () => {
  let calls = 0;
  const native = {
    cancelDownload: async () => {
      calls += 1;
    },
  };
  const recognition = loadRecognition({native, Emitter: RecordingEmitter});
  await recognition.cancelDownload();
  assert.equal(calls, 1);
});

test('cancelDownload rejects rather than throwing when the native module is absent', async () => {
  const recognition = loadRecognition({native: undefined, Emitter: RecordingEmitter});
  await assert.rejects(() => recognition.cancelDownload());
});

test('the existing exports are untouched', () => {
  const native = {
    getRecognitionSettings: async () => ({mode: 'ml_kit', engines: [], downloading: []}),
    setRecognitionMode: async () => {},
    downloadRecognitionEngine: async () => ({mode: 'medium', engines: [], downloading: []}),
  };
  const recognition = loadRecognition({native, Emitter: RecordingEmitter});
  assert.equal(typeof recognition.getRecognitionSettings, 'function');
  assert.equal(typeof recognition.setRecognitionMode, 'function');
  assert.equal(typeof recognition.downloadRecognitionEngine, 'function');
});
