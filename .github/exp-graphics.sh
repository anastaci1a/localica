#!/usr/bin/env bash
# Temporary #544 experiment: repeat graphics-heavy classes, count transport aborts.
set -uo pipefail
classes=io.github.anastaci1a.localica.MotionSubpixelTest,io.github.anastaci1a.localica.PlayerExpansionMotionTest,io.github.anastaci1a.localica.PlayerGestureTest,io.github.anastaci1a.localica.PinchReleaseHandoffTest,io.github.anastaci1a.localica.PinchRetainedHighlightTest,io.github.anastaci1a.localica.RetainedLayerReadbackTest
rounds=${ROUNDS:-5}
mkdir -p exp-out
adb shell settings put global hide_error_dialogs 1 || true
adb shell getprop ro.kernel.qemu.gles; adb shell getprop ro.hardware.egl; adb shell getprop ro.opengles.version
for i in $(seq 1 "$rounds"); do
  adb logcat -b crash -c; adb logcat -c
  start=$(date +%s)
  ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$classes" > exp-out/gradle-$i.log 2>&1
  rc=$?
  adb logcat -b crash -d -v threadtime > exp-out/crash-$i.txt 2>&1
  adb logcat -d -v threadtime | grep -aE "QemuPipeStream|rcReadColorBuffer|gralloc_lock|Bad address|eglSwapBuffers|EGL_BAD" | head -20 > exp-out/gfx-$i.txt
  t=$(( $(date +%s) - start ))
  abort=$(grep -acE "QemuPipeStream|Fatal signal" exp-out/crash-$i.txt)
  crashed=$(grep -ac "Process crashed" exp-out/gradle-$i.log)
  failed=$(grep -ac "FAILED" exp-out/gradle-$i.log)
  echo "ROUND $i rc=$rc seconds=$t transport_or_native_abort_lines=$abort process_crashed=$crashed failed_lines=$failed"
  echo "ROUND $i rc=$rc seconds=$t aborts=$abort process_crashed=$crashed failed=$failed" >> exp-out/summary.txt
done
exit 0
