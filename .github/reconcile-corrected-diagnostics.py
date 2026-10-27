#!/usr/bin/env python3
"""Fail-closed evidence reconciliation for three diagnostic-only, immutable-source cases.
No result is release certification or optical acceptance. Never modifies original evidence.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET

PREFIX = 'io.github.anastaci1a.localica.'
DIALOG = PREFIX + 'GlassDialogLateReopenTest'
POPUP = PREFIX + 'PopupOuterFinishBoundaryTest'
PIN = '1e31403cb042e9896686200a4800faffce9b2a59'
DIALOG_PIN = 'cfc20fd22b6295d2336d861dcf867e4bcc903b3f'
POPUP_PIN = '25fa9a7b462e9d5fddc535ae69306d2e56be508d'
DIALOG_METHOD = 'restoredSortReclaimsItsClosingWindowBeforeBodySettlesAndInvisibleSortRejectsContacts'
POPUP_METHOD = 'sameUtilityPopupRecordsOuterFinishControlsOutsideChildren'
CASES = {
    'dialog26': (DIALOG_PIN, '26', [DIALOG], [DIALOG+'#'+DIALOG_METHOD]),
    'popup26': (POPUP_PIN, '26', [POPUP], [POPUP+'#'+POPUP_METHOD]),
    'popup36': (POPUP_PIN, '36', [POPUP], [POPUP+'#'+POPUP_METHOD]),
}
SOURCE_TREES = {'dialog': ('1e31403cb042e9896686200a4800faffce9b2a59', 'ea775b9a4c7f3961b1165edbdaace21fb078f233'), 'popup': ('ac96014bbbf87cf76dc380ed39b342aea524a3d7', '36b8c668b8c138396dc2e6169f7ea2d5ba315000')}
PHASES = ['ordinary-context', 'rim-off', 'rim-restored', 'coating-off', 'coating-restored']


def require(condition, message):
    if not condition:
        raise ValueError(message)


def data(path):
    require(path.is_file() and not path.is_symlink(), 'Missing or symlink evidence: '+str(path))
    return path.read_bytes()


def digest(path):
    return hashlib.sha256(data(path)).hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            require(k not in result, 'Duplicate JSON key: '+k)
            result[k] = v
        return result
    return json.loads(data(path), object_pairs_hook=unique)


def git(source, *args):
    return subprocess.check_output(['git', '-C', str(source), *args], text=True).strip()


def exact_files(root, pattern, count=1):
    roots = root if isinstance(root, list) else [root]
    files = []
    for base in roots:
        for path in sorted(base.glob(pattern)):
            require(path.resolve().is_relative_to(base.resolve()), 'Evidence escaped root')
            files.append(path)
    # AGP export and explicit device pull may preserve identical original bytes.
    # Distinct byte sequences are never silently selected/replaced.
    if isinstance(root, list):
        unique = {}
        for path in files:
            unique.setdefault((path.name, digest(path)), path)
        files = list(unique.values())
    require(len(files) == count, 'Expected '+str(count)+' originals for '+pattern+', found '+str(len(files)))
    for path in files:
        data(path)
    return files


def xml_methods(root, expected):
    files = exact_files(root, '**/*.xml', count=len(list(root.glob('**/*.xml'))))
    require(files, 'No final instrumentation XML')
    methods = {}
    for path in files:
        document = ET.fromstring(data(path))
        require(document.tag in ('testsuite', 'testsuites'), 'Unsupported XML root')
        suites = [document] if document.tag == 'testsuite' else list(document.iter('testsuite'))
        require(suites, 'Missing XML suites')
        for suite in suites:
            require(not suite.findall('testsuite'), 'Nested XML suite unsupported')
            cases = suite.findall('testcase')
            require(int(suite.attrib['tests']) == len(cases), 'Incomplete XML tests count')
            for attr, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                actual = sum(len(case.findall(tag)) for case in cases)
                require(int(suite.attrib.get(attr, '0')) == actual, 'Inconsistent XML '+attr)
                require(actual == 0, 'Nonpassing XML '+attr)
            require(not suite.findall('failure') and not suite.findall('error'), 'Suite-level error')
            for case in cases:
                identity = case.attrib['classname']+'#'+case.attrib['name']
                require(identity in expected and identity not in methods, 'Unexpected or duplicate test: '+identity)
                require(not any(case.find(t) is not None for t in ('failure', 'error', 'skipped')), 'Nonpassing test')
                methods[identity] = 'passed'
    require(set(methods) == set(expected), 'Missing expected test methods')
    return methods, {str(p.relative_to(root)): digest(p) for p in files}


def original_evidence(case, source, evidence, api):
    require(case in CASES and api == CASES[case][1], 'Unknown diagnostic case/API')
    output = [source/'app/build/outputs/connected_android_test_additional_output', evidence/'device-output', evidence/'device-fallback']
    hashes = {}
    def keep(path):
        hashes[str(path.relative_to(source)) if path.is_relative_to(source) else 'evidence/'+str(path.relative_to(evidence))] = digest(path)
        return data(path)
    if case == 'dialog26':
        log = evidence/'logcat.txt'
        raw = keep(log).decode('utf-8', errors='replace')
        require(re.search(r'GlassDialogLateReopen[^\r\n]*return open=[^\r\n]*frames=\d+ virtualMs=\d+ wallMs=\d+ progress=[^\s]+ running=(?:true|false)', raw), 'Missing actual tagged dialog return progress')
    else:
        manifest_path = exact_files(output, '**/popup-outer-finish-boundary/*/manifest.json')[0]
        manifest = read_json(manifest_path)
        keep(manifest_path)
        require(manifest['status'] == 'matched-upstream-source-control-NOT-optical-acceptance' and manifest['failure'] is None, 'Popup control incomplete')
        require(str(manifest['api']) == api and manifest['declaredTotalCaptures'] == manifest['requestedCopies'] == 5, 'Wrong popup API/capture count')
        require(manifest['phaseOrder'] == PHASES, 'Wrong popup phases')
        require('fixedChildShadowStrength' not in manifest, 'Obsolete popup strength schema')
        context_strength = manifest['contextChildShadowStrength']
        compared_strength = manifest['comparedChildShadowStrength']
        require(type(context_strength) in (int, float) and math.isfinite(context_strength) and context_strength > 0,
                'Invalid ordinary context child strength')
        require(type(compared_strength) in (int, float) and math.isfinite(compared_strength) and compared_strength == 0,
                'Compared child strength is not zero')
        require(manifest['requestedComparisonPhases'] == ['rim-off/rim-restored', 'coating-off/coating-restored'],
                'Wrong popup comparison pairs')
        require(manifest['ordinaryQualification'] == 'first original and metadata are unbound context only; not compared',
                'Ordinary context must remain unbound and un-compared')
        records = manifest['records']
        require(len(records) == 5 and [r['phase'] for r in records] == PHASES, 'Incomplete popup records')
        folder = manifest_path.parent
        require(len(list(folder.glob('*-original-window.png'))) == 5, 'Extra popup original')
        for record, mode in zip(records, [0, 1, 0, 2, 0]):
            require(record['status'] == 'original-retained' and record['requestedFinishMode'] == mode, 'Incomplete popup original')
            strength = record['requestedChildStrength']
            expected_strength = context_strength if record['phase'] == 'ordinary-context' else compared_strength
            require(type(strength) in (int, float) and math.isfinite(strength) and strength == expected_strength,
                    'Wrong per-phase requested child strength')
            require(record['image'] == record['phase']+'-original-window.png', 'Unsafe/mismatched popup filename')
            path = exact_files(output, '**/popup-outer-finish-boundary/*/'+record['image'])[0]
            raw = keep(path)
            require(len(raw) == record['bytes'] and hashlib.sha256(raw).hexdigest() == record['sha256'], 'Popup original hash mismatch')
            require(raw[:8] == b'\x89PNG\r\n\x1a\n' and raw[12:16] == b'IHDR' and raw[-12:] == b'\x00\x00\x00\x00IEND\xaeB`\x82', 'Invalid/incomplete PNG')
            width, height = struct.unpack('>II', raw[16:24])
            require(width > 0 and height > 0 and [width, height] == [record['imageWidth'], record['imageHeight']], 'Wrong original dimensions')
            require(record['bitmapConfig'] == 'ARGB_8888' and record['hasAlpha'] is True, 'Original alpha not retained')
            require(isinstance(record['before'], dict) and isinstance(record['after'], dict), 'Missing popup binding receipts')
        for phase in ('rim-off', 'coating-off'):
            path = exact_files(output, '**/popup-outer-finish-boundary/*/'+phase+'-outer-margin-profiles.csv')[0]
            rows = list(csv.DictReader(keep(path).decode('utf-8').splitlines()))
            require(rows and {r['margin'] for r in rows} == {'top', 'bottom'}, 'Missing margin profile rows')
            require(all(int(r['pixels']) > 0 and all(math.isfinite(float(v)) for k,v in r.items() if k != 'margin') for r in rows), 'Invalid profile numbers')
            regions = exact_files(output, '**/popup-outer-finish-boundary/*/'+phase+'-measured-margin-regions.json')[0]
            value = read_json(regions)
            require(set(value) == {'top', 'bottom'} and all(len(v) == 4 and all(math.isfinite(float(n)) for n in v) for v in value.values()), 'Invalid margin regions')
            keep(regions)
    return hashes


def reconcile(args):
    source, evidence = Path(args.source).resolve(), Path(args.evidence).resolve()
    tooling = Path(args.tooling).resolve()
    sha, api, selectors, expected = CASES[args.case]
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Unbound corrected source pin')
    require(args.runner_exit == 0, 'Native runner did not complete successfully')
    require(re.fullmatch('[0-9a-f]{40}', args.workflow_sha) and re.fullmatch('[0-9]+', args.run_id) and re.fullmatch('[1-9][0-9]*', args.attempt), 'Invalid run identity')
    require(git(source, 'rev-parse', 'HEAD') == sha, 'Source checkout differs from immutable pin')
    parent, tree = SOURCE_TREES['dialog' if args.case == 'dialog26' else 'popup']
    require(git(source, 'show', '-s', '--format=%P', 'HEAD') == parent and git(source, 'rev-parse', 'HEAD^{tree}') == tree, 'Corrected source parent/tree mismatch')
    require(not git(source, 'diff', 'HEAD', '--name-only'), 'Tracked source changed')
    context = read_json(evidence/'context.json')
    for key, value in dict(case=args.case, source_sha=sha, workflow_sha=args.workflow_sha, api=api, run_id=args.run_id, attempt=args.attempt, selectors=selectors).items():
        require(context[key] == value, 'Wrong context '+key)
    argv = ['./gradlew', 'connectedDebugAndroidTest', '--no-daemon', '--max-workers=2', '-Pandroid.testInstrumentationRunnerArguments.class='+','.join(selectors), '-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/media/io.github.anastaci1a.localica.debug/additional_test_output', '-Pandroid.enableAdditionalTestOutput=true', '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true']
    require(context['argv'] == argv and context['expected_methods'] == expected, 'Wrong diagnostic command/methods')
    require(context['release_certified'] is False and context['tooling_sha'] == PIN, 'Wrong tooling/scope identity')
    require(git(tooling, 'rev-parse', 'HEAD') == PIN and not git(tooling, 'diff', 'HEAD', '--name-only'), 'Tooling checkout drift')
    helpers = ['.github/test-evidence/observe.py', '.github/test-evidence/supervise.py', '.github/test-evidence/publish.mjs', '.github/test-evidence/source_methods.py', '.github/test-evidence/package.json', '.github/test-evidence/package-lock.json', '.github/prepare-device-tests.sh', '.github/test-evidence/environment.sh']
    require(set(context['helper_sha256']) == set(helpers), 'Incomplete tooling hash inventory')
    for helper in helpers:
        require(digest(tooling/helper) == context['helper_sha256'][helper], 'Tooling receipt hash mismatch')
        require(git(tooling, 'hash-object', str(tooling/helper)) == git(tooling, 'rev-parse', PIN+':'+helper), 'Tool differs from pinned tree')
    supervisor_argv = ['python3', str(tooling/'.github/test-evidence/supervise.py'), '--output', str(evidence/'live'), '--run-id', args.run_id, '--attempt', args.attempt, '--sha', sha, '--api', api, '--lane', 'corrected-'+args.case, '--', *argv]
    require(context['supervisor_argv'] == supervisor_argv, 'Wrong supervisor invocation provenance')
    status = read_json(evidence/'status.json')
    require(status['native_exit'] == 0 and status['cleanup_exit'] == 0 and status['cancel_signal'] is None, 'Native execution/cleanup not successful')
    require(data(evidence/'runner-exit.txt').strip() == b'0' and status['runner_exit'] == 0 and status['phase'] == 'native-terminal', 'Runner exit receipt missing/nonzero')
    captures = {}
    for line in data(evidence/'capture-status.txt').decode().splitlines():
        name, value = line.split('=', 1)
        require(name not in captures and value.isdigit(), 'Ambiguous capture status')
        captures[name] = int(value)
    required_captures = {'logcat', 'crash-buffer', 'windows', 'activities', 'device-output-pull'}
    require(set(captures) == required_captures, 'Incomplete capture statuses')
    require(all(captures[k] == 0 for k in ('logcat', 'crash-buffer', 'windows', 'activities')), 'Native diagnostic capture degraded')
    certificates = data(evidence/'test-input-certificates.txt').decode()
    fingerprints = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', certificates, re.M)
    require(len(fingerprints) == 2 and fingerprints[0] == fingerprints[1], 'App/test signing identity mismatch')
    device = read_json(evidence/'device.json')
    require(str(device['api']) == api and re.fullmatch(r'emulator-[0-9]+', device['serial']), 'Wrong native device/API')
    inventory = read_json(evidence/'expected-methods.json')
    classes = sorted({value.split('#')[0] for value in selectors})
    require(inventory['selectors'] == classes and set(expected).issubset(inventory['runtime_methods']), 'Wrong source method inventory')
    require(len(inventory['classes']) == len(classes), 'Wrong source class count')
    for entry in inventory['classes']:
        path = source/entry['source']
        require(path.resolve().is_relative_to(source) and entry['class'] in classes, 'Unexpected source class')
        require(digest(path) == entry['sha256'], 'Source inventory hash mismatch')
        require(git(source, 'hash-object', str(path)) == git(source, 'rev-parse', sha+':'+entry['source']), 'Source file differs from pinned tree')
    require(set(inventory['runtime_methods']) == set(expected), 'Unexpected full-class source methods')
    apk_paths = ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    lines = data(evidence/'test-input-apks.sha256').decode().splitlines()
    require(len(lines) == 2, 'Incomplete APK hash receipt')
    for line, path in zip(lines, apk_paths):
        require(line == digest(source/path)+'  '+path, 'APK checksum receipt mismatch')
    live = evidence/'live'
    observed = read_json(live/'observed/manifest.json')
    supervised = read_json(live/'supervision.json')
    published = read_json(live/'publishing/complete.json')
    for receipt in (observed, supervised, published):
        require(receipt['sha'] == sha and receipt['api'] == api, 'Monitoring source/API mismatch')
    require(observed['lane'] == published['lane'] == 'corrected-'+args.case, 'Wrong monitored lane')
    require(observed['argv'] == argv and observed['command_id'] == published['command_id'], 'Monitoring command mismatch')
    require(re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}', observed['command_id']), 'Invalid command identity')
    require(observed['status'] == 'completed' and observed['ended_utc'] is not None, 'Observer incomplete')
    for key in ('child_returncode', 'observer_exit_status', 'observed_failure_lines', 'oversize_lines_not_parsed'):
        require(observed[key] == 0, 'Degraded/nonpassing observation '+key)
    require(observed['cancel_signal'] is None and observed['observer_error'] is None, 'Observer canceled/degraded')
    require(supervised['observer_exit'] == supervised['publisher_exit'] == 0 and supervised['monitoring_complete'] is True and supervised['supervisor_error'] is None, 'Supervisor degraded')
    require(published['status'] == 'monitoring_complete' and published['observer_status'] == 'completed', 'Publisher incomplete')
    require(published['observed_failure_lines'] == published['observed_failures'] == published['uploaded_packets'] == published['read_offset'] == 0, 'Contradictory publisher evidence')
    require(data(live/'observed/events.jsonl') == b'', 'Provisional failure contradicts pass')
    require(not list((live/'publishing').glob('packet-*.json')) and not list((live/'publishing').glob('receipt-*.json')) and not (live/'publishing/monitoring-degraded.json').exists(), 'Contradictory publication state')
    require(data(live/'observed/command.log').strip(), 'Empty native command output')
    methods, xml = xml_methods(source/'app/build/outputs/androidTest-results', expected)
    originals = original_evidence(args.case, source, evidence, api)
    return {'schema_version': 1, 'case': args.case, 'source_sha': sha, 'workflow_sha': args.workflow_sha, 'api': api, 'run_id': args.run_id, 'attempt': args.attempt, 'methods': methods, 'xml_sha256': xml, 'original_sha256': originals, 'source_inventory_sha256': digest(evidence/'expected-methods.json'), 'monitoring_complete': True, 'release_certified': False, 'optical_acceptance': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=CASES, required=True)
    for name in ('source', 'tooling', 'evidence', 'workflow-sha', 'run-id', 'attempt'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--runner-exit', type=int, required=True)
    args = parser.parse_args()
    result = {'case': args.case, 'release_certified': False, 'optical_acceptance': False, 'status': 'rejected'}
    code = 3
    try:
        result.update(reconcile(args), status='diagnostic-complete')
        code = 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError) as error:
        result['error'] = type(error).__name__+': '+str(error)
        print('Diagnostic reconciliation rejected: '+result['error'], file=sys.stderr)
    try:
        with (Path(args.evidence)/'reconciled-tests.json').open('x') as output:
            json.dump(result, output, indent=2)
            output.write('\n')
    except OSError as error:
        print('Cannot preserve fresh reconciliation receipt: '+str(error), file=sys.stderr)
        return 3
    return code


if __name__ == '__main__':
    sys.exit(main())
