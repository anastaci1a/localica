#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == --trials ]]; then
  for trial in diagnostic-Legacy; do
    mode=${trial##*-}
    mkdir "legacy-thread-probe/$trial"
    adb logcat -c
    python3 - "$trial" "$mode" <<'PY'
import json
from pathlib import Path
import subprocess
import sys
import time

trial, mode = sys.argv[1:]
root = Path('legacy-thread-probe') / trial
command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
           'io.github.anastaci1a.localica.TabResetTest,io.github.anastaci1a.localica.GalleryNavigationTest',
           '-e', 'rendererCostMode', mode, '-e', 'fixtureCostTrace', 'true',
           '-e', 'fixtureThreadDiagnostics', 'true',
           'io.github.anastaci1a.localica.debug.test/androidx.test.runner.AndroidJUnitRunner']
started = time.perf_counter_ns()
with (root / 'instrumentation.txt').open('x') as output:
    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
elapsed = time.perf_counter_ns() - started
(root / 'command.json').write_text(json.dumps({'command': command, 'returncode': result.returncode,
    'elapsed_nanoseconds': elapsed, 'mode': mode}, indent=2) + '\n')
sys.exit(result.returncode)
PY
    adb logcat -d > "legacy-thread-probe/$trial/logcat.txt"
    sha256sum --check legacy-thread-probe/input-apks.sha256 > "legacy-thread-probe/$trial/integrity.txt"
    python3 .github/compatibility/check-instrumentation.py "legacy-thread-probe/$trial/instrumentation.txt" 2
    python3 - "$trial" "$mode" <<'PY'
from pathlib import Path
import sys

trial, mode = sys.argv[1:]
text = (Path('legacy-thread-probe') / trial / 'instrumentation.txt').read_text()
receipt = f'INSTRUMENTATION_RESULT: renderer_cost_backend=requested={mode},effective={mode},epoch=0,api=36'
assert receipt in text.splitlines(), 'Missing actual requested/effective backend receipt'
PY
  done
  exit 0
fi
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(adb shell getprop ro.build.version.sdk | tr -d '\r') == 36 ]]
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]]
[[ ! -e legacy-thread-probe ]]
source .github/prepare-device-tests.sh
mkdir legacy-thread-probe
printf 'sha=%s\nrun=%s\nattempt=%s\napi=36\norder=diagnostic-Legacy\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" > legacy-thread-probe/identity.txt
adb logcat -G 16M
adb logcat -g > legacy-thread-probe/logcat-buffer-config.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > legacy-thread-probe/input-apks.sha256
adb install -r app/build/outputs/apk/debug/app-debug.apk > legacy-thread-probe/app-install.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > legacy-thread-probe/test-install.txt
grep -qx Success legacy-thread-probe/app-install.txt
grep -qx Success legacy-thread-probe/test-install.txt
set +e
python3 .github/test-evidence/supervise.py --output legacy-thread-probe/supervision --sha "$GITHUB_SHA" --api 36 --lane native --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" -- bash .github/run-navigation-legacy-thread-probe.sh --trials
result=$?
set -e
integrity=0
sha256sum --check legacy-thread-probe/input-apks.sha256 || integrity=$?
printf 'test_status=%s\nintegrity_status=%s\n' "$result" "$integrity" > legacy-thread-probe/result.txt
if [[ "$result" -ne 0 ]]; then exit "$result"; fi
exit "$integrity"
