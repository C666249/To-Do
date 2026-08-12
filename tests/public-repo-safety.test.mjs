import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const publicUiMirrors = [
  new URL('../ui/todo.html', import.meta.url),
  new URL('../android/app/src/main/assets/todo.html', import.meta.url),
];

const readProjectFile = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');

test('public UI sources do not contain embedded API credentials', () => {
  for (const source of publicUiMirrors) {
    const contents = readFileSync(source, 'utf8');
    assert.doesNotMatch(contents, /sk-[A-Za-z0-9_-]{20,}/, source.pathname);
  }
});

test('Android package uses the v1.19.1 release version', () => {
  const gradle = readProjectFile('android/app/build.gradle.kts');
  assert.match(gradle, /versionCode = 31\b/);
  assert.match(gradle, /versionName = "1\.19\.1"/);
});

test('release-facing files consistently name v1.19.1', () => {
  assert.match(readProjectFile('build-apk.bat'), /To-Do-v1\.19\.1\.apk/);
  assert.match(readProjectFile('RELEASE_NOTES_v1.19.1.md'), /^# To-Do v1\.19\.1$/m);
  assert.match(readProjectFile('README.md'), /Version-1\.19\.1-/);
});
