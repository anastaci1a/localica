#!/usr/bin/env bash
# Isolated original/cached trials in one disposable hosted emulator and one APK pair.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]]
[[ ! -e browse-face-cost ]]
source .github/prepare-device-tests.sh
mkdir browse-face-cost
printf 'sha=%s\nrun=%s\nattempt=%s\napi=%s\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" "$TEST_API" > browse-face-cost/identity.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > browse-face-cost/input-apks.sha256
adb install -r app/build/outputs/apk/debug/app-debug.apk > browse-face-cost/app-install.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > browse-face-cost/test-install.txt
grep -qx Success browse-face-cost/app-install.txt
grep -qx Success browse-face-cost/test-install.txt
for trial in equivalence-original equivalence-cached A1-original B1-cached B2-cached A2-original; do
  mode=${trial##*-}
  trace=false
  [[ $trial != equivalence-* ]] || trace=true
  mkdir "browse-face-cost/$trial"
  python3 - "$trial" "$mode" "$trace" <<'PY'
import json
from pathlib import Path
import subprocess
import sys
import time

trial, mode, trace = sys.argv[1:]
root = Path('browse-face-cost') / trial
command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
           'io.github.anastaci1a.localica.BrowseOptionsFaceContractTest',
           '-e', 'browseFaceMode', mode, '-e', 'browseFaceTrace', trace,
           'io.github.anastaci1a.localica.debug.test/androidx.test.runner.AndroidJUnitRunner']
started = time.perf_counter_ns()
with (root / 'instrumentation.txt').open('x') as output:
    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
elapsed = time.perf_counter_ns() - started
(root / 'command.json').write_text(json.dumps({'command': command, 'returncode': result.returncode,
    'elapsed_nanoseconds': elapsed, 'mode': mode, 'trace': trace}, indent=2) + '\n')
sys.exit(result.returncode)
PY
  python3 .github/compatibility/check-instrumentation.py "browse-face-cost/$trial/instrumentation.txt" 2
  adb logcat -d > "browse-face-cost/$trial/logcat.txt"
  sha256sum --check browse-face-cost/input-apks.sha256 > "browse-face-cost/$trial/integrity.txt"
done
python3 - <<'PY'
import json
from pathlib import Path
import re

root = Path('browse-face-cost')
def traces(mode):
    text = (root / ('equivalence-' + mode) / 'instrumentation.txt').read_text()
    rows = re.findall(r'^INSTRUMENTATION_RESULT: (browse_face_trace_[^=]+)=(.*)$', text, re.M)
    assert len(rows) == 6 and len(dict(rows)) == 6, 'Require all six font/width assertion-input traces'
    return dict(rows)
original, cached = traces('original'), traces('cached')
assert original == cached, 'Original and cached assertion-input traces differ'
(root / 'equivalence.json').write_text(json.dumps({'equal': True, 'phase_count': 6,
    'original': original, 'cached': cached,
    'limits': 'One assertion-input equivalence trial per mode; not full-suite speedup proof.'}, indent=2) + '\n')
PY
