# Note Files and Built-in Preview

To-Do v1.22.6 extends Note from text-and-image editing into a lightweight local file workspace.

## Importing files

- **Phone files:** Android's system file picker supports multi-select.
- **WeChat / QQ:** To-Do opens the chosen chat app; share the file back to the Android target named **“添加到 To-Do”**.
- **Other apps:** Android `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, and compatible `content://` view intents are accepted by a dedicated import activity.
- **Recent:** files already imported into To-Do can be cloned into another Note without tying both cards to the same deletable file.

Android does not allow To-Do to enumerate another app's private chat database. The WeChat / QQ flow therefore requires the user to select and share the file; To-Do does not read chat history.

Imported payloads are copied into the app-private `files/note_files/` directory. A Note stores only the card metadata and internal filename, not the complete file as Base64.

## Preview support

| Format | Behavior |
| --- | --- |
| Markdown, text, source code, JSON, XML, CSV | Local, JavaScript-disabled document view |
| PDF | Vertical continuous pages, lazy rendering, bitmap recycling, 1×–5× pinch zoom and pan |
| Images | Built-in viewer with zoom and pan |
| Common audio / video | Built-in playback with external fallback |
| DOCX | Best-effort headings, paragraphs, basic run formatting, tables, and embedded images |
| XLSX | Best-effort shared strings and worksheet cell tables |
| APK/APKS/XAPK/AAB, PPT/PPTX, archives, executables, CAD/design and other professional formats | Open with an external app |

DOCX and XLSX support is a quick preview, not an Office-compatible layout engine. Complex pagination, charts, macros, conditional formatting, floating objects, and other professional features may differ. The viewer always provides **“其他应用”** so the same file can be checked in WPS, Office, Adobe, a media player, or another installed app.

## Editor behavior

- The Note body remains the single text scroll owner.
- Native Android IME geometry keeps the formatting toolbar above the keyboard.
- Caret reveal runs only when needed and scrolls the minimum distance.
- Bold, strikethrough, and highlight are explicit future-input switches; moving the caret next to formatted text does not silently re-enable them.
- Inline image taps suppress contenteditable focus before opening the full-screen preview, while vertical gestures still scroll the Note.

## Privacy and sharing

To-Do does not request broad shared-storage access for this feature. System pickers grant access only to files the user selects. External viewers and share targets receive temporary, read-only `content://` access through Android `FileProvider`.
