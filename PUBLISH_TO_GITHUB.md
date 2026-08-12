# Publish To-Do to GitHub with GitHub Desktop

This ZIP is already arranged as a public repository source tree.

## 1. Extract

Extract the ZIP to a permanent folder, for example:

```text
D:\Projects\To-Do
```

Do not upload the ZIP itself as the repository contents.

## 2. Add to GitHub Desktop

Open GitHub Desktop:

```text
File → Add local repository
```

Choose the extracted `To-Do` folder.

If GitHub Desktop says it is not a Git repository, choose **Create a repository here**.

## 3. First commit

Summary:

```text
Initial open source release
```

Then click:

```text
Commit to main
```

## 4. Publish

Click **Publish repository**.

Suggested repository name:

```text
To-Do
```

Suggested description:

```text
A local-first Android To-Do, Note & Daily reminder app with gesture-driven overlay reminders.
```

Make sure **Keep this code private** is NOT selected.

## 5. Add repository topics

Suggested topics:

```text
android
todo
productivity
notes
daily
daily-tasks
reminder
kotlin
webview
task-manager
open-source
```

## 6. Add screenshots

Follow `docs/SCREENSHOTS.md`, commit the image files, then enable the screenshot block in `README.md`.

## 7. Create the first Release

After building your APK, open the repository on GitHub:

```text
Releases → Draft a new release
```

Suggested tag:

```text
v1.19.1
```

Suggested title:

```text
To-Do v1.19.1
```

Paste the content of `RELEASE_NOTES_v1.19.1.md`, then attach the APK file.

## Important security check

Before publishing, never add these files manually:

```text
local.properties
*.jks
*.keystore
.env
secrets.properties
```

The included `.gitignore` already excludes them.
