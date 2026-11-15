#!/usr/bin/env bash
# Disposable hosted emulator only; NEVER run this preparation on a physical phone.
set -euo pipefail
umask 0002
mode=${1:?generate or functional}
[[ $mode == generate || $mode == functional ]]
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
pkg=io.github.anastaci1a.localica.debug
adb root
adb wait-for-device
adb shell wm size 720x1280
adb shell wm density 280
python3 .github/emulator-readiness.py --normalize-navigation
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 1.0
done
if [[ $mode == generate ]]; then
    target=app/build/outputs/apk/nonMinifiedDistribution/app-nonMinifiedDistribution.apk
else
    target=app/build/outputs/apk/distribution/app-distribution.apk
fi
adb install -r "$target"
node tools/performance/macrobenchmark/fixture.mjs "$RUNNER_TEMP/performance-fixture"
tar -cf "$RUNNER_TEMP/performance-fixture.tar" -C "$RUNNER_TEMP/performance-fixture" .
adb push "$RUNNER_TEMP/performance-fixture.tar" /data/local/tmp/localica-986-fixture.tar
uid=$(adb shell dumpsys package "$pkg" | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1 | tr -d '\r')
[[ $uid =~ ^[0-9]+$ ]]
adb shell "am force-stop $pkg; tar -xf /data/local/tmp/localica-986-fixture.tar -C /data/user/0/$pkg; chown -R $uid:$uid /data/user/0/$pkg; restorecon -R /data/user/0/$pkg"
if [[ $mode == generate ]]; then
    task=:benchmark:connectedNonMinifiedDistributionAndroidTest
    # New methods run in isolation before the full generator. One scenario suffices
    # to establish the journey method and shell injector before the complete set.
    ./gradlew "$task" -Pandroid.testInstrumentationRunnerArguments.class=io.github.anastaci1a.localica.benchmark.BaselineProfileGenerator#startup --no-daemon --max-workers=2
    ./gradlew "$task" -Pandroid.testInstrumentationRunnerArguments.class=io.github.anastaci1a.localica.benchmark.BaselineProfileGenerator#journeys -Pandroid.testInstrumentationRunnerArguments.scenario=library-list-fling --no-daemon --max-workers=2
    ./gradlew :app:generateDistributionBaselineProfile --no-daemon --max-workers=2
else
    adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n "$pkg/androidx.profileinstaller.ProfileInstallReceiver"
    adb shell cmd package compile -f -m speed-profile "$pkg"
    adb shell dumpsys package dexopt > performance-dexopt.txt
    ./gradlew :benchmark:connectedBenchmarkDistributionAndroidTest -Pandroid.testInstrumentationRunnerArguments.scenario=library-list-fling -Pandroid.testInstrumentationRunnerArguments.class=io.github.anastaci1a.localica.benchmark.CostMapBenchmark -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR --no-daemon --max-workers=2
    ./gradlew :benchmark:connectedBenchmarkDistributionAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.anastaci1a.localica.benchmark.CostMapBenchmark -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR --no-daemon --max-workers=2
fi
