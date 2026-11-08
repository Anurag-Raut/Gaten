const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');

function loadTypeScript(relativePath) {
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const javascript = ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
  }).outputText;
  const module = {exports: {}};
  const run = new vm.Script(`(function (require, module, exports) { ${javascript}\n})`, {
    filename: relativePath,
  }).runInThisContext();
  run(() => ({}), module, module.exports);
  return module.exports;
}

const mark = loadTypeScript('src/rn/ui/onboarding/welcome-g-mark.ts');

test('the Welcome stage\'s G is the floating button\'s own drawable, unmodified', () => {
  const drawable = fs.readFileSync(
    path.join(root, 'app/src/main/res/drawable/ic_trigger_g.xml'),
    'utf8',
  );
  const pathData = /android:pathData="([^"]+)"/.exec(drawable);
  const width = /android:viewportWidth="([\d.]+)"/.exec(drawable);
  const height = /android:viewportHeight="([\d.]+)"/.exec(drawable);
  const translateX = /android:translateX="(-?[\d.]+)"/.exec(drawable);
  const translateY = /android:translateY="(-?[\d.]+)"/.exec(drawable);

  assert.ok(pathData && width && height && translateX && translateY, 'the drawable parses');
  assert.equal(mark.G_MARK_PATH, pathData[1]);
  // The drawable's group drops the artwork's 6-unit margin, so the viewBox is the ink box itself.
  assert.equal(
    mark.G_MARK_VIEWBOX,
    `${-Number(translateX[1])} ${-Number(translateY[1])} ${width[1]} ${height[1]}`,
  );
});
