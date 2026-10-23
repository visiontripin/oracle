#!/usr/bin/env bash
# Build debug APK locally. Requires: JDK 17 + Android SDK (API 34, build-tools).
#   export ANDROID_HOME=$HOME/Android/Sdk
#   ./scripts/build_apk.sh
set -euo pipefail
cd "$(dirname "$0")/../android"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ERROR: ANDROID_HOME is not set. Install Android SDK cmdline-tools first."
  echo "See: https://developer.android.com/studio#command-line-tools-only"
  exit 1
fi

if [ -f gradlew ]; then
  ./gradlew :app:assembleDebug
else
  gradle wrapper --gradle-version 8.7
  ./gradlew :app:assembleDebug
fi

echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"
