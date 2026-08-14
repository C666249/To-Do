@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
set "PACKAGE=com.todolist.app"

echo ============================================================
echo To-Do v1.22.6 in-place update safety check
echo Package: %PACKAGE%
echo ============================================================

set "SDK_DIR="
for /f "tokens=1,* delims==" %%A in (local.properties) do (
  if /I "%%A"=="sdk.dir" set "SDK_DIR=%%B"
)
if not defined SDK_DIR (
  if defined ANDROID_SDK_ROOT set "SDK_DIR=%ANDROID_SDK_ROOT%"
)
if not defined SDK_DIR (
  if defined ANDROID_HOME set "SDK_DIR=%ANDROID_HOME%"
)
if not defined SDK_DIR (
  echo [ERROR] Android SDK path not found. Check local.properties / ANDROID_SDK_ROOT.
  exit /b 2
)
set "SDK_DIR=!SDK_DIR:/=\!"
set "ADB=!SDK_DIR!\platform-tools\adb.exe"
if not exist "!ADB!" (
  echo [ERROR] adb.exe not found at !ADB!
  exit /b 3
)

set "APKSIGNER="
for /f "delims=" %%F in ('dir /b /s "!SDK_DIR!\build-tools\*\apksigner.bat" 2^>nul') do set "APKSIGNER=%%F"
if not defined APKSIGNER (
  echo [ERROR] apksigner.bat not found under Android SDK build-tools.
  exit /b 4
)

set "NEW_APK=%CD%\dist\To-Do-v1.22.6.apk"
if not exist "!NEW_APK!" (
  echo [ERROR] Signed release APK not found at !NEW_APK!
  echo         Build, align, and sign the v1.22.6 release APK first.
  exit /b 5
)

echo [1/4] Checking connected device...
"!ADB!" get-state >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No adb device connected/authorized.
  exit /b 6
)

echo [2/4] Locating installed stable To-Do APK...
set "REMOTE_APK="
for /f "tokens=2 delims=:" %%P in ('"!ADB!" shell pm path %PACKAGE% 2^>nul ^| findstr /B /C:"package:"') do (
  if not defined REMOTE_APK set "REMOTE_APK=%%P"
)
if not defined REMOTE_APK (
  echo [ERROR] %PACKAGE% is not installed on the connected phone.
  exit /b 7
)
set "REMOTE_APK=!REMOTE_APK: =!"
set "OLD_APK=%TEMP%\todo-installed-current.apk"
"!ADB!" pull "!REMOTE_APK!" "!OLD_APK!" >nul
if errorlevel 1 (
  echo [ERROR] Failed to pull installed APK.
  exit /b 8
)

echo [3/4] Reading signing certificates...
set "OLD_CERT="
set "NEW_CERT="
for /f "tokens=2,* delims=:" %%A in ('"!APKSIGNER!" verify --print-certs "!OLD_APK!" 2^>nul ^| findstr /I /C:"certificate SHA-256 digest"') do if not defined OLD_CERT set "OLD_CERT=%%B"
for /f "tokens=2,* delims=:" %%A in ('"!APKSIGNER!" verify --print-certs "!NEW_APK!" 2^>nul ^| findstr /I /C:"certificate SHA-256 digest"') do if not defined NEW_CERT set "NEW_CERT=%%B"
set "OLD_CERT=!OLD_CERT: =!"
set "NEW_CERT=!NEW_CERT: =!"
if not defined OLD_CERT (
  echo [ERROR] Could not read certificate from installed APK.
  exit /b 9
)
if not defined NEW_CERT (
  echo [ERROR] Could not read certificate from new APK.
  exit /b 10
)

echo Installed cert: !OLD_CERT!
echo New APK cert : !NEW_CERT!
echo.
echo [4/4] Result
if /I "!OLD_CERT!"=="!NEW_CERT!" (
  echo [PASS] Package name and signing certificate are compatible for an in-place update.
  echo        Do NOT uninstall the existing To-Do app. Install the new APK over it.
  echo        Android should preserve the existing app sandbox/local data during the update.
  exit /b 0
) else (
  echo [BLOCK] Signing certificate mismatch.
  echo         Android will NOT allow this APK to overwrite the installed To-Do app.
  echo         DO NOT uninstall the stable app if its local records/notes are important.
  echo         Rebuild/sign v1.22.6 with the SAME key that signed the currently installed app.
  exit /b 20
)
