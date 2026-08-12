# Contributing

Thanks for helping improve To-Do.

## Before opening a pull request

1. Open an issue first for large behavior or architecture changes.
2. Keep changes focused and avoid unrelated formatting or refactors.
3. Keep `ui/todo.html` and `android/app/src/main/assets/todo.html` identical.
4. Never commit API keys, signing files, `local.properties`, or personal screenshots/data.
5. Run:

```bash
node --test tests/public-repo-safety.test.mjs
./gradlew :app:assembleDebug
```

For visible changes, include an Android device or emulator screenshot in the pull request.
