#!/usr/bin/env bash
# One disposable hosted emulator, owned package restoration, no whole-suite run.
set -euo pipefail
[[ $# == 4 ]] || { echo 'Usage: run-clean.sh SOURCE REVIEWED_HARNESS OUTPUT API' >&2; exit 2; }
source_root=$(realpath "$1")
support_root=$(realpath "$2")
output=$(realpath -m "$3")
export TEST_API=$4
[[ "$TEST_API" == 26 || "$TEST_API" == 36 ]] || exit 2
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || exit 2
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
[[ $(adb devices | awk 'NR>1 && NF {print $1 " " $2}') == "$serial device" ]] || exit 2
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || exit 2
[[ $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r') == "$TEST_API" ]] || exit 2
export ANDROID_SERIAL=$serial
runner=$(realpath "$(dirname "$0")/run_clean.py")
cd "$source_root"
[[ $(git rev-parse HEAD) == afebeba6d459e97743e6e2dd0bb14f91510db2ca ]] || exit 2
[[ $(git rev-parse 'HEAD^{tree}') == ad0a4c9f29232bf2916198f915ea6e4eb2fb2f16 ]] || exit 2
[[ -z $(git status --porcelain --untracked-files=no) ]] || exit 2
# The parent workflow checks out the immutable clean source. Source the
# unmodified project guard once; it owns its EXIT restoration across all trials.
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
python3 "$runner" --source "$source_root" --reviewed-harness "$support_root" --output "$output" --api "$TEST_API" &
child=$!
wait "$child"
result=$?
child=''
set -e
exit "$result"
