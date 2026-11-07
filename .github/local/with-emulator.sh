#!/usr/bin/env bash
# Disposable emulator for prototype.yml focused jobs on the locked-down self-hosted JIT runner (#592).
#
# Mirrors reactivecircus/android-emulator-runner (the hosted path) for the same inputs: a freshly
# created AVD named "test" (forced), hw.cpu.ncore=2, a cold boot with the same emulator options,
# a 600 s boot timeout on port 5554, "input keyevent 82", optional animation scales of 0, then each
# non-empty, non-comment script line through `sh -c` with ANDROID_SERIAL and EMULATOR_PORT set,
# stopping at the first failing line, and `emu kill` afterwards. Only the SDK location differs:
# the runner image carries the SDK and both system images read-only.
#
# Inputs (environment): EMULATOR_API, EMULATOR_TARGET, EMULATOR_PROFILE, EMULATOR_OPTIONS,
# EMULATOR_DISABLE_ANIMATIONS (true|false) and EMULATOR_SCRIPT. The workflow contract test keeps
# them identical to the hosted step's inputs.
set -euo pipefail
umask 0002
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == self-hosted && ${LOCALICA_JIT_RUNNER:-} == 1 && ${LOCALICA_EPHEMERAL_RUNNER:-} == localica-kvm ]] ||
  { echo 'refusing: not the locked-down self-hosted JIT runner' >&2; exit 2; }
api=${EMULATOR_API:?}; target=${EMULATOR_TARGET:?}; profile=${EMULATOR_PROFILE:?}
options=${EMULATOR_OPTIONS:?}; animations=${EMULATOR_DISABLE_ANIMATIONS:?}; script=${EMULATOR_SCRIPT:?}
[[ $api =~ ^[0-9]+$ && $target =~ ^[a-z_]+$ && $profile =~ ^[a-z0-9_]+$ ]] || { echo 'invalid emulator identity' >&2; exit 2; }
[[ $animations == true || $animations == false ]] || { echo 'EMULATOR_DISABLE_ANIMATIONS must be true or false' >&2; exit 2; }
: "${ANDROID_HOME:?}"
package="system-images;android-$api;$target;x86_64"
[[ -f $ANDROID_HOME/system-images/android-$api/$target/x86_64/source.properties ]] || { echo "missing $package" >&2; exit 2; }
export ANDROID_AVD_HOME=$HOME/.android/avd
mkdir -p "$ANDROID_AVD_HOME"
port=5554
serial=emulator-$port
log=${RUNNER_TEMP:-/tmp}/emulator-api$api.log

echo '::group::Create AVD'
echo no | avdmanager create avd --force -n test --package "$package" --device "$profile"
printf 'hw.cpu.ncore=2\n' >> "$ANDROID_AVD_HOME/test.avd/config.ini"
echo '::endgroup::'

emu_pid=''
stop_emulator() {
  [[ -n $emu_pid ]] || return 0
  echo '::group::Terminate Emulator'
  adb -s "$serial" emu kill || true
  for _ in $(seq 30); do kill -0 "$emu_pid" 2>/dev/null || break; sleep 1; done
  kill -0 "$emu_pid" 2>/dev/null && kill "$emu_pid" 2>/dev/null || true
  wait "$emu_pid" 2>/dev/null || true
  emu_pid=''
  echo '::endgroup::'
}
trap stop_emulator EXIT

echo '::group::Launch Emulator'
# shellcheck disable=SC2086  # options are a word list, exactly as the action passes them to sh -c
"$ANDROID_HOME/emulator/emulator" -port "$port" -avd test $options > "$log" 2>&1 &
emu_pid=$!
t0=$(date +%s)
booted=''
for _ in $(seq 300); do
  if ! kill -0 "$emu_pid" 2>/dev/null; then break; fi
  if [[ $(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then booted=1; break; fi
  sleep 2
done
if [[ -z $booted ]]; then
  echo "::error::emulator API $api did not boot within 600 s"; tail -n 150 "$log" || true; exit 1
fi
echo "Emulator booted in $(( $(date +%s) - t0 )) s."
adb -s "$serial" shell input keyevent 82
if [[ $animations == true ]]; then
  for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb -s "$serial" shell settings put global "$scale" 0.0
  done
fi
echo '::endgroup::'

export ANDROID_SERIAL=$serial EMULATOR_PORT=$port
rc=0
while IFS= read -r line || [[ -n $line ]]; do
  line=${line#"${line%%[![:space:]]*}"}
  [[ -n $line && $line != \#* ]] || continue
  sh -c "$line" || { rc=$?; echo "::error::script line failed ($rc): $line"; break; }
done <<< "$script"
if (( rc )); then tail -n 150 "$log" || true; fi
exit "$rc"
