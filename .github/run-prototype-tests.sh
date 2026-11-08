#!/usr/bin/env bash
# Fast-only hosted prototype checks. Never use this result as release certification.
set -euo pipefail
repetitions=${PROTOTYPE_REPEAT:-1}
[[ "$repetitions" =~ ^([1-9]|[12][0-9]|30)$ ]] || { echo 'PROTOTYPE_REPEAT must be 1 through 30' >&2; exit 2; }
method_iteration=${PROTOTYPE_METHOD_ITERATION:-false}
[[ "$method_iteration" == true || "$method_iteration" == false ]] || exit 2
if [[ "$method_iteration" == false ]] && { (( repetitions > 1 )) || [[ -n "${PROTOTYPE_METHODS:-}" ]]; }; then
  exec python3 .github/run-prototype-repeat.py
fi
classes=${AFFECTED_CLASSES:-}
required=io.github.anastaci1a.localica.SearchImeMotionTest
focus_regression=io.github.anastaci1a.localica.ListSearchTest
contrast_regression=io.github.anastaci1a.localica.FrostedSurfaceTest
if [[ "$method_iteration" == true ]]; then
  [[ -n "${PROTOTYPE_METHODS:-}" ]] || exit 2
  required=''; focus_regression=''; contrast_regression=''
fi
[[ -n "$classes" && ${#classes} -le 4096 ]] || { echo 'Supply nonempty affected behavior classes (maximum 4096 characters)' >&2; exit 2; }
pattern='^io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*(,io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*)*$'
[[ "$classes" =~ $pattern ]] || { echo 'Invalid affected class selector' >&2; exit 2; }
IFS=, read -r -a selected <<< "$classes"
[[ ${#selected[@]} -le 73 ]] || { echo 'At most 73 affected classes plus the three required regression classes' >&2; exit 2; }
[[ -f .github/prototype-risk-classes.txt ]] || { echo 'Missing reviewed class inventory' >&2; exit 2; }
declare -A seen=()
affected=0
for name in "${selected[@]}" "$required" "$focus_regression" "$contrast_regression"; do
  [[ -n "$name" ]] || continue
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
# A disposable emulator only: GitHub-hosted, or the locked-down single-job self-hosted JIT runner (#592).
[[ ${GITHUB_ACTIONS:-} == true ]] || { echo 'Prototype runner requires the hosted disposable emulator' >&2; exit 2; }
[[ ${RUNNER_ENVIRONMENT:-} == github-hosted || ( ${RUNNER_ENVIRONMENT:-} == self-hosted && ${LOCALICA_JIT_RUNNER:-} == 1 && ${LOCALICA_EPHEMERAL_RUNNER:-} == localica-kvm ) ]] || { echo 'Prototype runner requires the hosted disposable emulator' >&2; exit 2; }
[[ ! -e prototype-evidence ]] || { echo 'Refusing stale prototype evidence' >&2; exit 2; }
mkdir prototype-evidence
printf '%s\n' "$selector" > prototype-evidence/selectors.txt
python3 .github/test-evidence/source_methods.py --classes "$selector" --api "$TEST_API" --output prototype-evidence/expected-methods.json
instrument_selector=$selector
required_selector=$(printf '%s\n' "$required" "$focus_regression" "$contrast_regression" | sed '/^$/d' | paste -sd, -)
if [[ "$method_iteration" == true ]]; then
  python3 .github/prototype-methods.py prototype-evidence/expected-methods.json "$PROTOTYPE_METHODS"
  instrument_selector=$PROTOTYPE_METHODS
fi
printf 'candidate_sha=%s\napi=%s\nrelease_certified=false\n' "$GITHUB_SHA" "$TEST_API" > prototype-evidence/context.txt
# These APKs are independently compiled test inputs, NOT the canonical signed delivery APK.
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > prototype-evidence/test-input-apks.sha256
[[ -z $(find app/build/outputs/androidTest-results -name '*.xml' -print 2>/dev/null) ]] || { echo 'Refusing stale instrumentation XML' >&2; exit 2; }
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Require an explicit emulator serial' >&2; exit 2; }
actual_api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$actual_api" == "$TEST_API" ]] || { echo "Actual SDK $actual_api differs from requested $TEST_API" >&2; exit 2; }
# The pre-run boot id proves that a reconnected device is the same guest (#675).
boot_id=$(adb -s "$serial" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
[[ -n "$boot_id" ]] || { echo 'Require the emulator boot id' >&2; exit 2; }
printf 'serial=%s\nactual_api=%s\nboot_id=%s\n' "$serial" "$actual_api" "$boot_id" >> prototype-evidence/context.txt
aot_args=()
if [[ $TEST_API == 26 ]]; then
  aot_args=(-I .github/api26-aot.gradle)
fi
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
run_gate() {
  local status
  python3 .github/test-evidence/supervise.py --output prototype-evidence/live --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" --sha "$GITHUB_SHA" --api "$TEST_API" --lane prototype -- ./gradlew connectedDebugAndroidTest -I .github/prebuilt-device-tests.gradle "${aot_args[@]}" --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$instrument_selector" &
  child=$!
  wait "$child"
  status=$?
  child=''
  return "$status"
}
# #675: the emulator's adb transport can drop in the second after a complete, all-pass
# instrumentation run, while UTP pulls outputs and uninstalls the APKs. Gradle then fails
# on a teardown-only plugin error although every test passed. Only that exact signature,
# with the SAME guest answering again and the interrupted uninstall completed, lets the
# reconciliation below judge the first attempt's complete XML. Never a second run: it
# would give every test another chance. Any failed, missing or uncounted test, a rebooted
# guest, a device that stays offline or a package that cannot be removed keeps the failure.
# Exact-method iterations are excluded: the delivery decision requires their clean exit.
offline=prototype-evidence/teardown-offline
monitoring_completed() {
  python3 - prototype-evidence/live/supervision.json <<'PYSUP'
import json, sys
receipt = json.loads(open(sys.argv[1]).read())
raise SystemExit(0 if receipt['monitoring_complete'] is True and receipt['supervisor_error'] is None else 1)
PYSUP
}
set +e
run_gate
result=$?
if [[ "$result" != 0 && "$method_iteration" == false ]] \
  && python3 .github/test-evidence/teardown_offline.py classify --live prototype-evidence/live --results app/build/outputs/androidTest-results --expected prototype-evidence/expected-methods.json --output "$offline/classification.json" \
  && python3 .github/test-evidence/teardown_offline.py recover --serial "$serial" --api "$TEST_API" --boot-id "$boot_id" --timeout "${PROTOTYPE_OFFLINE_RECOVERY_SECONDS:-180}" --output "$offline" \
  && python3 .github/test-evidence/teardown_offline.py uninstall --serial "$serial" --output "$offline/uninstall.json" \
  && monitoring_completed; then
  echo "::warning title=#675 adb offline after a complete pass::API $TEST_API emulator went offline during UTP teardown after a complete, failure-free run (Gradle exit $result); the same guest reconnected and the interrupted uninstall completed, so the first attempt's final XML is reconciled as usual. Evidence under $offline."
  printf 'first_attempt_exit=%s\n' "$result" > "$offline/recovered.txt"
  result=0
fi
set -e
[[ "$result" == 0 ]] || exit "$result"
# The connected task must install the precompiled pair without rebuilding either APK.
sha256sum --check prototype-evidence/test-input-apks.sha256
python3 - "$selector" "$required_selector" <<'PYXML'
import hashlib, json, pathlib, re, sys, xml.etree.ElementTree as ET
if not __debug__:
    raise SystemExit("Optimized Python cannot disable prototype reconciliation")
root = pathlib.Path('prototype-evidence')
observed = json.loads((root/'live/observed/manifest.json').read_text())
if (root/'teardown-offline/recovered.txt').is_file():
    # #675: Gradle failed only on the teardown plugin error after a complete run; the same
    # guest answered again and the uninstall completed. The final XML below still decides.
    assert json.loads((root/'teardown-offline/classification.json').read_text())['accepted'] is True
    assert json.loads((root/'teardown-offline/recovery.json').read_text())['recovered'] is True
    assert observed['status'] == 'failed' and observed['child_returncode'] not in (None, 0)
    assert observed['observer_exit_status'] == observed['child_returncode'], 'Observer degraded beyond the Gradle exit'
    assert observed['cancel_signal'] is None and observed['observer_error'] is None
else:
    assert observed['status'] == 'completed' and observed['child_returncode'] == 0
    assert observed['observer_exit_status'] == 0
assert observed['observed_failure_lines'] == 0
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
# runtime_methods excludes methods outside their SdkSuppress interval. They must be
# absent outside it and execute inside it; runtime assumption skips still need evidence.
expected_methods = set(source_inventory['runtime_methods'])
assert source_inventory.get('api') == int(observed['api']), 'Inventory API differs from the observed API'
gates = source_inventory['gates']
gated_classes = set(source_inventory['gated_absent_classes'])
required = set(filter(None, sys.argv[2].split(',')))
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
# These two existing RuntimeShader-path tests assume SDK >= 33. Their exact
# API26 assumption callsites must be observed; API36 must execute both tests.
selection_sdk33_callsites = {
    'SelectionLensTest#translatingASelectionReusesItsDistanceField': 303,
    'SelectionLensTest#translatingAnIrregularSelectionKeepsItsMapButChangingItsHoleRebuildsIt': 315,
}
qualified_skips = []
assume_gated_skips = {}
def qualify_skip(name, method, skipped, xml_file):
    identity = name+'#'+method
    api = str(observed['api'])
    short_identity = identity.removeprefix(package) if identity.startswith(package) else None
    sdk33_line = selection_sdk33_callsites.get(short_identity)
    expected = profile_reasons.get(short_identity)
    if sdk33_line is not None and api == '26':
        expected = 'got: <false>, expected: is <true>'
    if identity == package+'PlayerBackgroundPolarityTest#nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers' and api == '26':
        expected = 'got: <false>, expected: is <true>'
    if identity == package+'PlayerArtworkOpticsTest#headerGlassKeepsTheCoverAcrossExpansionAndCollapse' and api == '26':
        expected = 'SDK <33 has cached Legacy glass rather than live Modern sampling'
    assume_gate = gates.get(identity, {}).get('assume_min_sdk', 0)
    assume_gated = expected is None and api.isdigit() and int(api) < assume_gate
    if assume_gated:
        expected = ''  # replaced by the single observed reason below
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
    if assume_gated and len(reasons) == 1:
        expected = reasons[0]
    if reasons != [expected]:
        raise ValueError('Missing, ambiguous or wrong observed skip reason: '+identity)
    if sdk33_line is not None:
        callsite = 'at '+name+'.'+method+'(SelectionLensTest.kt:'+str(sdk33_line)+')'
        boundaries = re.findall(r'TestRunner: (started|assumption failed|finished): ([^\r\n]*)', text)
        callsites = re.findall(r'TestRunner:\s+(at '+re.escape(name+'.'+method)+r'\(SelectionLensTest\.kt:[0-9]+\))', text)
        if boundaries != [('started', marker), ('assumption failed', marker), ('finished', marker)] or callsites != [callsite]:
            raise ValueError('Missing, mixed or ambiguous SDK assumption callsite/terminal: '+identity)
        ordered = ['TestRunner: started: '+marker, 'TestRunner: assumption failed: '+marker,
                   'TestRunner: org.junit.AssumptionViolatedException: '+expected, callsite,
                   'TestRunner: finished: '+marker]
        positions = [text.index(value) for value in ordered]
        if positions != sorted(positions) or re.search(r'TestRunner: (?:failed|failure|error):', text):
            raise ValueError('Unordered or contradictory SDK assumption evidence: '+identity)
    # If another adapter supplies XML reason text, it must not contradict the paired log.
    message = skipped.attrib.get('message', '')
    detail = (skipped.text or '').strip()
    if message not in ('', expected) or detail not in ('', expected, 'org.junit.AssumptionViolatedException: '+expected):
        raise ValueError('Contradictory XML assumption reason: '+identity)
    if assume_gated:
        assume_gated_skips[name] = assume_gated_skips.get(name, 0) + 1
    qualified_skips.append({'id': identity, 'api': api, 'reason': reasons[0], 'evidence': str(logs[0]),
        'evidence_sha256': hashlib.sha256(logs[0].read_bytes()).hexdigest(), 'xml': str(xml_file),
        'qualification': ('Source assumeTrue(SDK_INT >= '+str(assume_gate)+') above API'+api+'; skip is not a pass'
                          if assume_gated else 'Observed API26 SDK>=33 guard; shader path not exercised and API36 must execute'
                          if sdk33_line is not None else 'Observed assumption only; not a passed profile or optical proof')})
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
result = {'qualified_absences': source_inventory['gated_absent_reasons'], 'qualified_skips': qualified_skips, 'methods': methods, 'source_inventory_sha256': hashlib.sha256(source_bytes).hexdigest(), 'required_selectors': sorted(required), 'schema_version': 1, 'sha': observed['sha'], 'api': observed['api'], 'selectors': sorted(selected), 'counts': counts, 'xml_files': [str(p) for p in files], 'release_certified': False}
(root/'reconciled-tests.json').write_text(json.dumps(result, indent=2)+'\n')
assert not (gated_classes & required), 'Required regression classes cannot be API-gated'
# A class may run nothing when it is wholly SdkSuppress-gated outside this API, or only skip methods whose own
# source assumeTrue(SDK_INT >= N) is above this API; every other class needs a passing method.
assert all(v['failed'] == 0 and v['errors'] == 0 and (v['passed'] > 0 or (c in gated_classes and not any(v.values()))
           or (v['skipped'] > 0 and v['skipped'] == assume_gated_skips.get(c, 0)))
           for c, v in counts.items()), 'Required class failed or had no passing tests'
PYXML
