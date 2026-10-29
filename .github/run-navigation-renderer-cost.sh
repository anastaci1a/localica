#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == --trials ]]; then
  for trial in A1-Modern B1-Legacy B2-Legacy A2-Modern; do
    mode=${trial##*-}
    mkdir "renderer-cost/$trial"
    adb logcat -c
    python3 - "$trial" "$mode" <<'PY'
import json
from pathlib import Path
import subprocess
import sys
import time

trial, mode = sys.argv[1:]
root = Path('renderer-cost') / trial
command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
           'io.github.anastaci1a.localica.TabResetTest,io.github.anastaci1a.localica.GalleryNavigationTest',
           '-e', 'rendererCostMode', mode, '-e', 'fixtureCostTrace', 'true',
           'io.github.anastaci1a.localica.debug.test/androidx.test.runner.AndroidJUnitRunner']
started = time.perf_counter_ns()
with (root / 'instrumentation.txt').open('x') as output:
    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
elapsed = time.perf_counter_ns() - started
(root / 'command.json').write_text(json.dumps({'command': command, 'returncode': result.returncode,
    'elapsed_nanoseconds': elapsed, 'mode': mode}, indent=2) + '\n')
sys.exit(result.returncode)
PY
    adb logcat -d > "renderer-cost/$trial/logcat.txt"
    sha256sum --check renderer-cost/input-apks.sha256 > "renderer-cost/$trial/integrity.txt"
    python3 .github/compatibility/check-instrumentation.py "renderer-cost/$trial/instrumentation.txt" 2
    python3 - "$trial" "$mode" <<'PY'
from pathlib import Path
import sys

trial, mode = sys.argv[1:]
text = (Path('renderer-cost') / trial / 'instrumentation.txt').read_text()
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
[[ ! -e renderer-cost ]]
source .github/prepare-device-tests.sh
mkdir renderer-cost
printf 'sha=%s\nrun=%s\nattempt=%s\napi=36\norder=A1-Modern,B1-Legacy,B2-Legacy,A2-Modern\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" > renderer-cost/identity.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > renderer-cost/input-apks.sha256
adb install -r app/build/outputs/apk/debug/app-debug.apk > renderer-cost/app-install.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > renderer-cost/test-install.txt
grep -qx Success renderer-cost/app-install.txt
grep -qx Success renderer-cost/test-install.txt
set +e
python3 .github/test-evidence/supervise.py --output renderer-cost/supervision --sha "$GITHUB_SHA" --api 36 --lane native --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" -- bash .github/run-navigation-renderer-cost.sh --trials
result=$?
set -e
integrity=0
sha256sum --check renderer-cost/input-apks.sha256 || integrity=$?
printf 'test_status=%s\nintegrity_status=%s\n' "$result" "$integrity" > renderer-cost/result.txt
if [[ "$result" -ne 0 ]]; then exit "$result"; fi
exit "$integrity"
