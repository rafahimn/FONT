@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle is not installed. Install Android Studio and use its Gradle sync, or install Gradle 8.9.
  pause
  exit /b 1
)
gradle --no-daemon assembleDebug
if errorlevel 1 exit /b 1
echo.
echo APK output: app\build\outputs\apk\debug\app-debug.apk
pause
