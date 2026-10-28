#!/usr/bin/env bash
set -euo pipefail
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell getprop > profile-evidence/device-properties.txt
adb shell wm size > profile-evidence/display.txt
adb shell wm density >> profile-evidence/display.txt
sleep 20
index=0
for variant in baseline candidate candidate baseline; do
  index=$((index+1))
  prefix="profile-evidence/${index}-${variant}"
  adb install -r -t "/tmp/nav-$variant/app/build/outputs/apk/debug/app-debug.apk"
  adb install -r -t "/tmp/nav-$variant/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  adb shell pm clear io.github.anastaci1a.localica.debug
  adb logcat -c
  adb shell am instrument -w -r -e class io.github.anastaci1a.localica.NavigationProfileTest io.github.anastaci1a.localica.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$prefix-instrumentation.txt"
  adb logcat -d -v threadtime > "$prefix-logcat.txt"
  adb shell dumpsys meminfo io.github.anastaci1a.localica.debug > "$prefix-meminfo.txt"
  grep -q 'OK (4 tests)' "$prefix-instrumentation.txt"
  adb shell am force-stop io.github.anastaci1a.localica.debug
  sleep 10
done
