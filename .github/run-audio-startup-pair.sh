#!/usr/bin/env bash
# Four fixed invocations, never retry-until-pass. Fresh instrumentation on one boot;
# this does not claim a fresh OS/cache/process state for each invocation.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]]
[[ ! -e audio-pair-evidence && ! -L audio-pair-evidence ]]
mkdir audio-pair-evidence
printf 'sha=%s\nrun=%s\napi=%s\norder=queue,static,static,queue\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" "$TEST_API" > audio-pair-evidence/plan.txt
methods=(queueActorBindsRoundedAmbiguousConfigToDecodedRate staticPlaylistBindsRoundedAmbiguousConfigToDecodedRate staticPlaylistBindsRoundedAmbiguousConfigToDecodedRate queueActorBindsRoundedAmbiguousConfigToDecodedRate)
first_failure=0
for index in 0 1 2 3; do
  round="audio-pair-evidence/round-$index"
  mkdir "$round"
  export SELECTORS="io.github.anastaci1a.localica.QueueContinuousPlaybackTest#${methods[$index]}"
  set +e
  bash .github/run-selection-preconditions.sh > "$round/command.log" 2>&1
  result=$?
  set -e
  printf '%s\n' "$result" > "$round/exit-status.txt"
  # Preserve all original per-invocation output before the next Gradle task.
  for path in selection-evidence app/build/outputs/androidTest-results app/build/outputs/connected_android_test_additional_output app/build/reports/androidTests; do
    if [[ -e "$path" ]]; then
      mkdir -p "$round/$(dirname "$path")"
      mv "$path" "$round/$path"
    fi
  done
  if [[ "$result" != 0 && "$first_failure" == 0 ]]; then first_failure=$result; fi
  # An incomplete monitor/build/device failure is not a usable paired sample.
  [[ -f "$round/selection-evidence/result.txt" ]] || { if [[ "$first_failure" != 0 ]]; then exit "$first_failure"; else exit 3; fi; }
  grep -Fxq 'integrity_status=0' "$round/selection-evidence/result.txt" || exit 3
  [[ -d "$round/app/build/outputs/androidTest-results" ]] || exit 3
  python3 - "$round" "$SELECTORS" "$result" <<'PY'
import json,sys,xml.etree.ElementTree as E
from pathlib import Path
root,selector,code=Path(sys.argv[1]),sys.argv[2],int(sys.argv[3])
monitor=json.loads((root/'selection-evidence/native/supervision.json').read_text())
assert monitor['monitoring_complete'] is True and monitor['supervisor_error'] is None
assert monitor['publisher_exit']==0 and monitor['observer_exit']==code
observed=json.loads((root/'selection-evidence/native/observed/manifest.json').read_text())
assert observed['child_returncode']==observed['observer_exit_status']==code
assert observed['observer_error'] is None and observed['cancel_signal'] is None
assert observed['status']==('failed' if code else 'completed')
cases=[c for p in (root/'app/build/outputs/androidTest-results').rglob('*.xml') for c in E.parse(p).getroot().iter('testcase')]
assert len(cases)==1
c=cases[0]
assert c.attrib['classname']+'#'+c.attrib['name']==selector
assert c.find('skipped') is None and c.find('error') is None
assert (c.find('failure') is not None)==(code!=0)
PY
  # Test assertion failures remain failures; later fixed samples cannot clear them.
done
printf 'first_failure=%s\n' "$first_failure" > audio-pair-evidence/result.txt
exit "$first_failure"
