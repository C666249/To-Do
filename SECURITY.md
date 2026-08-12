# Security & Credentials

## Reporting a vulnerability

Please use the repository's **Security → Report a vulnerability** flow so sensitive details are not posted in a public issue.

## API keys

This public repository intentionally contains **no private API key**.

If you enable the optional AI assistant, use your own credential and do not commit it to GitHub. Public client-side / WebView code cannot safely hide a shared secret.

If a credential has ever been committed or shared publicly, revoke or rotate it at the provider immediately.

## Local data

Core task, note, and Daily data are designed to remain local to the Android app. Note images are copied to the app-private files directory.

Uninstalling the app can remove app-private data. When upgrading an existing installation, keep the same Android application ID and signing certificate and install the new APK over the old version rather than uninstalling first.
