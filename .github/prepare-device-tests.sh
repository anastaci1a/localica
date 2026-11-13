#!/usr/bin/env bash
# Source from the native runner so package restoration survives instrumentation.
set -euo pipefail
[[ "$BASH_SOURCE" != "$0" ]] || { echo 'Source this setup from run-device-tests.sh' >&2; exit 2; }

# Explicit return statuses also survive Bash 5.2's EXIT-trap context.
localica_guard_adb() { adb -s "$LOCALICA_GUARD_SERIAL" "$@"; }
localica_guard_probe() {
  local dump line
  dump=$(localica_guard_adb shell dumpsys package com.google.android.googlequicksearchbox | tr -d '\r') || return $?
  line=$(printf '%s\n' "$dump" | grep -E '^[[:space:]]*User 0:.*[[:space:]]installed=') || return $?
  [[ $(printf '%s\n' "$line" | wc -l) == 1 && "$line" == *installed=true* ]] || {
    echo 'Expected one installed user-0 package-state row' >&2; return 2;
  }
  LOCALICA_GUARD_STATE=$(printf '%s\n' "$line" | awk '{for(i=1;i<=NF;i++)if($i~/^enabled=[0-4]$/){sub(/^enabled=/,"",$i);print $i}}') || return $?
  [[ "$LOCALICA_GUARD_STATE" =~ ^[0-4]$ ]] || return 2
  LOCALICA_GUARD_ID=$(printf '%s\n' "$dump" | awk '$1~/^(userId|codePath|versionCode)=/{print}' | sort -u) || return $?
  [[ "$LOCALICA_GUARD_ID" == *userId=* && "$LOCALICA_GUARD_ID" == *codePath=* && "$LOCALICA_GUARD_ID" == *versionCode=* ]] || return 2
}
localica_guard_verify_device() {
  local serial boot avd
  serial=$(localica_guard_adb get-serialno | tr -d '\r') || return $?
  [[ "$serial" == "$LOCALICA_GUARD_SERIAL" ]] || return 2
  [[ $(localica_guard_adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || return 2
  [[ $(localica_guard_adb shell getprop ro.build.version.sdk | tr -d '\r') == 26 ]] || return 2
  boot=$(localica_guard_adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r') || return $?
  avd=$(localica_guard_adb emu avd name | tr -d '\r' | sed '/^OK$/d') || return $?
  [[ "$boot" == "$LOCALICA_GUARD_BOOT" && "$avd" == "$LOCALICA_GUARD_AVD" ]] || return 2
}
localica_guard_restore() {
  local token command
  localica_guard_verify_device || return $?
  token=$(localica_guard_adb shell cat "$LOCALICA_GUARD_DIR/token" | tr -d '\r') || return $?
  [[ "$token" == "$LOCALICA_GUARD_TOKEN" ]] || return 2
  localica_guard_probe || return $?
  [[ "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
  if [[ "$LOCALICA_GUARD_MUTATION_STARTED" == no ]]; then
    [[ "$LOCALICA_GUARD_STATE" == "$LOCALICA_GUARD_PRIOR" ]] || return 2
  elif [[ "$LOCALICA_GUARD_STATE" == 3 ]]; then
    case "$LOCALICA_GUARD_PRIOR" in
      0) command=default-state ;; 1) command=enable ;; 2) command=disable ;;
      3) command=disable-user ;; 4) command=disable-until-used ;; *) return 2 ;;
    esac
    # Only user 0 and the single constant package; never clear data or touch Localica.
    localica_guard_adb shell pm "$command" --user 0 com.google.android.googlequicksearchbox || return $?
    localica_guard_probe || return $?
    [[ "$LOCALICA_GUARD_STATE" == "$LOCALICA_GUARD_PRIOR" && "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
  else
    echo 'Guard state drift; refusing to overwrite another actor' >&2
    return 2
  fi
  # Delete only this token and empty lock directory, conditional on token still ours.
  localica_guard_adb shell "[ \"\$(cat '$LOCALICA_GUARD_DIR/token')\" = '$LOCALICA_GUARD_TOKEN' ] && rm '$LOCALICA_GUARD_DIR/token' && rmdir '$LOCALICA_GUARD_DIR'" || return $?
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
  # No mutation on local/shared devices: only the hosted native runner or the disposable
  # emulator of the locked-down single-job self-hosted JIT runner image (#592).
  [[ -v GITHUB_ACTIONS && "$GITHUB_ACTIONS" == true && -v RUNNER_ENVIRONMENT ]] || return 0
  [[ "$RUNNER_ENVIRONMENT" == github-hosted || ( "$RUNNER_ENVIRONMENT" == self-hosted && "${LOCALICA_JIT_RUNNER:-}" == 1 && "${LOCALICA_EPHEMERAL_RUNNER:-}" == localica-kvm ) ]] || return 0
  LOCALICA_GUARD_SERIAL=emulator-5554
  if [[ -v ANDROID_SERIAL && -n "$ANDROID_SERIAL" ]]; then LOCALICA_GUARD_SERIAL=$ANDROID_SERIAL; fi
  [[ "$LOCALICA_GUARD_SERIAL" =~ ^emulator-[0-9]+$ ]] || return 2
  [[ $(adb devices | awk 'NR>1 && NF {print $1 " " $2}') == "$LOCALICA_GUARD_SERIAL device" ]] || { echo 'Require exactly the selected hosted emulator' >&2; return 2; }
  [[ $(localica_guard_adb get-serialno | tr -d '\r') == "$LOCALICA_GUARD_SERIAL" ]] || return 2
  [[ $(localica_guard_adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || return 2
  local api installed
  api=$(localica_guard_adb shell getprop ro.build.version.sdk | tr -d '\r') || return $?
  [[ "$api" =~ ^[0-9]+$ ]] || return 2
  [[ "$api" == 26 ]] || return 0
  [[ -v TEST_API && "$TEST_API" == 26 && -v GITHUB_RUN_ID && "$GITHUB_RUN_ID" =~ ^[0-9]+$ ]] || return 2
  [[ -z $(trap -p EXIT INT TERM) ]] || { echo 'Refusing to replace existing cleanup traps' >&2; return 2; }
  installed=$(localica_guard_adb shell pm list packages --user 0 com.google.android.googlequicksearchbox | tr -d '\r') || return $?
  grep -Fxq 'package:com.google.android.googlequicksearchbox' <<< "$installed" || return 0
  localica_guard_probe || return $?
  LOCALICA_GUARD_PRIOR=$LOCALICA_GUARD_STATE
  LOCALICA_GUARD_SAVED_ID=$LOCALICA_GUARD_ID
  LOCALICA_GUARD_BOOT=$(localica_guard_adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r') || return $?
  LOCALICA_GUARD_AVD=$(localica_guard_adb emu avd name | tr -d '\r' | sed '/^OK$/d') || return $?
  [[ -n "$LOCALICA_GUARD_BOOT" && -n "$LOCALICA_GUARD_AVD" ]] || return 2
  LOCALICA_GUARD_DIR=/data/local/tmp/localica-api26-gsa-guard
  LOCALICA_GUARD_TOKEN="$GITHUB_RUN_ID:$$:$RANDOM:$RANDOM"
  LOCALICA_GUARD_MUTATION_STARTED=no
  # Exclusive device-side lease; refuse stale/foreign leases, never remove them.
  localica_guard_adb shell "umask 077; mkdir '$LOCALICA_GUARD_DIR' && printf '%s\\n' '$LOCALICA_GUARD_TOKEN' > '$LOCALICA_GUARD_DIR/token'" || return $?
  trap localica_guard_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  export ANDROID_SERIAL=$LOCALICA_GUARD_SERIAL
  echo "API26 guard owner=$LOCALICA_GUARD_TOKEN serial=$LOCALICA_GUARD_SERIAL avd=$LOCALICA_GUARD_AVD boot=$LOCALICA_GUARD_BOOT prior=$LOCALICA_GUARD_PRIOR"
  localica_guard_verify_device || return $?
  [[ $(localica_guard_adb shell cat "$LOCALICA_GUARD_DIR/token" | tr -d '\r') == "$LOCALICA_GUARD_TOKEN" ]] || return 2
  localica_guard_probe || return $?
  [[ "$LOCALICA_GUARD_STATE" == "$LOCALICA_GUARD_PRIOR" && "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
  LOCALICA_GUARD_MUTATION_STARTED=yes
  localica_guard_adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox || return $?
  localica_guard_probe || return $?
  [[ "$LOCALICA_GUARD_STATE" == 3 && "$LOCALICA_GUARD_ID" == "$LOCALICA_GUARD_SAVED_ID" ]] || return 2
}
# Hosted disposable emulator only: a system crash/ANR dialog takes window focus
# from instrumentation (#529). Suppress them once, then dismiss any survivor
# before each instrumentation command. Best effort; never fails the run.
dialogs_ready=''
dialog_adb() {
  local serial=${LOCALICA_GUARD_SERIAL:-${ANDROID_SERIAL:-}}
  if [[ -n "$serial" ]]; then timeout 20 adb -s "$serial" "$@"; else timeout 20 adb "$@"; fi
}
focused_error_dialog() {
  dialog_adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -E '^[[:space:]]*(mCurrentFocus|mFocusedWindow)=.*(Application Error|Application Not Responding):' | head -n 1 || true
}
guard_error_dialogs() {
  local lane=$1 setting ns key value found
  [[ ${GITHUB_ACTIONS:-} == true ]] || return 0
  [[ ${RUNNER_ENVIRONMENT:-} == github-hosted || ( ${RUNNER_ENVIRONMENT:-} == self-hosted && ${LOCALICA_JIT_RUNNER:-} == 1 && ${LOCALICA_EPHEMERAL_RUNNER:-} == localica-kvm ) ]] || return 0
  [[ $(dialog_adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r') == 1 ]] || return 0
  if [[ -z "$dialogs_ready" ]]; then
    dialogs_ready=yes
    # Keys absent on an API are harmless; read each back to prove the value.
    for setting in secure:anr_show_background:0 global:show_first_crash_dialog:0 global:show_first_crash_dialog_dev_option:0; do
      IFS=: read -r ns key value <<< "$setting"
      dialog_adb shell settings put "$ns" "$key" "$value" >/dev/null 2>&1 || true
      echo "error-dialog setting $ns/$key=$(dialog_adb shell settings get "$ns" "$key" 2>/dev/null | tr -d '\r') (wanted $value)"
    done
    # API 36 System UI aborts when its jank tracker flushes data for a transition leash that WM
    # Shell is releasing (#673: FrameTracker nativeSurfaceFlushJankData vs SurfaceControl.release),
    # killing the instrumentation run. That tracker measures System UI, not the app: turn it off,
    # and stop flag sync from turning it back on.
    dialog_adb shell device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true
    dialog_adb shell device_config put interaction_jank_monitor enabled false >/dev/null 2>&1 || true
    echo "system-ui jank monitor interaction_jank_monitor/enabled=$(dialog_adb shell device_config get interaction_jank_monitor enabled 2>/dev/null | tr -d '\r') (wanted false)"
  fi
  found=$(focused_error_dialog)
  if [[ -z "$found" ]]; then echo "error-dialog check before $lane: none found"; return 0; fi
  dialog_adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 1
  if [[ -n $(focused_error_dialog) ]]; then dialog_adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true; sleep 1; fi
  if [[ -n $(focused_error_dialog) ]]; then echo "error-dialog check before $lane: found and NOT dismissed: ${found//[[:space:]]/ }"
  else echo "error-dialog check before $lane: found and dismissed: ${found//[[:space:]]/ }"; fi
  return 0
}
localica_guard_prepare
guard_error_dialogs preparation
