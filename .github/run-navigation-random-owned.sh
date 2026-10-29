#!/usr/bin/env bash
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]]
[[ ! -e selection-evidence && ! -e app/build/outputs/androidTest-results ]]
source .github/prepare-device-tests.sh
mkdir selection-evidence
printf 'sha=%s\nrun=%s\napi=%s\nselectors=%s\nowned_bridge_contracts_only=true\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" "$TEST_API" "$SELECTORS" > selection-evidence/identity.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > selection-evidence/input-apks.sha256
set +e
python3 .github/test-evidence/supervise.py --output selection-evidence/native --sha "$GITHUB_SHA" --api "$TEST_API" --lane native --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" -- ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$SELECTORS"
result=$?
set -e
integrity=0
sha256sum --check selection-evidence/input-apks.sha256 || integrity=$?
printf 'test_status=%s\nintegrity_status=%s\n' "$result" "$integrity" > selection-evidence/result.txt
if [[ "$result" -ne 0 ]]; then exit "$result"; fi
if [[ "$integrity" -ne 0 ]]; then exit "$integrity"; fi
