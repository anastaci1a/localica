#!/usr/bin/env bash
# Disposable hosted emulator only; NEVER run this preparation on a physical phone.
set -euo pipefail
umask 0002
mode=${1:?generate or functional}
[[ $mode == generate || $mode == functional ]]
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
pkg=io.github.anastaci1a.localica.debug
# Preserve the seeded fixture across isolated and aggregate connected-test tasks.
gradle=(./gradlew -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon --max-workers=2)
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
    target=performance-apks/candidate.apk
fi
adb install -r "$target"
node tools/performance/macrobenchmark/fixture.mjs "$RUNNER_TEMP/performance-fixture"
tar -cf "$RUNNER_TEMP/performance-fixture.tar" -C "$RUNNER_TEMP/performance-fixture/scale" .
adb push "$RUNNER_TEMP/performance-fixture.tar" /data/local/tmp/localica-986-fixture.tar
uid=$(adb shell stat -c %u "/data/user/0/$pkg" | tr -d '\r')
[[ $uid =~ ^[0-9]+$ ]]
adb shell "am force-stop $pkg; tar -xf /data/local/tmp/localica-986-fixture.tar -C /data/user/0/$pkg; chown -R $uid:$uid /data/user/0/$pkg; restorecon -R /data/user/0/$pkg"
if [[ $mode == generate ]]; then
    "${gradle[@]}" :app:generateBaselineProfile -Pandroid.testInstrumentationRunnerArguments.class=io.github.anastaci1a.localica.benchmark.BaselineProfileGenerator
else
    adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n "$pkg/androidx.profileinstaller.ProfileInstallReceiver"
    adb shell cmd package compile -f -m speed-profile "$pkg"
    adb shell dumpsys package dexopt > performance-dexopt.txt
    adb install -r benchmark/build/outputs/apk/benchmarkDistribution/benchmark-benchmarkDistribution.apk
    adb shell pm list instrumentation > performance-instrumentation.txt
    for scenario in ${BENCHMARK_SCENARIOS:?}; do
        node -e 'const p=require("./tools/performance/macrobenchmark/scenarios.json");if(!p.scenarios.some(s=>s.id===process.argv[1]))process.exit(1)' "$scenario"
        result="performance-result-$scenario.txt"
        adb shell am instrument -w -r -e class io.github.anastaci1a.localica.benchmark.CostMapBenchmark -e scenario "$scenario" -e androidx.benchmark.suppressErrors EMULATOR -e androidx.benchmark.output.enable true io.github.anastaci1a.localica.benchmark/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$result"
        grep -Fq 'OK (1 test)' "$result"
        grep -Fq "INSTRUMENTATION_STATUS: test=scenario[$scenario]" "$result"
        ! grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[12]|INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=' "$result"
        grep -Fq 'frameDurationCpuMs' "$result"
        if [[ $scenario == cold-start ]]; then grep -Fq 'timeToInitialDisplayMs' "$result"; fi
        mkdir -p "performance-results/$scenario"
        adb pull /sdcard/Android/media/io.github.anastaci1a.localica.benchmark "performance-results/$scenario/"
    done
    rg --files performance-results -g '*.perfetto-trace' -0 | xargs -0 -r sha256sum > performance-traces.sha256
fi
