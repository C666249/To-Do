# Privacy

To-Do is designed as a local-first Android application.

## Data stored on the device

- To-Do, Note, and Daily content is stored locally by the app.
- Note images selected through the Android system picker are copied into the app's private files directory.
- Reminder state is stored locally so alarms and reminder banners can work in the background.
- Android backup may include app data when device backup is enabled, subject to the device and Android account settings.

## Network access

Core task, note, Daily, and reminder features do not require an account or a project-operated server.

The app can access the network in these cases:

- The optional AI assistant sends the text you submit to the AI provider you select. You provide and control that provider credential.
- The WebView loads the open-source `marked` library from jsDelivr for Markdown rendering.

Review the privacy terms of any third-party AI provider before enabling the AI assistant. Do not enter confidential content if you do not want it sent to that provider.

## Android permissions

- Notifications: display reminder notifications.
- Display over other apps: show gesture-driven reminder banners.
- Exact alarms, wake lock, foreground service, and boot completed: schedule and restore reminders reliably.
- Internet: load the Markdown library and support the optional AI assistant.

The app uses the Android system photo picker rather than requesting broad access to the entire photo library.

## Deleting data

Delete content from within the app where supported. Uninstalling the app removes its private local data, subject to Android backup and restore behavior.
