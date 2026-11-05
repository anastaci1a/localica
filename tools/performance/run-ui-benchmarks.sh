#!/usr/bin/env bash
# Requires an owned disposable emulator with the matching debug and test APKs installed.
set -euo pipefail
umask 0002
serial=${1:?Usage: run-ui-benchmarks.sh SERIAL OUTPUT_DIRECTORY SOURCE_SHA}
out=${2:?Output directory required}
source_sha=${3:?Full source SHA required}
[[ $source_sha =~ ^[a-f0-9]{40}$ ]] || { echo 'Expected a full source SHA' >&2; exit 1; }
[[ ! -e $out ]] || { echo 'Use a fresh evidence directory' >&2; exit 1; }
mkdir -p "$out"
app=io.github.anastaci1a.localica.debug
runner=$app.test/io.github.anastaci1a.localica.NavigationRandomOwnedSession
remote=/sdcard/Android/data/$app/files/ui600
adb -s "$serial" shell getprop > "$out/device-properties.txt"
adb -s "$serial" shell dumpsys display > "$out/display.txt"
adb -s "$serial" shell wm size > "$out/size.txt"
adb -s "$serial" shell wm density > "$out/density.txt"
run() {
    local selection=$1 reps=$2 log=$3
    adb -s "$serial" shell am instrument -w -r \
        -e class io.github.anastaci1a.localica.UiPerformanceTest \
        -e uiPerformance true -e uiPerformanceSource "$source_sha" \
        -e uiPerformanceScenario "$selection" -e uiPerformanceRepetitions "$reps" \
        -e additionalTestOutputDir "$remote" "$runner" > "$log" 2>&1
    if ! grep -q 'OK (1 test)' "$log" || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$log"; then
        cat "$log" >&2; return 1
    fi
}
# Exact owned output subdirectory; no library data is removed.
adb -s "$serial" shell rm -rf "$remote"
run '.*' 3 "$out/instrumentation.txt"
adb -s "$serial" pull "$remote/ui-performance/." "$out/"
node "$(dirname "$0")/summarize-ui.mjs" "$out"
mkdir "$out/traces"
while IFS= read -r scenario; do
    # Trace passes are separate and never substituted into baseline measurements.
    adb -s "$serial" shell atrace --async_start -a "$app" -b 32768 gfx view sched freq am wm > "$out/traces/$scenario-start.txt"
    if ! run "$scenario" 1 "$out/traces/$scenario-instrumentation.txt"; then
        adb -s "$serial" shell atrace --async_stop > "$out/traces/$scenario-failed.trace"
        exit 1
    fi
    adb -s "$serial" exec-out atrace --async_stop -z > "$out/traces/$scenario.ctrace"
done < "$out/top-scenarios.txt"
