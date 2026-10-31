#!/usr/bin/env bash
# Launch the production-configured distribution APK on an emulator and fail on crashes.
set -euo pipefail
pkg=io.github.anastaci1a.localica.debug
apk=app/build/outputs/apk/distribution/app-distribution.apk
adb logcat -c
adb install -r "$apk"
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n "$pkg/io.github.anastaci1a.localica.MainActivity"
sleep 20
adb shell monkey -p "$pkg" --throttle 300 --pct-syskeys 0 --pct-appswitch 0 -s 7 400 || true
sleep 5
adb logcat -d -v threadtime > distribution-smoke-logcat.txt
pid=$(adb shell pidof "$pkg" | tr -d '\r' || true)
echo "pid=$pid"
if grep -aE "FATAL EXCEPTION|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|ClassNotFoundException|AbstractMethodError" distribution-smoke-logcat.txt; then
  echo 'Distribution smoke found a crash or missing class' >&2
  exit 1
fi
[[ -n "$pid" ]] || { echo 'Distribution process is not running' >&2; exit 1; }
echo 'Distribution smoke passed'
