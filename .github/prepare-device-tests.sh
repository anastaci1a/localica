#!/usr/bin/env bash
# Source from the native runner so package restoration survives instrumentation.
set -euo pipefail
[[ "$BASH_SOURCE" != "$0" ]] || { echo 'Source this setup from run-device-tests.sh' >&2; exit 2; }

localica_guard_adb() { adb -s "$LOCALICA_GUARD_SERIAL" "$@"; }
localica_guard_probe() {
  local dump line
  dump=$(localica_guard_adb shell dumpsys package com.google.android.googlequicksearchbox | tr -d '\r') || return
  line=$(printf '%s\n' "$dump" | grep -E '^[[:space:]]*User 0:.*[[:space:]]installed=') || return
  [[ $(printf '%s\n' "$line" | wc -l) == 1 && "$line" == *installed=true* ]] || {
    echo 'Expected one installed user-0 package-state row' >&2; return 2;
  }
  LOCALICA_GUARD_STATE=$(printf '%s\n' "$line" | awk '{for(i=1;i<=NF;i++)if($i~/^enabled=[0-4]$/){sub(/^enabled=/,"",$i);print $i}}') || return
  [[ "$LOCALICA_GUARD_STATE" =~ ^[0-4]$ ]] || return 2
  LOCALICA_GUARD_ID=$(printf '%s\n' "$dump" | awk '$1~/^(userId|codePath|versionCode)=/{print}' | sort -u) || return
  [[ "$LOCALICA_GUARD_ID" == *userId=* && "$LOCALICA_GUARD_ID" == *codePath=* && "$LOCALICA_GUARD_ID" == *versionCode=* ]] || return 2
}
localica_guard_verify_device() {
  local serial boot avd
  serial=$(localica_guard_adb get-serialno | tr -d '\r') || return
  [[ "$serial" == "$LOCALICA_GUARD_SERIAL" ]] || return 2
  [[ $(localica_guard_adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || return 2
  [[ $(localica_guard_adb shell getprop ro.build.version.sdk | tr -d '\r') == 26 ]] || return 2
  boot=$(localica_guard_adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r') || return
  avd=$(localica_guard_adb emu avd name | tr -d '\r' | sed '/^OK$/d') || return
  [[ "$boot" == "$LOCALICA_GUARD_BOOT" && "$avd" == "$LOCALICA_GUARD_AVD" ]] || return 2
}
localica_guard_restore() {
  local token command
  localica_guard_verify_device || return
  token=$(localica_guard_adb shell cat "$LOCALICA_GUARD_DIR/token" | tr -d '\r') || return
  [[ "$token" == "$LOCALICA_GUARD_TOKEN" ]] || return 2
  localica_guard_probe || return
  [[ "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
  if [[ "$LOCALICA_GUARD_MUTATION_STARTED" == no ]]; then
    [[ "$LOCALICA_GUARD_STATE" == "$LOCALICA_GUARD_PRIOR" ]] || return 2
  elif [[ "$LOCALICA_GUARD_STATE" == 3 ]]; then
    case "$LOCALICA_GUARD_PRIOR" in
      0) command=default-state ;; 1) command=enable ;; 2) command=disable ;;
      3) command=disable-user ;; 4) command=disable-until-used ;; *) return 2 ;;
    esac
    # Only user 0 and the single constant package; never clear data or touch Localica.
    localica_guard_adb shell pm "$command" --user 0 com.google.android.googlequicksearchbox || return
    localica_guard_probe || return
    [[ "$LOCALICA_GUARD_STATE" == "$LOCALICA_GUARD_PRIOR" && "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
  else
    echo 'Guard state drift; refusing to overwrite another actor' >&2
    return 2
  fi
  # Delete only this token and empty lock directory, conditional on token still ours.
  localica_guard_adb shell "[ \"\$(cat '$LOCALICA_GUARD_DIR/token')\" = '$LOCALICA_GUARD_TOKEN' ] && rm '$LOCALICA_GUARD_DIR/token' && rmdir '$LOCALICA_GUARD_DIR'" || return
}
localica_guard_exit() {
  local result=$? cleanup=0
  trap - EXIT INT TERM
  localica_guard_restore || cleanup=$?
  if (( cleanup != 0 )); then
    echo "API26 package restoration failed ($cleanup); preserve guard receipt/state for review" >&2
    (( result != 0 )) || result=3
  fi
  exit "$result"
}
localica_guard_prepare() {
  # No mutation on local/shared devices; this is the hosted native runner only.
  [[ -v GITHUB_ACTIONS && "$GITHUB_ACTIONS" == true && -v RUNNER_ENVIRONMENT && "$RUNNER_ENVIRONMENT" == github-hosted ]] || return 0
  LOCALICA_GUARD_SERIAL=emulator-5554
  if [[ -v ANDROID_SERIAL && -n "$ANDROID_SERIAL" ]]; then LOCALICA_GUARD_SERIAL=$ANDROID_SERIAL; fi
  [[ "$LOCALICA_GUARD_SERIAL" =~ ^emulator-[0-9]+$ ]] || return 2
  [[ $(adb devices | awk 'NR>1 && NF {print $1 " " $2}') == "$LOCALICA_GUARD_SERIAL device" ]] || { echo 'Require exactly the selected hosted emulator' >&2; return 2; }
  [[ $(localica_guard_adb get-serialno | tr -d '\r') == "$LOCALICA_GUARD_SERIAL" ]] || return 2
  [[ $(localica_guard_adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || return 2
  local api installed
  api=$(localica_guard_adb shell getprop ro.build.version.sdk | tr -d '\r') || return
  [[ "$api" =~ ^[0-9]+$ ]] || return 2
  [[ "$api" == 26 ]] || return 0
  [[ -v TEST_API && "$TEST_API" == 26 && -v GITHUB_RUN_ID && "$GITHUB_RUN_ID" =~ ^[0-9]+$ ]] || return 2
  [[ -z $(trap -p EXIT INT TERM) ]] || { echo 'Refusing to replace existing cleanup traps' >&2; return 2; }
  installed=$(localica_guard_adb shell pm list packages --user 0 com.google.android.googlequicksearchbox | tr -d '\r') || return
  grep -Fxq 'package:com.google.android.googlequicksearchbox' <<< "$installed" || return 0
  localica_guard_probe || return
  LOCALICA_GUARD_PRIOR=$LOCALICA_GUARD_STATE
  LOCALICA_GUARD_SAVED_ID=$LOCALICA_GUARD_ID
  LOCALICA_GUARD_BOOT=$(localica_guard_adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r') || return
  LOCALICA_GUARD_AVD=$(localica_guard_adb emu avd name | tr -d '\r' | sed '/^OK$/d') || return
  [[ -n "$LOCALICA_GUARD_BOOT" && -n "$LOCALICA_GUARD_AVD" ]] || return 2
  LOCALICA_GUARD_DIR=/data/local/tmp/localica-api26-gsa-guard
  LOCALICA_GUARD_TOKEN="$GITHUB_RUN_ID:$$:$RANDOM:$RANDOM"
  LOCALICA_GUARD_MUTATION_STARTED=no
  # Exclusive device-side lease; refuse stale/foreign leases, never remove them.
  localica_guard_adb shell "umask 077; mkdir '$LOCALICA_GUARD_DIR' && printf '%s\\n' '$LOCALICA_GUARD_TOKEN' > '$LOCALICA_GUARD_DIR/token'" || return
  trap localica_guard_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  export ANDROID_SERIAL=$LOCALICA_GUARD_SERIAL
  echo "API26 guard owner=$LOCALICA_GUARD_TOKEN serial=$LOCALICA_GUARD_SERIAL avd=$LOCALICA_GUARD_AVD boot=$LOCALICA_GUARD_BOOT prior=$LOCALICA_GUARD_PRIOR"
  localica_guard_verify_device || return
  [[ $(localica_guard_adb shell cat "$LOCALICA_GUARD_DIR/token" | tr -d '\r') == "$LOCALICA_GUARD_TOKEN" ]] || return 2
  localica_guard_probe || return
  [[ "$LOCALICA_GUARD_STATE" == "$LOCALICA_GUARD_PRIOR" && "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
  LOCALICA_GUARD_MUTATION_STARTED=yes
  localica_guard_adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox || return
  localica_guard_probe || return
  [[ "$LOCALICA_GUARD_STATE" == 3 && "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
}
localica_guard_prepare
