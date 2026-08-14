# Changelog

## v1.22.6

### Note file workspace

- Added multi-file attachment selection through Android's system file picker.
- Added share-back import from WeChat, QQ, and other Android apps through a dedicated, no-history import receiver.
- Stores imported files under the app-private `files/note_files/` directory and inserts reusable file cards into Notes.
- Added recent-import reuse, direct preview, targeted sharing, external-app opening, and attachment cleanup.

### Built-in preview

- Added in-app preview for Markdown/text/code/JSON/XML/CSV, PDF, images, common audio/video, and lightweight DOCX/XLSX content.
- Added external-first handling for APK, presentation, archive, executable, and professional formats.
- Changed PDF viewing to a vertically continuous, lazy-rendered list with bitmap recycling.
- Added 1×–5× pinch zoom and pan for PDF pages and full-screen Note images.

### Note editor stability

- Restored a single body scroll owner and minimized caret reveal to prevent long-note jump-to-top regressions.
- Kept the formatting toolbar above the Android soft keyboard using native IME geometry.
- Made bold, strikethrough, and highlight explicit future-input states instead of inheriting caret formatting.
- Prevented inline-image taps from reopening the keyboard before full-screen preview while preserving vertical Note scrolling.

### Release safety

- Bumped Android version to `versionCode 39` / `versionName 1.22.6` without changing `applicationId`.
- Removed embedded developer credentials from public source; optional AI features require the user's own key.

## v1.19.1

- Updated the launcher and notification artwork.
- Prepared the first public-source release with screenshots, a real-device gesture demo, and release documentation.
- Removed embedded developer credentials; optional AI features now require the user's own API key.

## v1.18

### Note image support
- Added a system photo picker entry to the Note toolbar.
- Supports selecting multiple images and inserting them at the current editor position.
- Images are copied into the app-private `files/note_images/` directory rather than stored as Base64 in localStorage.
- Added image preview and per-image deletion.
- Fixed the Note bold button so normal text is the default and bold is enabled only after tapping `B`.

### Reminder polish
- Unified native overlay exit behavior to opacity-only fade-out before detaching the view.
- Reduced final-frame flicker for Todo / Daily reminder interactions and snooze feedback.
- WebView toast exit animation is also opacity-only.

## v1.17
- Added four-direction Todo / Daily reminder Banner gestures.
- Updated notification icons.
- Reduced Daily drawer entrance flicker.

## v1.15
- Added right-swipe `+5 min / +10 min` snooze behavior.

## v1.14
- Added contextual Feature Coach tutorials.

## v1.13
- Added first-run onboarding and visual tutorial flow.

## v1.10
- Added Daily recurring tasks and completion history.

- 修复图标外缘发白问题，统一替换为透明底无白边新图标。
