#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.9 is required. Install Android Studio/Gradle and Android SDK Platform 35 first." >&2
  exit 1
fi
gradle --no-daemon assembleDebug
echo "APK output: app/build/outputs/apk/debug/app-debug.apk"
