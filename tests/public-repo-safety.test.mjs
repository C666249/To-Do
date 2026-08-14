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

test('Android package uses the v1.22.6 release version', () => {
  const gradle = readProjectFile('android/app/build.gradle.kts');
  assert.match(gradle, /versionCode = 39\b/);
  assert.match(gradle, /versionName = "1\.22\.6"/);
});

test('Android manifest exposes the Note file workflow', () => {
  const manifest = readProjectFile('android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /\.ImportReceiverActivity/);
  assert.match(manifest, /android\.intent\.action\.SEND_MULTIPLE/);
  assert.match(manifest, /\.NoteFileViewerActivity/);
  assert.match(manifest, /androidx\.core\.content\.FileProvider/);
});

test('app-only mutation receiver is not exported', () => {
  const activity = readProjectFile('android/app/src/main/java/com/todolist/app/MainActivity.kt');
  assert.match(activity, /ContextCompat\.registerReceiver\(/);
  assert.match(activity, /ContextCompat\.RECEIVER_NOT_EXPORTED/);
});

test('release-facing files consistently name v1.22.6', () => {
  assert.match(readProjectFile('build-apk.bat'), /To-Do-v1\.22\.6\.apk/);
  assert.match(readProjectFile('RELEASE_NOTES_v1.22.6.md'), /^# To-Do v1\.22\.6$/m);
  assert.match(readProjectFile('README.md'), /Version-1\.22\.6-/);
});
