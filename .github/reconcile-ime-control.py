#!/usr/bin/env python3
"""Fail-closed evidence reconciliation for one isolated API36 IME control case at one immutable source.
No result is release certification, optical acceptance, or a causal comparison. Never modifies original evidence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from ime_control_contract import CASES, BASE, BASE_PARENT, BASE_TREE, SOURCE, PROBE, FILES, command, expected_inventory
from ime_environment import validate_receipt
PIN = '1e31403cb042e9896686200a4800faffce9b2a59'

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
    require(root.is_dir() and not any(p.is_symlink() for p in [root, *root.parents]), 'Missing/symlink XML root')
    require(not any(p.is_symlink() for p in root.rglob('*')), 'Symlink in XML tree')
    files = exact_files(root, '**/*.xml', count=len(list(root.glob('**/*.xml'))))
    require(files, 'No final instrumentation XML')
    methods = {}
    for path in files:
        document = ET.fromstring(data(path))
        require(document.tag in ('testsuite', 'testsuites'), 'Unsupported XML root')
        # Contradictory aggregate or suite-level terminal markers are failures,
        # even if every nested testcase happens to look green.
        require(not any(node.tag in ('failure', 'error', 'skipped') for node in document.iter()), 'Nonpassing XML marker')
        for node in document.iter():
            if node.tag in ('testsuites', 'testsuite', 'testcase'):
                if 'status' in node.attrib:
                    require(node.attrib['status'] in ('run', 'passed', 'pass'), 'Nonpassing XML status')
                if 'result' in node.attrib:
                    require(node.attrib['result'] in ('completed', 'passed', 'pass'), 'Nonpassing XML result')
        if document.tag == 'testsuites':
            require(all(node.tag in ('testsuite', 'properties', 'system-out', 'system-err') for node in document), 'Unsupported XML wrapper child')
            total = len(list(document.iter('testcase')))
            for attr, value in [('tests', total), ('failures', 0), ('errors', 0), ('skipped', 0)]:
                if attr in document.attrib:
                    require(int(document.attrib[attr]) == value, 'Contradictory XML wrapper '+attr)

        suites = [document] if document.tag == 'testsuite' else list(document.iter('testsuite'))
        require(suites, 'Missing XML suites')
        for suite in suites:
            require(not suite.findall('testsuite'), 'Nested XML suite unsupported')
            require(all(node.tag in ('testcase', 'properties', 'system-out', 'system-err') for node in suite), 'Unsupported XML suite child')
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


TRACE_PHASES = ['before-artwork-tap', 'after-artwork-tap', 'tap-popup-title', 'before-artwork-hold', 'before-menu-action', 'after-menu-action', 'menu-popup-title']
TRACE_CAVEAT = 'Descriptive, non-atomic owner/observer/frame/session samples; logging and observation may perturb scheduling. Matching samples do not establish readiness, exact launch causality, or optical/release acceptance.'

TRACE_CAVEAT='Source-exact baseline is distinct from patched defaults and diagnostic markers/probes. Logging can perturb scheduling; controllability does not prove editor ownership or initiating cause. No-warmup is inconclusive without native docked readiness. Outcomes are not acceptance, a repair, or timing equivalence.'
def outcome_summary(root,expected):
    result=dict(status='partial',methods={},xml_sha256={},errors=[],all_passed=False)
    try:
        require(root.is_dir() and not any(p.is_symlink() for p in [root,*root.parents,*root.rglob('*')]),'Missing/symlink XML tree')
        files=sorted(root.rglob('*.xml'));require(files,'No final XML')
        for path in files:
            result['xml_sha256'][str(path.relative_to(root))]=digest(path)
            doc=ET.fromstring(data(path));require(doc.tag in ('testsuite','testsuites'),'Unsupported XML root')
            for node in doc.iter('testcase'):
                identity=node.attrib['classname']+'#'+node.attrib['name']
                require(identity in expected and identity not in result['methods'],'Unexpected/duplicate XML method '+identity)
                outcomes=[k for k in ('failure','error','skipped') if node.find(k) is not None]
                require(len(outcomes)<=1,'Ambiguous XML method outcome')
                result['methods'][identity]=dict(outcome=outcomes[0] if outcomes else 'passed',details=[dict(type=n.tag,attributes=n.attrib,text=n.text) for n in node if n.tag in ('failure','error','skipped')])
        require(set(result['methods'])==set(expected),'Missing expected XML methods')
        # This inventory intentionally retains failures. Only xml_methods can certify a passing cell.
        result['status']='method-inventory-complete'
        try:xml_methods(root,expected);result['all_passed']=True
        except (ValueError,KeyError,ET.ParseError) as e:result['errors'].append('Nonpassing/invalid final XML: '+str(e))
    except (OSError,ValueError,KeyError,ET.ParseError) as e:result['errors'].append(str(e))
    return result

def trace_summary(source,evidence,case,run_id,attempt):
    c=CASES[case]
    result=dict(schema_version=1,status='partial',case=case,source_sha=c['sha'],run_id=run_id,attempt=attempt,methods={},original_sha256={},errors=[],caveat=TRACE_CAVEAT,causal_claim=False,timing_equivalent=False)
    def error(e):result['errors'].append(str(e))
    try:
        raw=data(evidence/'logcat-stream.txt');result['original_sha256']['evidence/logcat-stream.txt']=hashlib.sha256(raw).hexdigest()
        lines=raw.decode('utf-8',errors='strict').splitlines();pattern=re.compile(r'\bImeControlBoundary\s*:\s*(.*)$')
        bounds=[(i,m.group(1)) for i,line in enumerate(lines) if (m:=pattern.search(line))]
        identity='run='+run_id+' attempt='+attempt+' source='+c['sha']
        require([x[1] for x in bounds]==['START '+identity,'END '+identity],'Missing, repeated, foreign, or unordered log boundaries')
    except (OSError,ValueError,UnicodeError) as e:error(e)
    roots=[evidence/'device-output',source/'app/build/outputs/connected_android_test_additional_output']
    expected_names={m.split('#')[1]+suffix for m in c['expected'] for suffix in ('.txt','-timeline.txt','-keyboard-setup.txt')}
    originals={}
    for root in roots:
        try:
            if not root.exists():continue
            require(not any(p.is_symlink() for p in [root,*root.parents,*root.rglob('*')]),'Symlink additional output')
            for directory in root.rglob('search-ime-motion'):
                require(directory.is_dir(),'Invalid IME output directory')
                for path in directory.rglob('*'):
                    if not path.is_file():continue
                    require(path.parent==directory and path.name in expected_names,'Unexpected original IME file '+str(path))
                    raw=data(path);sha=hashlib.sha256(raw).hexdigest();key=str(path.relative_to(root))
                    result['original_sha256'][str(root)+'/'+key]=sha
                    if path.name in originals:require(originals[path.name][1]==sha,'Conflicting original copies '+path.name)
                    else:originals[path.name]=(raw,sha)
        except (OSError,ValueError) as e:error(e)
    if set(originals)!=expected_names:error('Missing or extra original IME timeline/frame/setup files: '+str(sorted(expected_names-set(originals))))
    for method in c['expected']:
        short=method.split('#')[1];name=short+'-timeline.txt'
        if name not in originals:continue
        entry=dict(original_sha256={n:originals[n][1] for n in [short+'.txt',short+'-keyboard-setup.txt',name] if n in originals},controls=[],phase_records=[],native_records=[],retirement_records=[],positive_native_ime_inset_observation=False)
        result['methods'][method]=entry
        try:
            lines=originals[name][0].decode('utf-8',errors='strict').splitlines()
            for i,line in enumerate(lines):
                if ' controls ' in line:entry['controls'].append(dict(line=i+1,value=line))
                if ' fixturePhase ' in line:entry['phase_records'].append(dict(line=i+1,value=line))
                if ' nativeProbe ' in line:entry['native_records'].append(dict(line=i+1,value=line))
                if 'endEditing reason=imeObservation' in line:entry['retirement_records'].append(dict(line=i+1,value=line))
                # Observational only; does not substitute for the unchanged native keyboard assertions.
                if re.search(r'root=\[visible=true,bottom=[1-9][0-9]*\]',line):entry['positive_native_ime_inset_observation']=True
                require('probeCleanupFailure' not in line,'Native probe cleanup failed')
            controls=c['controls']
            if controls is None:
                require(not entry['controls'] and not entry['phase_records'] and not entry['native_records'],'Diagnostics in trace-off/default source arm')
            else:
                require(len(entry['controls'])==1,'Missing/duplicate control record')
                values=' '.join(k+'='+str(v).lower() for k,v in zip(('diagnostics','nativeTrace','nativeWarmup','inputObservation'),controls))
                require(re.fullmatch(r'[0-9]+ controls '+re.escape(values)+r' fingerprint=\S+ wallMillis=[0-9]+ uptime=[0-9]+',entry['controls'][0]['value']) is not None,'Control record does not match arm')
                require(entry['phase_records'],'No opted-in phase records')
                if controls[1]:require(any('window=activity event=[installed]' in r['value'] for r in entry['native_records']),'Native observer installation not recorded')
                else:require(not entry['native_records'],'Native trace appeared in markers-only arm')
        except (OSError,ValueError,UnicodeError) as e:error(method+': '+str(e))
    if not result['errors']:result['status']='complete'
    return result


def reconcile(args):
    source, evidence = Path(args.source).resolve(), Path(args.evidence).resolve()
    tooling = Path(args.tooling).resolve()
    require(args.case in CASES, 'Unknown diagnostic case')
    c = CASES[args.case]
    sha, api, selectors, expected = c['sha'], '36', c['selectors'], c['expected']
    require(re.fullmatch('[0-9a-f]{40}', sha), 'Unbound corrected source pin')
    require(args.runner_exit == 0, 'Native runner did not complete successfully')
    require(re.fullmatch('[0-9a-f]{40}', args.workflow_sha) and re.fullmatch('[0-9]+', args.run_id) and re.fullmatch('[1-9][0-9]*', args.attempt), 'Invalid run identity')
    require(git(source, 'rev-parse', 'HEAD') == sha, 'Source checkout differs from immutable pin')
    parent, tree = c['parent'], c['tree']
    require(re.fullmatch('[0-9a-f]{40}', tree), 'Unbound diagnostic source tree')
    require(git(source, 'show', '-s', '--format=%P', 'HEAD') == parent and git(source, 'rev-parse', 'HEAD^{tree}') == tree, 'Corrected source parent/tree mismatch')
    if c['arm'] != 'baseline':
        require(git(source, 'show', '-s', '--format=%P', parent) == BASE_PARENT and git(source, 'rev-parse', parent+'^{tree}') == BASE_TREE, 'Baseline ancestry/tree differs')
        require(git(source, 'diff-tree', '--no-commit-id', '--name-status', '-r', parent, sha) == 'M\t'+SOURCE+'\nA\t'+PROBE, 'Test-only child delta differs')
    require(not git(source, 'diff', 'HEAD', '--name-only'), 'Tracked source changed')
    context = read_json(evidence/'context.json')
    harness = source.parent/'harness'
    require(git(harness, 'rev-parse', 'HEAD') == args.workflow_sha and not git(harness, 'diff', 'HEAD', '--name-only'), 'Harness checkout drift')
    harness_paths = FILES
    require(set(context['harness_sha256']) == set(harness_paths), 'Incomplete harness hash inventory')
    for path in harness_paths:
        require(digest(harness/path) == context['harness_sha256'][path], 'Harness receipt hash mismatch')
        require(git(harness, 'hash-object', str(harness/path)) == git(harness, 'rev-parse', args.workflow_sha+':'+path), 'Harness differs from workflow tree')

    changes=[] if c['arm']=='baseline' else [SOURCE,PROBE]
    for key,value in dict(case=args.case,arm=c['arm'],scope=c['scope'],source_sha=sha,source_parent=parent,source_tree=tree,baseline_sha=BASE,baseline_parent=BASE_PARENT,baseline_tree=BASE_TREE,source_changes=changes,controls=c['controls'],workflow_sha=args.workflow_sha,api=api,run_id=args.run_id,attempt=args.attempt,selectors=selectors,timing_equivalent=False).items():
        require(context[key]==value,'Wrong context '+key)
    argv = command(args.case)
    require(context['argv'] == argv and context['expected_methods'] == expected, 'Wrong diagnostic command/methods')
    require(all(context[key] is False for key in ('release_certified', 'full_suite_run', 'compatibility_run', 'optical_acceptance', 'causal_claim')) and context['tooling_sha'] == PIN, 'Wrong tooling/scope identity')
    require(git(tooling, 'rev-parse', 'HEAD') == PIN and not git(tooling, 'diff', 'HEAD', '--name-only'), 'Tooling checkout drift')
    helpers = ['.github/test-evidence/observe.py', '.github/test-evidence/supervise.py', '.github/test-evidence/publish.mjs', '.github/test-evidence/source_methods.py', '.github/test-evidence/package.json', '.github/test-evidence/package-lock.json', '.github/prepare-device-tests.sh', '.github/test-evidence/environment.sh']
    require(set(context['helper_sha256']) == set(helpers), 'Incomplete tooling hash inventory')
    for helper in helpers:
        require(digest(tooling/helper) == context['helper_sha256'][helper], 'Tooling receipt hash mismatch')
        require(git(tooling, 'hash-object', str(tooling/helper)) == git(tooling, 'rev-parse', PIN+':'+helper), 'Tool differs from pinned tree')
    supervisor_argv = ['python3', str(tooling/'.github/test-evidence/supervise.py'), '--output', str(evidence/'live'), '--run-id', args.run_id, '--attempt', args.attempt, '--sha', sha, '--api', api, '--lane', 'ime-control-'+args.case, '--', *argv]
    require(context['supervisor_argv'] == supervisor_argv, 'Wrong supervisor invocation provenance')
    status = read_json(evidence/'status.json')
    require(status['native_exit'] == 0 and status['cleanup_exit'] == 0 and status['cancel_signal'] is None, 'Native execution/cleanup not successful')
    require(data(evidence/'runner-exit.txt').strip() == b'0' and status['runner_exit'] == 0 and status['phase'] == 'native-terminal', 'Runner exit receipt missing/nonzero')
    captures = {}
    for line in data(evidence/'capture-status.txt').decode().splitlines():
        name, value = line.split('=', 1)
        require(name not in captures and value.isdigit(), 'Ambiguous capture status')
        captures[name] = int(value)
    required_captures = {'logcat', 'crash-buffer', 'windows', 'activities', 'device-output-pull', 'boundary-start', 'boundary-end'}
    require(set(captures) == required_captures, 'Incomplete capture statuses')
    require(all(captures[k] == 0 for k in required_captures - {'device-output-pull'}), 'Native diagnostic capture degraded')
    certificates = data(evidence/'test-input-certificates.txt').decode()
    fingerprints = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', certificates, re.M)
    require(len(fingerprints) == 2 and fingerprints[0] == fingerprints[1], 'App/test signing identity mismatch')
    device = read_json(evidence/'device.json')
    require(device['api'] == api and device['serial'] == 'emulator-5554', 'Wrong native device/API')
    require(device['effective_size'] == [720,1280] and all(type(v) is int for v in device['effective_size']), 'Wrong device size')
    require(type(device['effective_density']) is int and device['effective_density'] == 280, 'Wrong device density')
    scales = device['animation_scales']
    require(isinstance(scales,dict) and set(scales) == {'animator_duration_scale','transition_animation_scale','window_animation_scale'}, 'Incomplete animation settings')
    require(all(type(v) in (int,float) and math.isfinite(v) and v == 1.0 for v in scales.values()), 'Wrong animation settings')
    identity=read_json(evidence/'environment-identity.json')
    validate_receipt(identity)
    inventory = read_json(evidence/'expected-methods.json')
    classes = sorted({value.split('#')[0] for value in selectors})
    require(inventory['selectors'] == classes and set(expected).issubset(inventory['runtime_methods']), 'Wrong source method inventory')
    require(len(inventory['classes']) == len(classes), 'Wrong source class count')
    for entry in inventory['classes']:
        path = source/entry['source']
        require(path.resolve().is_relative_to(source) and entry['class'] in classes, 'Unexpected source class')
        require(digest(path) == entry['sha256'], 'Source inventory hash mismatch')
        require(git(source, 'hash-object', str(path)) == git(source, 'rev-parse', sha+':'+entry['source']), 'Source file differs from pinned tree')
    require(inventory == expected_inventory(args.case), 'Pinned declared source inventory differs')
    apk_paths = ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    require(data(evidence/'build-input-apks.sha256') == data(evidence/'test-input-apks.sha256'), 'Build/native APK inputs differ')
    require(data(evidence/'APK-STATUS.txt').decode().startswith('UNVALIDATED DIAGNOSTIC DEBUG INPUTS.'), 'Missing unvalidated APK qualification')
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
    require(observed['lane'] == published['lane'] == 'ime-control-'+args.case, 'Wrong monitored lane')
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
    trace = trace_summary(source,evidence,args.case,args.run_id,args.attempt)
    require(trace['status']=='complete','Incomplete original IME evidence: '+str(trace['errors']))
    stream_status=data(evidence/'logcat-stream-status.txt').decode()
    require(stream_status=='healthy=true\nexit=143\n','Logcat stream exited early or failed to drain')
    require(not data(evidence/'logcat-stream-stderr.txt').strip(),'Logcat stream stderr is nonempty')
    originals = {}
    for name in ('logcat', 'logcat-stream', 'crash-buffer', 'windows', 'activities'):
        originals['evidence/'+name+'.txt'] = digest(evidence/(name+'.txt'))
    require(data(evidence/'logcat.txt').strip(), 'Empty native logcat')
    return dict(schema_version=1,case=args.case,arm=c['arm'],scope=c['scope'],source_sha=sha,source_parent=parent,source_tree=tree,baseline_sha=BASE,test_only_delta=changes,workflow_sha=args.workflow_sha,api=api,run_id=args.run_id,attempt=args.attempt,methods=methods,xml_sha256=xml,original_sha256=originals,trace_sha256=trace['original_sha256'],source_inventory_sha256=digest(evidence/'expected-methods.json'),monitoring_complete=True,release_certified=False,full_suite_run=False,compatibility_run=False,optical_acceptance=False,causal_claim=False,timing_equivalent=False)



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=CASES, required=True)
    for name in ('source', 'tooling', 'evidence', 'workflow-sha', 'run-id', 'attempt'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--runner-exit', type=int, required=True)
    args = parser.parse_args()
    result = {'case': args.case, 'release_certified': False, 'optical_acceptance': False, 'causal_claim': False, 'status': 'rejected'}
    code = 3
    try:
        trace=trace_summary(Path(args.source),Path(args.evidence),args.case,args.run_id,args.attempt)
        outcome=outcome_summary(Path(args.source)/'app/build/outputs/androidTest-results',CASES[args.case]['expected'])
        for name,value in [('trace-summary.json',trace),('outcome-summary.json',outcome)]:
            with (Path(args.evidence)/name).open('x') as output:json.dump(value,output,indent=2);output.write('\n')
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
