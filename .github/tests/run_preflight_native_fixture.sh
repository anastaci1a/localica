#!/usr/bin/env bash
# Only the three-test application in a disposable GitHub-hosted emulator.
set -euo pipefail
root=$PWD
fixture=$PWD/preflight-native-fixture
risk='io.github.anastaci1a.localica.PreflightRiskOneTest,io.github.anastaci1a.localica.PreflightRiskTwoTest'
all="$risk,io.github.anastaci1a.localica.PreflightRemainderTest"
cd "$fixture"
mkdir option-evidence
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > option-evidence/test-input-apks.sha256
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$risk" 2>&1 | tee option-evidence/risk-gradle.log
mv app/build/outputs/androidTest-results option-evidence/option-risk
sha256sum --check option-evidence/test-input-apks.sha256
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$all" "-Pandroid.testInstrumentationRunnerArguments.notClass=$risk" 2>&1 | tee option-evidence/remainder-gradle.log
mv app/build/outputs/androidTest-results option-evidence/option-remainder
sha256sum --check option-evidence/test-input-apks.sha256
python3 "$root/.github/tests/verify_preflight_native_fixture.py" option-evidence option | tee option-evidence/verified.json
RISK_CLASSES="$risk" LOCALICA_LIVE_EVIDENCE=true bash .github/run-device-tests.sh
python3 "$root/.github/tests/verify_preflight_native_fixture.py" . union | tee native-fixture-verified.json
sha256sum --check option-evidence/test-input-apks.sha256
