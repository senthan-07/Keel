#!/usr/bin/env bash
# Fails if the built APK requests INTERNET or any other network permission.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
MODULE="${1:-app}"
APK="$MODULE/build/outputs/apk/debug/$MODULE-debug.apk"
PERMS=$("$SDK/build-tools/36.0.0/aapt2" dump permissions "$APK")
echo "$PERMS"
if echo "$PERMS" | grep -Eq 'android.permission.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE)'; then
  echo "FAIL: network permission found"; exit 1
fi
echo "PASS: no network permission"
