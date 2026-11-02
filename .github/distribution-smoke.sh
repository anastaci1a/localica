#!/usr/bin/env bash
# Smoke-test the shipped R8 distribution APK on the connected emulator.
# Usage: distribution-smoke.sh app-debug.apk logcat.txt
set -uo pipefail
apk=${1:?APK path required}
log=${2:?logcat output path required}
pkg=io.github.anastaci1a.localica.debug
activity=$pkg/io.github.anastaci1a.localica.MainActivity
seed=20261102
events=600
monkey_out="${log%.txt}-monkey.txt"
failures=()

fail() { failures+=("$1"); echo "FAIL: $1" >&2; }

adb install -r -t "$apk" || { echo 'APK install failed' >&2; exit 1; }
adb logcat -c
adb logcat -v threadtime > "$log" &
logcat_pid=$!
trap 'kill "$logcat_pid" 2>/dev/null || true' EXIT

adb shell am start -W -n "$activity" || fail 'main activity did not start'

resumed=false
for _ in $(seq 1 60); do
  if adb shell dumpsys activity activities | grep -E 'ResumedActivity' | grep -q "$pkg/"; then
    resumed=true
    break
  fi
  sleep 2
done
$resumed || fail 'main activity never reached resumed state'

adb shell monkey -p "$pkg" --throttle 100 -s "$seed" "$events" > "$monkey_out" 2>&1
monkey_status=$?
cat "$monkey_out"
[ "$monkey_status" -eq 0 ] || fail "monkey exited with status $monkey_status"
grep -E '^// (CRASH|NOT RESPONDING)|Monkey aborted' "$monkey_out" && fail 'monkey reported a crash or ANR'

sleep 3
pids=$(adb shell pidof "$pkg" | tr -d '\r')
[ -n "$pids" ] || fail 'app process is gone at the end'

kill "$logcat_pid" 2>/dev/null || true
wait "$logcat_pid" 2>/dev/null || true
trap - EXIT

if grep -E "FATAL EXCEPTION" -A3 "$log" | grep -q "Process: $pkg,"; then
  fail 'FATAL EXCEPTION in the app process'
fi
if grep -E "ANR in $pkg" "$log"; then
  fail 'ANR reported for the app'
fi
# Linkage errors for our package: the line names the package, or comes from an app pid.
app_pids=$(grep -E "Start proc [0-9]+:$pkg/" "$log" | sed -E 's/.*Start proc ([0-9]+):.*/\1/' | sort -u | paste -sd'|' -)
errors='ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError'
if grep -E "$errors" "$log" | grep -E "io\.github\.anastaci1a\.localica${app_pids:+|^[0-9-]+ [0-9:.]+ +($app_pids) }" ; then
  fail 'class linkage error in the app'
fi

if [ "${#failures[@]}" -gt 0 ]; then
  printf 'Distribution smoke failed:\n- %s\n' "${failures[@]}" >&2
  exit 1
fi
echo "Distribution smoke passed: $events monkey events, process alive ($pids)"
