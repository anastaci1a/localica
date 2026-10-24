#!/usr/bin/env bash
# Hosted disposable emulator only; never used by compatibility's preserved-data phases.
set -euo pipefail
classes=${RISK_CLASSES:-}
reports="$PWD/preflight-reports"
if [[ -z "$classes" ]]; then
  source .github/prepare-device-tests.sh
  # Keep the sourced EXIT restoration trap alive; preserve Gradle failure status.
  set +e
  ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
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
mkdir -p "$reports"
printf 'sha=%s\napi=%s\nclasses=%s\n' "${GITHUB_SHA:-unknown}" "${TEST_API:-unknown}" "$classes" > "$reports/plan.txt"
printf 'preflight=running\nfull=not-started\n' > "$reports/status.txt"
set +e
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$classes" 2>&1 | tee "$reports/gradle.log"
statuses=("${PIPESTATUS[@]}")
result=${statuses[0]}
log_result=${statuses[1]}
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
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
result=$?
set -e
status_result=0
printf 'preflight=passed\nfull_exit=%s\n' "$result" > "$reports/status.txt" || status_result=$?
[[ "$result" == 0 ]] || exit "$result"
exit "$status_result"
