# Architecture

To-Do is a hybrid Android application:

- **Web UI layer:** HTML / CSS / Vanilla JavaScript rendered in Android WebView.
- **Native Android layer:** Kotlin for reminders, overlay windows, alarms, notifications, photo picking, and file bridging.
- **Primary UI source:** `ui/todo.html`.
- **Packaged WebView asset mirror:** `android/app/src/main/assets/todo.html`.

## Data

The WebView layer is the source of truth for ordinary To-Do / Note / Daily UI data. Native stores only the additional state required for background reminders and private Note image files.

## Native bridge responsibilities

`MainActivity.kt` exposes Android capabilities to the WebView, including reminder scheduling and image selection. Background reminder logic lives under:

```text
android/app/src/main/java/com/todolist/app/reminder/
android/app/src/main/java/com/todolist/app/receiver/
android/app/src/main/java/com/todolist/app/service/
android/app/src/main/java/com/todolist/app/manager/
```

## Overlay reminders

The reminder host uses `WindowManager` overlays for Todo / Daily reminder banners, with `AlarmManager` and a foreground service used to improve background reliability.
