#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == --trials ]]; then
  for trial in A1-original B1-deduplicated B2-deduplicated A2-original; do
    mode=${trial##*-}
    mkdir "artist-range-cost/$trial"
    sha256sum --check artist-range-cost/input-apks.sha256 > "artist-range-cost/$trial/integrity-before.txt"
    # Android 8 can reject clearing an active main buffer. Delimit our own
    # disposable trial instead; keep the unmodified raw dump for independent review.
    trial_marker="trial-start=$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT:$trial"
    printf '%s\n' "$trial_marker" > "artist-range-cost/$trial/log-marker.txt"
    adb shell log -t LocalicaArtistRangeCost "$trial_marker"
    command_status=0
    python3 - "$trial" "$mode" <<'PY' || command_status=$?
import json
from pathlib import Path
import subprocess
import sys
import time

trial, mode = sys.argv[1:]
root = Path('artist-range-cost') / trial
command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
           'io.github.anastaci1a.localica.ArtistAlbumFilterRangeTest',
           '-e', 'artistRangeSettleIdle', mode,
           'io.github.anastaci1a.localica.debug.test/androidx.test.runner.AndroidJUnitRunner']
started = time.perf_counter_ns()
with (root / 'instrumentation.txt').open('x') as output:
    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
elapsed = time.perf_counter_ns() - started
(root / 'command.json').write_text(json.dumps({'command': command, 'returncode': result.returncode,
    'elapsed_nanoseconds': elapsed, 'mode': mode}, indent=2) + '\n')
sys.exit(result.returncode)
PY
    log_status=0
    adb logcat -d > "artist-range-cost/$trial/logcat-raw.txt" || log_status=$?
    if [[ $log_status -eq 0 ]]; then
      python3 - "$trial" "$trial_marker" <<'PY' || log_status=$?
from pathlib import Path
import re
import sys

trial, marker = sys.argv[1:]
root = Path('artist-range-cost') / trial
lines = (root / 'logcat-raw.txt').read_text().splitlines(keepends=True)
pattern = re.compile(r'LocalicaArtistRangeCost\s*:\s*' + re.escape(marker) + r'\s*$')
matches = [i for i, line in enumerate(lines) if pattern.search(line)]
assert len(matches) == 1, 'Require exactly one retained trial-start marker; no inferred log boundary'
(root / 'logcat.txt').write_text(''.join(lines[matches[0] + 1:]))
PY
    fi
    integrity_status=0
    sha256sum --check artist-range-cost/input-apks.sha256 > "artist-range-cost/$trial/integrity-after.txt" || integrity_status=$?
    checker_status=0
    python3 .github/compatibility/check-instrumentation.py "artist-range-cost/$trial/instrumentation.txt" 3 || checker_status=$?
    printf 'command_status=%s\nchecker_status=%s\nlog_status=%s\nintegrity_status=%s\n' "$command_status" "$checker_status" "$log_status" "$integrity_status" > "artist-range-cost/$trial/result.txt"
    # Preserve the actual command/test failure before evidence or checksum failures.
    for status in "$command_status" "$checker_status" "$log_status" "$integrity_status"; do
      if [[ $status -ne 0 ]]; then exit "$status"; fi
    done
    python3 - "$trial" "$mode" <<'PY'
from pathlib import Path
import re
import sys

trial, mode = sys.argv[1:]
text = (Path('artist-range-cost') / trial / 'logcat.txt').read_text()
assert len(re.findall(r'ArtistRangeFixture: phase=settle-idle-mode-' + mode + r' uptime=', text)) == 3, 'Require three fixture mode receipts'
PY
  done
  exit 0
fi
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(adb shell getprop ro.build.version.sdk | tr -d '\r') == "$TEST_API" ]]
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]]
[[ ! -e artist-range-cost ]]
source .github/prepare-device-tests.sh
mkdir artist-range-cost
printf 'sha=%s\nrun=%s\nattempt=%s\napi=%s\norder=A1-original,B1-deduplicated,B2-deduplicated,A2-original\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" "$TEST_API" > artist-range-cost/identity.txt
adb logcat -G 16M
adb logcat -g > artist-range-cost/logcat-buffer-config.txt
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > artist-range-cost/input-apks.sha256
adb install -r app/build/outputs/apk/debug/app-debug.apk > artist-range-cost/app-install.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > artist-range-cost/test-install.txt
grep -qx Success artist-range-cost/app-install.txt
grep -qx Success artist-range-cost/test-install.txt
set +e
python3 .github/test-evidence/supervise.py --output artist-range-cost/supervision --sha "$GITHUB_SHA" --api "$TEST_API" --lane native --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" -- bash .github/run-artist-range-settle-cost.sh --trials
result=$?
set -e
integrity=0
sha256sum --check artist-range-cost/input-apks.sha256 || integrity=$?
printf 'test_status=%s\nintegrity_status=%s\n' "$result" "$integrity" > artist-range-cost/result.txt
if [[ "$result" -ne 0 ]]; then exit "$result"; fi
exit "$integrity"
