# Changelog

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
