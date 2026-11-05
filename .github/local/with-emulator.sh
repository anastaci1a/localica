#!/usr/bin/env bash
# Boot a disposable emulator from a cached clean snapshot, run a command against it, then stop it.
# Local locked-down runner only (see android-local.yml, LOCALICA_JIT_RUNNER). Hosted jobs use
# reactivecircus/android-emulator-runner; this script mirrors its AVD, GPU and settings for the
# same API/target/profile, so the tests see the same device. Only boot time differs.
#
#   with-emulator.sh <26|36> -- command args...
#
# Needs: read-only ANDROID_HOME, writable ANDROID_USER_HOME, writable LOCALICA_AVD_CACHE.
set -euo pipefail
umask 0002
api=${1:?api}; shift
[[ ${1:-} == -- ]] && shift
[[ $api == 26 || $api == 36 ]] || { echo "unsupported api $api" >&2; exit 2; }
[[ ${LOCALICA_JIT_RUNNER:-} == 1 ]] || { echo 'refusing: not the locked-down JIT runner image' >&2; exit 2; }
: "${ANDROID_HOME:?}" "${ANDROID_USER_HOME:?}" "${LOCALICA_AVD_CACHE:?}"
(( $# )) || { echo 'no command' >&2; exit 2; }

if [[ $api == 36 ]]; then tag=default; gpu=lavapipe; wm_size=720x1280; wm_density=280; animations=keep
else tag=google_apis; gpu=swiftshader_indirect; wm_size=reset; wm_density=reset; animations=off; fi
memory=3072
sysdir=$ANDROID_HOME/system-images/android-$api/$tag/x86_64
[[ -f $sysdir/source.properties ]] || { echo "missing system image $sysdir" >&2; exit 2; }
emulator=$ANDROID_HOME/emulator/emulator
adb=$ANDROID_HOME/platform-tools/adb
avdmanager=${AVDMANAGER:-/opt/android-sdk/cmdline-tools/latest/bin/avdmanager}
export ANDROID_AVD_HOME=$ANDROID_USER_HOME/avd
export ANDROID_EMULATOR_HOME=$ANDROID_USER_HOME
mkdir -p "$ANDROID_AVD_HOME" "$LOCALICA_AVD_CACHE"
log_dir=${LOCALICA_EMULATOR_LOG_DIR:-${RUNNER_TEMP:-/scratch/tmp}/emulator}
mkdir -p "$log_dir"

key=$( { cat "$ANDROID_HOME/emulator/source.properties" "$sysdir/source.properties"; echo "$gpu $memory $wm_size $wm_density $animations v1"; } | sha256sum | cut -c1-12)
template=$LOCALICA_AVD_CACHE/api$api-$key

port=5554
serial=emulator-$port
emu_pid=''
stop_emulator() {
  [[ -n $emu_pid ]] || return 0
  "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
  for _ in $(seq 30); do kill -0 "$emu_pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$emu_pid" 2>/dev/null; then kill "$emu_pid" 2>/dev/null || true; fi
  wait "$emu_pid" 2>/dev/null || true
  emu_pid=''
}
trap stop_emulator EXIT

wait_boot() {
  local deadline=$(( $(date +%s) + ${1:-600} ))
  "$adb" start-server >/dev/null 2>&1 || true
  "$adb" -s "$serial" wait-for-device
  until [[ $("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
    kill -0 "$emu_pid" 2>/dev/null || { echo 'emulator exited during boot' >&2; return 1; }
    (( $(date +%s) < deadline )) || { echo 'emulator boot timeout' >&2; return 1; }
    sleep 2
  done
}

start_emulator() {  # $@ = extra emulator flags
  "$emulator" -avd localica -no-window -gpu "$gpu" -noaudio -no-boot-anim -memory "$memory" -port "$port" "$@" \
    > "$log_dir/emulator-api$api.log" 2>&1 &
  emu_pid=$!
}

install_avd() {  # copy the template into this container's private AVD home
  rm -rf "$ANDROID_AVD_HOME/localica.avd"
  cp -a --reflink=auto "$template/localica.avd" "$ANDROID_AVD_HOME/localica.avd"
  printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/localica.avd\ntarget=android-%s\n' "$ANDROID_AVD_HOME/localica.avd" "$api" > "$ANDROID_AVD_HOME/localica.ini"
}

build_template() {
  echo "building clean-boot snapshot template for API $api ($key)"
  rm -rf "$ANDROID_AVD_HOME/localica.avd" "$ANDROID_AVD_HOME/localica.ini" "$template.tmp"
  echo no | "$avdmanager" --verbose create avd --force -n localica -k "system-images;android-$api;$tag;x86_64" -d pixel_2 --sdk_root "$ANDROID_HOME" >/dev/null
  printf 'hw.keyboard=yes\nhw.ramSize=%s\n' "$memory" >> "$ANDROID_AVD_HOME/localica.avd/config.ini"
  start_emulator -no-snapshot-load -no-snapshot-save
  wait_boot 900
  if [[ $animations == off ]]; then
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do "$adb" -s "$serial" shell settings put global "$s" 0.0; done
  fi
  "$adb" -s "$serial" shell wm size "$wm_size"
  "$adb" -s "$serial" shell wm density "$wm_density"
  sleep 60   # same settle time as the hosted workflow's script
  "$adb" -s "$serial" emu avd snapshot save clean | tee "$log_dir/snapshot-save-api$api.txt"
  stop_emulator
  mkdir "$template.tmp"
  cp -a --reflink=auto "$ANDROID_AVD_HOME/localica.avd" "$template.tmp/localica.avd"
  date -u +%FT%TZ > "$template.tmp/READY"
  mv "$template.tmp" "$template"
}

exec 9>"$LOCALICA_AVD_CACHE/api$api.lock"
flock -x 9
if [[ ! -f $template/READY ]]; then
  t=$(date +%s); build_template; echo "template built in $(( $(date +%s) - t )) s"
fi
flock -u 9
install_avd

t=$(date +%s)
start_emulator -snapshot clean -no-snapshot-save
wait_boot 600
echo "emulator API $api ready from snapshot in $(( $(date +%s) - t )) s"
"$adb" -s "$serial" shell getprop ro.build.version.sdk
export ANDROID_SERIAL=$serial
set +e
"$@"
rc=$?
set -e
exit "$rc"
