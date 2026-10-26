#!/usr/bin/env bash
# Hosted disposable emulator only; never used by compatibility's preserved-data phases.
set -euo pipefail
classes=${RISK_CLASSES:-}
reports="$PWD/preflight-reports"
live=${LOCALICA_LIVE_EVIDENCE:-false}
live_reports="$PWD/native-live-evidence"
child=''
[[ "$live" == true || "$live" == false ]] || { echo 'Invalid live evidence mode' >&2; exit 2; }
if [[ "$live" == true ]]; then
  [[ ${GITHUB_SHA:-} =~ ^[0-9a-f]{40}$ && $(git rev-parse HEAD) == "$GITHUB_SHA" ]] || { echo 'Live evidence requires the exact checkout SHA' >&2; exit 2; }
  [[ ${TEST_API:-} == 26 || ${TEST_API:-} == 36 ]] || { echo 'Live evidence requires API 26 or 36' >&2; exit 2; }
  [[ ${GITHUB_RUN_ID:-} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:-} =~ ^[0-9]+$ ]] || { echo 'Live evidence requires run/attempt identity' >&2; exit 2; }
  for helper in observe.py supervise.py publish.mjs; do
    [[ -f ".github/test-evidence/$helper" ]] || { echo "Missing live evidence helper: $helper" >&2; exit 2; }
  done
  [[ ! -e "$live_reports" && ! -L "$live_reports" ]] || { echo 'Refusing stale native live evidence; no replay' >&2; exit 2; }
  mkdir "$live_reports"
  printf 'sha=%s\napi=%s\nrun=%s\nattempt=%s\n' "$GITHUB_SHA" "$TEST_API" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" > "$live_reports/context.txt"
fi
stop_live_child() {
  local sig=$1 code=$2
  trap '' INT TERM
  if [[ -n "$child" ]]; then kill -s "$sig" "$child" 2>/dev/null || true; wait "$child" || true; fi
  # Only after the owned observer drains its test process may EXIT restore GSA.
  exit "$code"
}
install_live_signals() {
  if [[ "$live" == true ]]; then
    trap 'stop_live_child TERM 143' TERM
    trap 'stop_live_child INT 130' INT
  fi
}
run_native() {
  local lane=$1 result label
  shift
  if [[ "$live" == false ]]; then "$@"; return $?; fi
  label=$lane
  [[ "$lane" != full ]] || label=native
  python3 .github/test-evidence/supervise.py --output "$live_reports/$lane" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" --sha "$GITHUB_SHA" --api "$TEST_API" --lane "$label" -- "$@" &
  child=$!
  wait "$child"
  result=$?
  child=''
  return "$result"
}
verify_empty_preflight() {
  # A failed/degraded preflight stops before full. A successful one may admit full
  # only if its publisher used zero packets, retaining the six-packet job budget.
  python3 - "$live_reports/preflight" "$GITHUB_SHA" "$TEST_API" <<'PYLIVE'
import json
from pathlib import Path
import sys

root, sha, api = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
def require(condition):
    if not condition:
        raise ValueError("Invalid preflight admission evidence")
try:
    observed = json.loads((root/'observed/manifest.json').read_text())
    supervised = json.loads((root/'supervision.json').read_text())
    published = json.loads((root/'publishing/complete.json').read_text())
    require(observed['sha'] == supervised['sha'] == published['sha'] == sha)
    require(observed['api'] == supervised['api'] == published['api'] == api)
    require(observed['lane'] == published['lane'] == 'preflight')
    require(observed['command_id'] == published['command_id'])
    require(observed['status'] == 'completed' and observed['child_returncode'] == 0)
    require(observed['observer_exit_status'] == 0 and observed['observer_error'] is None)
    require(observed['cancel_signal'] is None and observed['observed_failure_lines'] == 0)
    require(supervised['observer_exit'] == supervised['publisher_exit'] == 0)
    require(supervised['monitoring_complete'] is True and supervised['supervisor_error'] is None)
    require(published['status'] == 'monitoring_complete')
    require(published['observer_status'] == 'completed')
    require(published['observed_failure_lines'] == published['observed_failures'] == 0)
    require(published['uploaded_packets'] == published['read_offset'] == 0)
    require((root/'observed/events.jsonl').read_bytes() == b'')
    require(not list((root/'publishing').glob('packet-*.json')))
    require(not list((root/'publishing').glob('receipt-*.json')))
    require(not (root/'publishing/monitoring-degraded.json').exists())
except (OSError, ValueError, KeyError, TypeError, AssertionError):
    print('Preflight cannot admit full: missing, degraded or contradictory zero-event evidence', file=sys.stderr)
    sys.exit(3)
PYLIVE
}
if [[ -z "$classes" ]]; then
  source .github/prepare-device-tests.sh
  install_live_signals
  # Keep the sourced EXIT restoration trap alive; preserve Gradle failure status.
  set +e
  run_native full ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
  result=$?
  set -e
  exit "$result"
fi
[[ ${#classes} -le 4096 ]] || { echo 'risk_classes exceeds 4096 characters' >&2; exit 2; }
pattern='^io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*(,io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*)*$'
[[ "$classes" =~ $pattern ]] || { echo 'Invalid risk_classes: expected comma-separated fully qualified test classes' >&2; exit 2; }
IFS=, read -r -a selected <<< "$classes"
[[ ${#selected[@]} -le 20 ]] || { echo 'risk_classes exceeds 20 classes' >&2; exit 2; }
for name in "${selected[@]}"; do
  source="app/src/androidTest/java/${name//.//}"
  [[ -f "$source.kt" || -f "$source.java" ]] || { echo "Missing instrumentation source: $name" >&2; exit 2; }
done
# Refuse data clearing unless this really is the disposable GitHub-hosted emulator.
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || { echo 'Preflight requires a GitHub-hosted runner' >&2; exit 2; }
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Preflight requires an emulator' >&2; exit 2; }
source .github/prepare-device-tests.sh
install_live_signals
mkdir -p "$reports"
printf 'sha=%s\napi=%s\nclasses=%s\n' "${GITHUB_SHA:-unknown}" "${TEST_API:-unknown}" "$classes" > "$reports/plan.txt"
printf 'preflight=running\nfull=not-started\n' > "$reports/status.txt"
set +e
if [[ "$live" == true ]]; then
  # Keep child ownership in this shell, not a tee pipeline's subshell.
  run_native preflight ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
  result=$?
  cp "$live_reports/preflight/observed/command.log" "$reports/gradle.log"
  log_result=$?
else
  run_native preflight ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$classes" 2>&1 | tee "$reports/gradle.log"
  statuses=("${PIPESTATUS[@]}")
  result=${statuses[0]}
  log_result=${statuses[1]}
fi
set -e
# Preserve the focused evidence before the full task replaces its report directories.
archive_result=0
for path in app/build/reports/androidTests app/build/outputs/androidTest-results app/build/outputs/connected_android_test_additional_output; do
  if [[ -d "$path" ]]; then
    if mkdir -p "$reports/$(dirname "$path")"; then
      cp -a "$path" "$reports/$path" || archive_result=$?
    else
      archive_result=$?
    fi
  fi
done
printf 'preflight_exit=%s\nfull=not-started\n' "$result" > "$reports/status.txt" || archive_result=$?
[[ "$result" == 0 ]] || exit "$result"
[[ "$log_result" == 0 && "$archive_result" == 0 ]] || { echo "Failed to preserve preflight evidence" >&2; exit 3; }
if [[ "$live" == true ]]; then verify_empty_preflight; fi
# Restore fresh-install app state before the complete suite; never touch other packages.
for package in io.github.anastaci1a.localica.debug io.github.anastaci1a.localica.debug.test; do
  # AGP may already uninstall a test APK after instrumentation; absent is fresh.
  installed=$(adb shell pm list packages "$package" | tr -d '\r')
  if grep -Fxq "package:$package" <<< "$installed"; then
    [[ $(adb shell pm clear "$package" | tr -d '\r') == Success ]] || { echo "Unable to reset $package" >&2; exit 3; }
  fi
done
printf 'preflight=passed\nfull=running\n' > "$reports/status.txt"
set +e
run_native full ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
result=$?
set -e
status_result=0
printf 'preflight=passed\nfull_exit=%s\n' "$result" > "$reports/status.txt" || status_result=$?
[[ "$result" == 0 ]] || exit "$result"
exit "$status_result"
