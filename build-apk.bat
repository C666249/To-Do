@echo off
setlocal
cd /d "%~dp0"
echo [To-Do] Building debug APK from project root...
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
  echo.
  echo BUILD FAILED. See the Gradle error above.
  exit /b 1
)
if not exist "dist" mkdir "dist"
for %%F in ("android\app\build\outputs\apk\debug\*.apk") do (
  copy /Y "%%~fF" "dist\To-Do-v1.22.6.apk" >nul
  echo.
  echo APK: %CD%\dist\To-Do-v1.22.6.apk
  exit /b 0
)
echo Build finished but no APK was found under android\app\build\outputs\apk\debug
exit /b 2
