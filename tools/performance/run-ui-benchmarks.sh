#!/usr/bin/env bash
# Requires an owned disposable emulator with the matching debug and test APKs installed.
set -euo pipefail
umask 0002
serial=${1:?Usage: run-ui-benchmarks.sh SERIAL OUTPUT_DIRECTORY SOURCE_SHA}
out=${2:?Output directory required}
source_sha=${3:?Full source SHA required}
[[ $source_sha =~ ^[a-f0-9]{40}$ ]] || { echo 'Expected a full source SHA' >&2; exit 1; }
[[ ! -e $out ]] || { echo 'Use a fresh evidence directory' >&2; exit 1; }
node "$(dirname "$0")/freeze-scenarios.mjs" check
mkdir -p "$out"
cp "$(dirname "$0")/scenarios.json" "$out/script-freeze.json"
app=io.github.anastaci1a.localica.debug
runner=$app.test/io.github.anastaci1a.localica.NavigationRandomOwnedSession
remote=/sdcard/Android/data/$app/files/ui600
for package in "$app" "$app.test"; do
    apk_path=$(adb -s "$serial" shell pm path "$package" | head -1 | cut -d: -f2 | tr -d '\r')
    [[ -n $apk_path ]] || { echo "Missing installed $package" >&2; exit 1; }
    adb -s "$serial" shell sha256sum "$apk_path" > "$out/$package-apk.sha256"
done
adb -s "$serial" shell getprop > "$out/device-properties.txt"
adb -s "$serial" shell dumpsys display > "$out/display.txt"
adb -s "$serial" shell wm size > "$out/size.txt"
adb -s "$serial" shell wm density > "$out/density.txt"
run() {
    local selection=$1 reps=$2 log=$3
    local selector=()
    if [[ $selection != '.*' ]]; then
        [[ $selection =~ ^[a-z0-9-]+$ ]] || return 1
        selector=(-e uiPerformanceScenario "$selection")
    fi
    adb -s "$serial" shell am instrument -w -r \
        -e class io.github.anastaci1a.localica.UiPerformanceTest \
        -e uiPerformance true -e uiPerformanceSource "$source_sha" \
        "${selector[@]}" -e uiPerformanceRepetitions "$reps" \
        -e additionalTestOutputDir "$remote" "$runner" > "$log" 2>&1
    if ! grep -q 'OK (1 test)' "$log" || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$log"; then
        cat "$log" >&2; return 1
    fi
}
# Exact owned output subdirectory; no library data is removed.
adb -s "$serial" shell rm -rf "$remote"
run '.*' 3 "$out/instrumentation.txt"
adb -s "$serial" pull "$remote/ui-performance/." "$out/"
node "$(dirname "$0")/none-contract.mjs" "$out" "$source_sha"
node "$(dirname "$0")/summarize-ui.mjs" "$out"
mkdir "$out/traces"
mapfile -t top_scenarios < "$out/top-scenarios.txt"
for scenario in "${top_scenarios[@]}"; do
    # Trace passes are separate and never substituted into baseline measurements.
    api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
    if (( api >= 31 )); then
        trace=/data/misc/perfetto-traces/ui600-$scenario.perfetto-trace
        pid=$(adb -s "$serial" shell perfetto --background-wait --txt -c - -o "$trace" < "$(dirname "$0")/frametimeline.pbtxt" | tr -d '\r')
        [[ $pid =~ ^[0-9]+$ ]] || { echo 'Perfetto did not return a PID' >&2; exit 1; }
        run "$scenario" 1 "$out/traces/$scenario-instrumentation.txt"
        # The traced service owns the file: consumer PID exit does not acknowledge
        # its final write. Register CLOSE_WRITE before stopping the consumer.
        adb -s "$serial" shell sh -s -- "$pid" "$trace" <<'CLOSE_TRACE'
set -eu
pid=$1 trace=$2
ack=/data/local/tmp/ui600-close-$pid
: > "$ack"
inotifyd - "$trace:w" > "$ack" &
watcher=$!
trap 'kill "$watcher" 2>/dev/null || :; rm -f "$ack"' EXIT
ready=false
for attempt in $(seq 1 100); do
    if grep -q '^inotify ' /proc/$watcher/fdinfo/*; then ready=true; break; fi
    sleep 0.05
done
$ready || { echo 'Trace close watcher not ready' >&2; exit 1; }
kill -TERM "$pid"
for attempt in $(seq 1 600); do
    if test -s "$ack"; then cat "$ack"; test -s "$trace"; exit; fi
    sleep 0.05
done
echo 'Trace CLOSE_WRITE not acknowledged' >&2
exit 1
CLOSE_TRACE
        adb -s "$serial" pull "$trace" "$out/traces/$scenario.perfetto-trace"
        [[ -s $out/traces/$scenario.perfetto-trace ]] || { echo 'Empty trace' >&2; exit 1; }
        continue
    fi
    echo 'FrameTimeline/presented timestamps unavailable on API26; atrace app-work proxy only' > "$out/traces/$scenario-limitations.txt"
    adb -s "$serial" shell atrace --async_start -a "$app" -b 131072 gfx view sched freq am wm > "$out/traces/$scenario-start.txt"
    if ! run "$scenario" 1 "$out/traces/$scenario-instrumentation.txt"; then
        adb -s "$serial" shell atrace -b 131072 --async_stop > "$out/traces/$scenario-failed.trace"
        exit 1
    fi
    # API 26 reapplies buffer size on stop; retain it to avoid truncating the trace.
    adb -s "$serial" exec-out atrace -b 131072 --async_stop -z > "$out/traces/$scenario.ctrace"
done
