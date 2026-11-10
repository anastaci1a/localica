#!/usr/bin/env bash
# Mandatory prototype setup and cold-process witness; never a frame timing measurement.
set -euo pipefail
serial=${1:?emulator serial}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
app=io.github.anastaci1a.localica.debug
runner=$app.test/io.github.anastaci1a.localica.NavigationRandomOwnedSession
out=prototype-evidence/main-thread-cold-start
[[ ! -e "$out" ]] || { echo 'Refusing stale cold-start evidence' >&2; exit 2; }
mkdir -p "$out"
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk > "$out/install-app.txt"
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > "$out/install-test.txt"
adb -s "$serial" shell am instrument -w -r -e class io.github.anastaci1a.localica.MainThreadGuardSeedTest "$runner" > "$out/seed.txt"
grep -Eq '^OK \(1 test\)' "$out/seed.txt"
! grep -Eq 'FAILURES|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$out/seed.txt"
adb -s "$serial" shell am force-stop "$app"
[[ -z $(adb -s "$serial" shell pidof "$app" | tr -d '\r') ]]
adb -s "$serial" logcat -c
capture() {
  adb -s "$serial" logcat -d -v threadtime > "$out/logcat.txt" 2>&1 || true
  adb -s "$serial" logcat -b crash -d -v threadtime > "$out/crashes.txt" 2>&1 || true
  adb -s "$serial" shell dumpsys meminfo "$app" > "$out/meminfo.txt" 2>&1 || true
  adb -s "$serial" shell dumpsys activity activities > "$out/activities.txt" 2>&1 || true
}
trap capture EXIT
adb -s "$serial" shell am start -W -n "$app/io.github.anastaci1a.localica.MainActivity" > "$out/launch.txt"
grep -q 'Status: ok' "$out/launch.txt"
pid=$(adb -s "$serial" shell pidof "$app" | tr -d '\r')
[[ "$pid" =~ ^[0-9]+$ ]]
ready=false
for attempt in $(seq 1 60); do
  [[ $(adb -s "$serial" shell pidof "$app" | tr -d '\r') == "$pid" ]] || {
    echo 'Cold app process died under fatal Main policy' >&2; exit 1;
  }
  # Delete both copies first: neither a failed dump nor a failed read may reuse a prior window.
  rm -f "$out/window.xml"
  adb -s "$serial" shell rm -f /sdcard/localica-threading-guard.xml
  if adb -s "$serial" shell uiautomator dump /sdcard/localica-threading-guard.xml > "$out/dump-status.txt" 2>&1 &&
      grep -Eq 'UI hier(archy|chary) dumped to:' "$out/dump-status.txt" &&
      adb -s "$serial" shell cat /sdcard/localica-threading-guard.xml > "$out/window.xml" 2> "$out/window-error.txt" &&
      grep -q 'Guard album' "$out/window.xml"; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'Cold seeded gallery never became visible' >&2; exit 1; }
# Observe a post-readiness interval: death can be deferred until the end of a Looper message.
sleep 2
[[ $(adb -s "$serial" shell pidof "$app" | tr -d '\r') == "$pid" ]]
printf 'pid=%s\ntracks=13400\nstrictmode=fatal\ncold-gallery-visible=true\n' "$pid" > "$out/receipt.txt"
capture
trap - EXIT
adb -s "$serial" shell am force-stop "$app"
