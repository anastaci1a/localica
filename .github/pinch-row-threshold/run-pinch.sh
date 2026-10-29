#!/usr/bin/env bash
# Disposable hosted emulator only; preserve original guard's EXIT restoration.
set -euo pipefail
[[ $# == 7 ]] || { echo 'Usage: run-pinch.sh BASELINE CANDIDATE EQUIVALENCE SUPPORT CONFIG OUTPUT API' >&2; exit 2; }
baseline=$(realpath "$1")
candidate=$(realpath "$2")
equivalence=$(realpath "$3")
support=$(realpath "$4")
config=$(realpath "$5")
output=$(realpath -m "$6")
export TEST_API=$7
[[ "$TEST_API" == 26 || "$TEST_API" == 36 ]] || exit 2
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
[[ $(adb devices | awk 'NR>1 && NF {print $1 " " $2}') == "$serial device" ]] || exit 2
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || exit 2
[[ $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r') == "$TEST_API" ]] || exit 2
export ANDROID_SERIAL=$serial
runner=$(realpath "$(dirname "$0")/run_pinch.py")
python3 "$runner" --config "$config" --print-refs >/dev/null
cd "$baseline"
[[ $(git rev-parse HEAD) == 39f5b6a4a27fc46c907b2d4c184c2ef5e3a0bb56 ]] || exit 2
[[ -z $(git status --porcelain --untracked-files=no) ]] || exit 2
source .github/prepare-device-tests.sh
child=''
stop_child() {
  local sig=$1 code=$2
  trap '' INT TERM
  if [[ -n "$child" ]]; then kill -s "$sig" "$child" 2>/dev/null || true; wait "$child" || true; fi
  exit "$code"
}
trap 'stop_child TERM 143' TERM
trap 'stop_child INT 130' INT
set +e
python3 "$runner" --baseline "$baseline" --candidate "$candidate" --equivalence "$equivalence" --reviewed-harness "$support" --config "$config" --output "$output" --api "$TEST_API" &
child=$!
wait "$child"
result=$?
child=''
set -e
exit "$result"
