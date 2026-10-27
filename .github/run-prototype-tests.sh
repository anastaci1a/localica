#!/usr/bin/env bash
# Fast-only hosted prototype checks. Never use this result as release certification.
set -euo pipefail
classes=${AFFECTED_CLASSES:-}
required=io.github.anastaci1a.localica.SearchImeMotionTest
focus_regression=io.github.anastaci1a.localica.ListSearchTest
contrast_regression=io.github.anastaci1a.localica.FrostedSurfaceTest
[[ -n "$classes" && ${#classes} -le 4096 ]] || { echo 'Supply nonempty affected behavior classes (maximum 4096 characters)' >&2; exit 2; }
pattern='^io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*(,io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*)*$'
[[ "$classes" =~ $pattern ]] || { echo 'Invalid affected class selector' >&2; exit 2; }
IFS=, read -r -a selected <<< "$classes"
[[ ${#selected[@]} -le 51 ]] || { echo 'At most 51 affected classes plus the three required regression classes' >&2; exit 2; }
[[ -f .github/prototype-risk-classes.txt ]] || { echo 'Missing reviewed class inventory' >&2; exit 2; }
declare -A seen=()
affected=0
for name in "${selected[@]}" "$required" "$focus_regression" "$contrast_regression"; do
  grep -Fxq "$name" .github/prototype-risk-classes.txt || { echo "Class is not in the reviewed inventory: $name" >&2; exit 2; }
  [[ "$name" == "$required" || "$name" == "$focus_regression" || "$name" == "$contrast_regression" ]] || affected=1
  seen["$name"]=1
done
(( affected )) || { echo 'Name affected behavior coverage in addition to the mandatory IME regression' >&2; exit 2; }
# Stable selectors make evidence comparable without changing instrumentation order.
selector=$(printf '%s\n' "${!seen[@]}" | LC_ALL=C sort | paste -sd, -)
[[ ${#selector} -le 4096 ]] || { echo 'Combined selector exceeds 4096 characters' >&2; exit 2; }
[[ ${TEST_API:-} == 26 || ${TEST_API:-} == 36 ]] || { echo 'TEST_API must be 26 or 36' >&2; exit 2; }
[[ ${GITHUB_SHA:-} =~ ^[0-9a-f]{40}$ ]] || { echo 'Require exact candidate SHA' >&2; exit 2; }
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]] || { echo 'Checkout SHA differs from candidate' >&2; exit 2; }
[[ -f .github/test-evidence/observe.py ]] || { echo 'Required test observer is not integrated' >&2; exit 2; }
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || { echo 'Prototype runner requires the hosted disposable emulator' >&2; exit 2; }
[[ ! -e prototype-evidence ]] || { echo 'Refusing stale prototype evidence' >&2; exit 2; }
mkdir prototype-evidence
printf '%s\n' "$selector" > prototype-evidence/selectors.txt
python3 .github/test-evidence/source_methods.py --classes "$selector" --output prototype-evidence/expected-methods.json
printf 'candidate_sha=%s\napi=%s\nrelease_certified=false\n' "$GITHUB_SHA" "$TEST_API" > prototype-evidence/context.txt
# These APKs are independently compiled test inputs, NOT the canonical signed delivery APK.
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > prototype-evidence/test-input-apks.sha256
[[ -z $(find app/build/outputs/androidTest-results -name '*.xml' -print 2>/dev/null) ]] || { echo 'Refusing stale instrumentation XML' >&2; exit 2; }
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Require an explicit emulator serial' >&2; exit 2; }
actual_api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$actual_api" == "$TEST_API" ]] || { echo "Actual SDK $actual_api differs from requested $TEST_API" >&2; exit 2; }
printf 'serial=%s\nactual_api=%s\n' "$serial" "$actual_api" >> prototype-evidence/context.txt
source .github/prepare-device-tests.sh
# Retain the setup's EXIT restoration trap; forward cancellation to the observer,
# which owns its child process group, before restoration runs.
child=''
stop_child() {
  local sig=$1 code=$2
  trap '' INT TERM
  if [[ -n "$child" ]]; then kill -s "$sig" "$child" 2>/dev/null || true; wait "$child" || true; fi
  exit "$code"
}
trap 'stop_child TERM 143' TERM
trap 'stop_child INT 130' INT
set +e
python3 .github/test-evidence/supervise.py --output prototype-evidence/live --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" --sha "$GITHUB_SHA" --api "$TEST_API" --lane prototype -- ./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$selector" &
child=$!
wait "$child"
result=$?
set -e
[[ "$result" == 0 ]] || exit "$result"
python3 - "$selector" "$required,$focus_regression,$contrast_regression" <<'PYXML'
import hashlib, json, pathlib, re, sys, xml.etree.ElementTree as ET
if not __debug__:
    raise SystemExit("Optimized Python cannot disable prototype reconciliation")
root = pathlib.Path('prototype-evidence')
observed = json.loads((root/'live/observed/manifest.json').read_text())
assert observed['status'] == 'completed' and observed['child_returncode'] == 0
assert observed['observer_exit_status'] == 0 and observed['observed_failure_lines'] == 0
assert not (root/'live/observed/events.jsonl').read_text().strip(), 'Observed failure contradicts a successful exit'
selected = set(sys.argv[1].split(','))
counts = {c: {'passed': 0, 'failed': 0, 'errors': 0, 'skipped': 0} for c in selected}
files = sorted(pathlib.Path('app/build/outputs/androidTest-results').rglob('*.xml'))
assert files, 'No final instrumentation XML'
identities = set()
methods = {}
source_bytes = (root/'expected-methods.json').read_bytes()
source_inventory = json.loads(source_bytes)
assert set(source_inventory['selectors']) == selected
expected_methods = set(source_inventory['runtime_methods'])
required = set(sys.argv[2].split(','))
# AGP's observed XML serializes assumptions as <skipped />. Its EXISTING per-method
# logcat carries the reason. Never infer that reason from a count or source predicate.
package = 'io.github.anastaci1a.localica.'
profile_reasons = {
    'RepeatOptionsEnvelopeTest#native360x640Font2SectionsExposeEveryControlAndStageDrafts': 'Exact native window profile requires sufficient display space and actual Android font scale 2',
    'RepeatOptionsEnvelopeTest#native400x800Font2SectionsExposeEveryControlAndStageDrafts': 'Exact native window profile requires sufficient display space and actual Android font scale 2',
    'RepeatOptionsEnvelopeTest#native360x640PhoneFont2SectionsFitSafeInsetsWithoutWindowResize': 'Requires the lead-configured 360x640 phone/font2 focused run',
    'RepeatOptionsEnvelopeTest#native400x800PhoneFont2SectionsFitSafeInsetsWithoutWindowResize': 'Requires the lead-configured 400x800 phone/font2 focused run',
    'DialogChromeLayoutTest#native360x640Font2RetainsSharedChromeAndEveryRepeatControl': 'Requires sufficient display space and actual Android font scale 2',
    'DialogChromeLayoutTest#native400x800Font2RetainsSharedChromeAndEveryRepeatControl': 'Requires sufficient display space and actual Android font scale 2',
    'TagEditorChromeLayoutTest#native360x640Font2KeepsSharedChromeAndScrollableFullSizeFields': 'Requires sufficient display space and the requested actual system font scale',
}
qualified_skips = []
def qualify_skip(name, method, skipped, xml_file):
    identity = name+'#'+method
    api = str(observed['api'])
    expected = profile_reasons.get(identity.removeprefix(package)) if identity.startswith(package) else None
    if identity == package+'PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers' and api == '26':
        expected = 'got: <false>, expected: is <true>'
    if expected is None or api not in ('26', '36'):
        raise ValueError('Unqualified skipped identity/API: '+identity+' API'+api)
    logs = sorted(pathlib.Path('app/build/outputs/androidTest-results').rglob('logcat-'+name+'-'+method+'.txt'))
    if len(logs) != 1 or logs[0].is_symlink() or logs[0].stat().st_size > 4*1024*1024:
        raise ValueError('Require one bounded original per-method assumption log: '+identity)
    text = logs[0].read_text(encoding='utf-8')
    marker = method+'('+name+')'
    if text.count('TestRunner: started: '+marker) != 1 or text.count('TestRunner: assumption failed: '+marker) != 1:
        raise ValueError('Assumption log lacks unique matching method boundaries: '+identity)
    reasons = re.findall(r'TestRunner: org\.junit\.AssumptionViolatedException: ([^\r\n]*)', text)
    if reasons != [expected]:
        raise ValueError('Missing, ambiguous or wrong observed skip reason: '+identity)
    # If another adapter supplies XML reason text, it must not contradict the paired log.
    message = skipped.attrib.get('message', '')
    detail = (skipped.text or '').strip()
    if message not in ('', expected) or detail not in ('', expected, 'org.junit.AssumptionViolatedException: '+expected):
        raise ValueError('Contradictory XML assumption reason: '+identity)
    qualified_skips.append({'id': identity, 'api': api, 'reason': reasons[0], 'evidence': str(logs[0]),
        'evidence_sha256': hashlib.sha256(logs[0].read_bytes()).hexdigest(), 'xml': str(xml_file),
        'qualification': 'Observed assumption only; not a passed profile or optical proof'})
for file in files:
    tree = ET.parse(file)
    suites = [tree.getroot()] if tree.getroot().tag == 'testsuite' else tree.getroot().findall('.//testsuite')
    assert suites, 'No test suites in '+str(file)
    for suite in suites:
        cases = suite.findall('testcase')
        assert int(suite.attrib['tests']) == len(cases), 'Incomplete XML test count'
        for case in cases:
            name = case.attrib['classname']
            assert name in selected, 'Unexpected class '+name
            identity = (name, case.attrib['name'])
            assert identity not in identities, 'Duplicate XML test '+str(identity)
            identities.add(identity)
            outcome = 'failed' if case.find('failure') is not None else 'errors' if case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            if outcome == 'skipped':
                qualify_skip(name, case.attrib['name'], case.find('skipped'), file)
            counts[name][outcome] += 1
            methods[name+'#'+case.attrib['name']] = outcome
        for attr, tag in [('failures','failure'), ('errors','error'), ('skipped','skipped')]:
            if attr in suite.attrib:
                assert int(suite.attrib[attr]) == sum(c.find(tag) is not None for c in cases), 'Inconsistent XML '+attr
assert set(methods) == expected_methods, 'Missing or extra source-declared methods'
assert all(counts[c]['skipped'] == 0 for c in required), 'Required regression methods cannot skip'
result = {'qualified_skips': qualified_skips, 'methods': methods, 'source_inventory_sha256': hashlib.sha256(source_bytes).hexdigest(), 'required_selectors': sorted(required), 'schema_version': 1, 'sha': observed['sha'], 'api': observed['api'], 'selectors': sorted(selected), 'counts': counts, 'xml_files': [str(p) for p in files], 'release_certified': False}
(root/'reconciled-tests.json').write_text(json.dumps(result, indent=2)+'\n')
assert all(v['passed'] > 0 and v['failed'] == 0 and v['errors'] == 0 for v in counts.values()), 'Required class failed or had no passing tests'
PYXML
