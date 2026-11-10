#!/usr/bin/env bash
# Separate service-only cold processes. Fatal policy and ordinary native gates remain mandatory.
set -euo pipefail
serial=${1:?emulator serial}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
app=io.github.anastaci1a.localica.debug
runner=$app.test/io.github.anastaci1a.localica.NavigationRandomOwnedSession
out=prototype-evidence/prepared-entry-cold
[[ ! -e "$out" ]] || { echo 'Refusing stale preference-entry evidence' >&2; exit 2; }
mkdir -p "$out"
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk > "$out/install-app.txt"
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > "$out/install-test.txt"
directory=''
capture() {
  [[ -n "$directory" ]] || return 0
  adb -s "$serial" logcat -d -v threadtime > "$directory/logcat.txt" 2>&1 || true
  adb -s "$serial" logcat -b events -d -v threadtime > "$directory/events.txt" 2>&1 || true
  adb -s "$serial" logcat -b crash -d -v threadtime > "$directory/crashes.txt" 2>&1 || true
}
trap capture EXIT
declare -A pids=()
for mode in playback scan tag lastfm; do
  directory=$out/$mode
  mkdir "$directory"
  adb -s "$serial" shell am force-stop "$app"
  [[ -z $(adb -s "$serial" shell pidof "$app" | tr -d '\r') ]]
  printf 'process-absent-before=true\n' > "$directory/receipt.txt"
  adb -s "$serial" shell am instrument -w -r \
    -e class io.github.anastaci1a.localica.PreparedColdEntryTest \
    -e preparedEntry "$mode" -e expectFatalPolicy true -e expectSeededTracks 13400 \
    "$runner" > "$directory/instrumentation.txt"
  capture
  grep -Eq '^OK \(1 test\)' "$directory/instrumentation.txt"
  ! grep -Eq 'FAILURES|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$directory/instrumentation.txt"
  receipt=$(grep -oE "prepared-entry=$mode;pid=[0-9]+;activities=0;.*" "$directory/instrumentation.txt" | head -1)
  [[ -n "$receipt" ]]
  pid=$(sed -nE 's/.*;pid=([0-9]+);.*/\1/p' <<< "$receipt")
  [[ "$pid" =~ ^[0-9]+$ && -z ${pids[$pid]:-} ]]
  pids[$pid]=$mode
  if [[ "$mode" == playback ]]; then
    grep -E 'playback-attached=true;play-pause-order=true;controller-released=true;service-stopped=true;durable-position=1234;native-released=true;player-id=[0-9a-f]+' "$directory/instrumentation.txt" > "$directory/playback.txt"
    player_id=$(sed -nE 's/.*;player-id=([0-9a-f]+).*/\1/p' "$directory/playback.txt")
    [[ "$player_id" =~ ^[0-9a-f]+$ ]]
    grep -E "[[:space:]]$pid[[:space:]]+$pid[[:space:]].*ExoPlayerImpl[[:space:]]*: Init $player_id[[:space:]]" "$directory/logcat.txt" > "$directory/player-init.txt"
    grep -E "[[:space:]]$pid[[:space:]]+$pid[[:space:]].*ExoPlayerImpl[[:space:]]*: Release $player_id[[:space:]]" "$directory/logcat.txt" > "$directory/player-release.txt"
  fi
  if [[ "$mode" == scan || "$mode" == tag ]]; then
    if [[ "$mode" == scan ]]; then service=ScanService; notification=9001; else service=TagEditService; notification=9010; fi
    # API26 does not emit am_create_service on these images. The service's foreground notification
    # is an independently recorded binder-side entry witness, with exact PID/package/notification ID.
    grep -F 'notification_enqueue:' "$directory/events.txt" | grep -F ",$pid,$app,$notification," > "$directory/foreground-notification.txt"
    printf 'service-entry=%s;foreground-notification=%s;same-pid=true\n' "$service" "$notification" >> "$directory/receipt.txt"
  fi
  printf '%s\ntracks=13400\nstrictmode=fatal\n' "$receipt" >> "$directory/receipt.txt"
  adb -s "$serial" shell am force-stop "$app"
done
trap - EXIT
