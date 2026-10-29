#!/usr/bin/env python3
"""Fail-closed ABBA native fixture comparison; not product or release validation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SHA = re.compile(r'[0-9a-f]{40}')
LABEL = re.compile(r'[a-z][a-z0-9-]{0,23}')
CLASS = re.compile(r'io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*')
OUTPUTS = ('app/build/outputs/androidTest-results', 'app/build/reports/androidTests',
           'app/build/outputs/connected_android_test_additional_output')
APKS = ('app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def parse_xml(folder, expected):
    require(folder.is_dir() and not folder.is_symlink(), 'Missing or unsafe native XML directory')
    files = sorted(folder.rglob('*.xml'))
    require(files, 'No native XML')
    methods = {}
    evidence = []
    suite_seconds = 0.0
    for path in files:
        require(not path.is_symlink() and path.stat().st_size <= 32*1024*1024, 'Unsafe XML')
        raw = path.read_bytes()
        require(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(), 'XML entities refused')
        root = ET.fromstring(raw)
        suites = [root] if root.tag == 'testsuite' else root.findall('.//testsuite') if root.tag == 'testsuites' else []
        require(suites, 'Missing testsuite')
        for suite in suites:
            require(not suite.findall('.//testsuite'), 'Nested test suites unsupported')
            cases = suite.findall('testcase')
            require(int(suite.attrib['tests']) == len(cases), 'XML count mismatch')
            for attr, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                require(int(suite.attrib.get(attr, '0')) == sum(len(c.findall(tag)) for c in cases), 'XML outcome count mismatch')
            elapsed = float(suite.attrib.get('time', '0'))
            require(elapsed >= 0 and elapsed < float('inf'), 'Invalid suite duration')
            suite_seconds += elapsed
            for case in cases:
                identity = case.attrib['classname']+'#'+case.attrib['name']
                require(identity not in methods, 'Duplicate XML identity: '+identity)
                outcome = [tag for tag in ('failure', 'error', 'skipped') if case.find(tag) is not None]
                require(not outcome, 'Nonpassing method: '+identity+' '+str(outcome))
                seconds = float(case.attrib.get('time', '0'))
                require(seconds >= 0 and seconds < float('inf'), 'Invalid duration')
                methods[identity] = {'seconds': seconds, 'outcome': 'passed'}
        evidence.append({'path': str(path), 'sha256': hashlib.sha256(raw).hexdigest()})
    require(set(methods) == set(expected), 'Missing/extra native method identity')
    require(len(methods) == len(expected), 'Require complete focused native methods')
    return {'methods': methods, 'xml': evidence, 'suite_seconds': suite_seconds,
            'method_seconds': sum(r['seconds'] for r in methods.values()), 'passed': len(methods), 'skipped': 0}


def check_monitor(root, sha, api, lane, expected_argv=None):
    observed = json.loads((root/'observed/manifest.json').read_text())
    supervisor = json.loads((root/'supervision.json').read_text())
    publisher = json.loads((root/'publishing/complete.json').read_text())
    require(observed['sha'] == supervisor['sha'] == publisher['sha'] == sha, 'Monitoring SHA mismatch')
    require(observed['api'] == supervisor['api'] == publisher['api'] == api, 'Monitoring API mismatch')
    require(observed['lane'] == publisher['lane'] == lane, 'Monitoring lane mismatch')
    require(observed['command_id'] == publisher['command_id'], 'Monitoring command mismatch')
    require(observed['status'] == 'completed' and observed['child_returncode'] == 0, 'Command did not pass')
    require(observed['observer_exit_status'] == 0 and observed['observer_error'] is None, 'Observer degraded')
    require(observed['cancel_signal'] is None and observed['observed_failure_lines'] == 0, 'Observed failure/cancel')
    require(supervisor['monitoring_complete'] and supervisor['observer_exit'] == supervisor['publisher_exit'] == 0
            and supervisor['supervisor_error'] is None, 'Supervisor degraded')
    require(publisher['status'] == 'monitoring_complete' and publisher['observed_failures'] == 0, 'Publisher degraded')
    require(publisher['observed_failure_lines'] == publisher['uploaded_packets'] == publisher['read_offset'] == 0, 'Successful publisher must have zero failure traffic')
    require(publisher['observer_status'] == 'completed', 'Publisher observer terminal mismatch')
    require(not list((root/'publishing').glob('packet-*.json')) and not list((root/'publishing').glob('receipt-*.json')), 'Unexpected failure packets/receipts')
    if expected_argv is not None:
        require(observed['argv'] == expected_argv, 'Observed command differs from intended focused invocation')
    require((root/'observed/events.jsonl').read_bytes() == b'', 'Failure events present')
    require(not (root/'publishing/monitoring-degraded.json').exists(), 'Monitoring degradation receipt')
    return {'command_id': observed['command_id'], 'monitoring_complete': True}


# Method contracts are intentionally not supplied by the candidate/config.
PREFIX = 'io.github.anastaci1a.localica.'
PROBE_CONTRACTS = {
    PREFIX+'ArtworkEditRestorationTest#'+method: {'artwork-pixel-initialization': 1}
    for method in ('normalEditorRestoresChooserAndAdjustedCropRepeatedlyWithTextDraft',
                   'artworkOnlyReturnsToReviewAfterRecreationAndCancelKeepsChosenImage',
                   'closingReopeningAndReplacingTargetDiscardPreviousSession')
}
PROBE_CONTRACTS.update({
    PREFIX+'PopupShadowContinuityTest#utilityShadowHasOneUnclippedFieldThroughoutRepeatedReversals':
        {'popup-endpoint': 2, 'popup-field-encode': 16, 'popup-profile-encode': 16},
    PREFIX+'PopupShadowContinuityTest#nestedShadowHasOneUnclippedFieldThroughoutRepeatedReversals':
        {'popup-endpoint': 2, 'popup-field-encode': 15, 'popup-profile-encode': 15},
    PREFIX+'PopupShadowContinuityTest#capsuleReferenceKeepsItsZeroWidthCenterRangeOrdered': {},
    PREFIX+'NativeSuiteCostEquivalenceTest#opaqueArtworkBulkWriteMatchesEveryOriginalPixel': {},
})
# Original AGP per-method logcats use logcat -v threadtime, verified against
# posttrim API26 original logs, including exact TestRunner start/finish text.
LOG_RECORD = re.compile(r'(?P<time>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+'
                        r'(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>[VDIWEF])\s+'
                        r'(?P<tag>[^:]+): (?P<message>.*)')
BOUNDARY = re.compile(r'(started|finished): ([A-Za-z0-9_]+)\(([^()]+)\)')
PROBE = re.compile(r'stage=([a-zA-Z0-9_-]+) elapsed_ns=([0-9]+)')


def read_probes(root, expected):
    """Require exact per-method diagnostic coverage independently of native XML."""
    require(set(expected) <= set(PROBE_CONTRACTS), 'Unknown diagnostic method contract')
    files = sorted(root.rglob('logcat-*.txt'))
    names = {'logcat-'+identity.replace('#', '-')+'.txt': identity for identity in expected}
    require(len(files) == len(expected), 'Missing/extra/duplicate per-method probe log')
    rows, seen = [], set()
    for path in files:
        require(not path.is_symlink() and path.stat().st_size <= 16*1024*1024, 'Unsafe probe log')
        require(path.name in names, 'Unknown per-method probe log')
        identity = names[path.name]
        require(identity not in seen, 'Duplicate per-method probe log')
        seen.add(identity)
        boundaries, samples, raw_samples = [], [], set()
        pid = None
        counts = {}
        for line_number, line in enumerate(path.read_text(errors='strict').splitlines(), 1):
            record = LOG_RECORD.fullmatch(line)
            if record is None:
                require('NativeSuiteCost' not in line and 'TestRunner:' not in line, 'Malformed diagnostic record')
                continue
            tag, message = record['tag'].strip(), record['message']
            if tag == 'TestRunner' and (message.startswith('started:') or message.startswith('finished:')):
                boundary = BOUNDARY.fullmatch(message)
                require(boundary is not None, 'Malformed method boundary')
                require(boundary[3]+'#'+boundary[2] == identity, 'Wrong method boundary')
                boundaries.append(boundary[1])
                require(boundaries in (['started'], ['started', 'finished']), 'Duplicate/reversed method boundary')
                if pid is None:
                    pid = record['pid']
                require(pid == record['pid'], 'Method boundary process changed')
            elif tag == 'NativeSuiteCost':
                require(boundaries == ['started'], 'Probe outside exact method boundaries')
                require(record['pid'] == pid, 'Probe process differs from method')
                probe = PROBE.fullmatch(message)
                require(probe is not None, 'Malformed timing probe')
                stage, value = probe.groups()
                require(stage in PROBE_CONTRACTS[identity], 'Unknown/unexpected probe stage')
                require(line not in raw_samples, 'Duplicate timing probe record')
                raw_samples.add(line)
                counts[stage] = counts.get(stage, 0)+1
                samples.append({'stage': stage, 'elapsed_ns': int(value), 'line': line_number,
                                'timestamp': record['time'], 'pid': pid, 'tid': record['tid']})
        require(boundaries == ['started', 'finished'], 'Missing method start/finish boundary')
        require(counts == PROBE_CONTRACTS[identity], 'Missing/extra stage samples for '+identity)
        rows.append({'method': identity, 'log': str(path), 'sha256': digest(path),
                     'expected_counts': PROBE_CONTRACTS[identity], 'observed_counts': counts,
                     'samples': samples})
    require(seen == set(expected), 'Missing per-method diagnostic evidence')
    return rows


class Run:
    def __init__(self, source, output):
        self.source, self.output = source, output
        self.child = None
        self.child_group = False
        self.cancelled = None
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, self.signal)

    def signal(self, sig, _frame):
        self.cancelled = self.cancelled or sig
        if self.child is not None and self.child.poll() is None:
            try:
                if self.child_group:
                    os.killpg(self.child.pid, sig)
                else:
                    self.child.send_signal(sig)
            except ProcessLookupError:
                pass

    def command(self, argv, *, log=None, env=None, supervisor=False):
        require(not self.cancelled, 'Cancelled before command')
        if log is None:
            return subprocess.check_output(argv, cwd=self.source, text=True).strip()
        with log.open('xb') as stream:
            start = time.monotonic_ns()
            self.child_group = not supervisor
            self.child = subprocess.Popen(argv, cwd=self.source, stdout=stream, stderr=subprocess.STDOUT,
                                          env=env, start_new_session=not supervisor)
            status = self.child.wait()
            elapsed = (time.monotonic_ns()-start)/1e9
            self.child = None
        if status < 0:
            status = 128-status
        if self.cancelled and status == 0:
            status = 128+self.cancelled
        return status, elapsed

    def clean(self, sha):
        require(self.command(['git','rev-parse','HEAD']) == sha, 'Checkout SHA mismatch')
        require(self.command(['git','status','--porcelain','--untracked-files=no']) == '', 'Tracked source modification')
        require(self.command(['git','ls-files','--others','--exclude-standard','app/src']) == '', 'Untracked app source refused')

    def archive(self, trial):
        for relative in OUTPUTS:
            path = self.source/relative
            require(not path.is_symlink(), 'Symlinked output refused: '+relative)
            if path.exists():
                dest = trial/'raw'/relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                require(not dest.exists(), 'Existing archived output')
                shutil.move(str(path), str(dest))

    def failure_context(self, trial):
        folder = trial/'android-failure-context'
        folder.mkdir(exist_ok=False)
        serial = os.environ.get('ANDROID_SERIAL','emulator-5554')
        for name, command in [('crash-buffer',['logcat','-b','crash','-d','-v','threadtime']),
                              ('windows',['shell','dumpsys','-t','5','window','windows']),
                              ('activities',['shell','dumpsys','-t','5','activity','activities'])]:
            try:
                with (folder/(name+'.txt')).open('xb') as stream:
                    result = subprocess.run(['adb','-s',serial,*command],stdout=stream,stderr=subprocess.STDOUT,timeout=10)
                (folder/(name+'.status')).write_text(str(result.returncode)+'\n')
            except (OSError, subprocess.TimeoutExpired) as error:
                (folder/(name+'.error')).write_text(str(error)+'\n')


def run(args):
    source = args.source.resolve()
    output = args.output.resolve()
    require(source.is_dir() and source != output and source not in output.parents, 'Evidence must be outside source checkout')
    require(not output.exists() and not output.is_symlink(), 'Refusing stale evidence directory')
    config = json.loads(args.config.read_text())
    label, cls = config['label'], config['class']
    require(LABEL.fullmatch(label) and CLASS.fullmatch(cls), 'Invalid candidate/class')
    baseline, candidate = config['baseline_sha'], config['candidate_sha']
    require(SHA.fullmatch(baseline) and SHA.fullmatch(candidate) and baseline != candidate, 'Invalid exact comparison SHAs')
    require(args.api in ('26','36'), 'Unsupported API')
    for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch(r'[0-9]+',os.environ.get(key,'')), 'Missing run identity')
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Require disposable hosted runner')
    serial = os.environ.get('ANDROID_SERIAL','emulator-5554')
    require(re.fullmatch(r'emulator-[0-9]+',serial), 'Explicit emulator serial required')
    def adb_text(*command):
        return subprocess.check_output(['adb','-s',serial,*command],text=True).strip()
    require(adb_text('shell','getprop','ro.kernel.qemu') == '1', 'Require emulator')
    require(adb_text('shell','getprop','ro.build.version.sdk') == args.api, 'Actual API mismatch')
    output.mkdir(parents=True)
    write_json(output/'config.json',config)
    runner = Run(source,output)
    runner.clean(baseline)
    for relative in OUTPUTS:
        require(not (source/relative).exists() and not (source/relative).is_symlink(), 'Stale initial native output: '+relative)
    changed = runner.command(['git','diff','--name-only',baseline,candidate]).splitlines()
    require(set(changed) == set(config['changed_paths']), 'Unexpected candidate source diff')
    require(changed and all(p.startswith('app/src/androidTest/') for p in changed), 'Only test-source candidate changes permitted')
    helper_paths = ['.github/prepare-device-tests.sh','.github/test-evidence/source_methods.py',
                    '.github/test-evidence/observe.py','.github/test-evidence/supervise.py','.github/test-evidence/publish.mjs']
    helpers = {p:digest(source/p) for p in helper_paths}
    write_json(output/'helper-sha256.json',helpers)
    order = [('A1','baseline',baseline),('B1','candidate',candidate),('B2','candidate',candidate),('A2','baseline',baseline)]
    precheck = config.get('precheck_class')
    if precheck:
        require(CLASS.fullmatch(precheck), 'Invalid precheck class')
        order.insert(0,('P0','precheck',baseline))
    expected = None
    apk_by_sha = {}
    rows = []
    for index,(trial_name,variant,sha) in enumerate(order,1):
        trial=output/trial_name
        trial_cls = precheck if variant == 'precheck' else cls
        expected_count = 1 if variant == 'precheck' else 3
        trial.mkdir()
        write_json(trial/'identity.json',{'trial':trial_name,'variant':variant,'sha':sha,'api':args.api,'class':trial_cls,
                                         'label':label,'order':index,'run_id':os.environ['GITHUB_RUN_ID'],
                                         'attempt':os.environ['GITHUB_RUN_ATTEMPT'],'release_certified':False})
        status = 0
        try:
            runner.command(['git','checkout','--detach',sha])
            runner.clean(sha)
            require({p:digest(source/p) for p in helper_paths} == helpers,'Monitoring/guard source changed')
            for relative in OUTPUTS:
                require(not (source/relative).exists() and not (source/relative).is_symlink(),'Stale trial output')
            status,build_seconds=runner.command(['./gradlew','assembleDebug','assembleDebugAndroidTest','--no-daemon','--max-workers=2'],log=trial/'compile.log')
            write_json(trial/'build.json',{'exit':status,'wall_seconds':build_seconds})
            require(status == 0,'Compilation failed')
            runner.clean(sha)
            runner.command([sys.executable,'.github/test-evidence/source_methods.py','--classes',trial_cls,'--output',str(trial/'expected-methods.json')])
            inv=json.loads((trial/'expected-methods.json').read_text())
            require(inv['selectors']==[trial_cls] and len(inv['runtime_methods'])==expected_count and inv['method_count']==expected_count,'Require exact focused method inventory')
            trial_expected=set(inv['runtime_methods'])
            if variant != 'precheck':
                if expected is None:
                    expected=trial_expected
                require(trial_expected==expected,'Candidate identity change')
            apks={p:digest(source/p) for p in APKS}
            if sha in apk_by_sha:
                require(apks==apk_by_sha[sha],'APK changed across same-source repeated trials')
            if apk_by_sha:
                require(apks[APKS[0]] == next(iter(apk_by_sha.values()))[APKS[0]], 'Production app APK differs between variants')
            apk_by_sha[sha]=apks
            write_json(trial/'input-apks.json',apks)
            # Preserve immutable inputs once per variant, even if native later fails.
            if not (output/('apks-'+variant)).exists():
                folder=output/('apks-'+variant);folder.mkdir()
                for p in APKS:shutil.copyfile(source/p,folder/Path(p).name)
            # Reset only the two owned app packages on this verified disposable
            # emulator. AGP may have uninstalled them; absent is already fresh.
            reset = []
            for package in ('io.github.anastaci1a.localica.debug','io.github.anastaci1a.localica.debug.test'):
                installed = runner.command(['adb','-s',serial,'shell','pm','list','packages','--user','0',package]).splitlines()
                present = 'package:'+package in installed
                row = {'package':package,'installed':present,'action':'already_absent'}
                if present:
                    response = runner.command(['adb','-s',serial,'shell','pm','clear','--user','0',package])
                    row.update(action='clear',response=response)
                    reset.append(row)
                    write_json(trial/(package.rsplit('.',1)[-1]+'-clear.json'),row)
                    require(response == 'Success','Owned package clear failed')
                else:
                    reset.append(row)
            write_json(trial/'owned-app-reset.json',reset)
            env=dict(os.environ,GITHUB_SHA=sha,TEST_API=args.api)
            lane=label+'-'+trial_name
            argv=['./gradlew','connectedDebugAndroidTest','--no-daemon','--max-workers=2','-Pandroid.testInstrumentationRunnerArguments.class='+trial_cls]
            supervisor_argv=[sys.executable,'.github/test-evidence/supervise.py','--output',str(trial/'live'),
                '--run-id',env['GITHUB_RUN_ID'],'--attempt',env['GITHUB_RUN_ATTEMPT'],'--sha',sha,'--api',args.api,'--lane',lane,'--',*argv]
            write_json(trial/'invocation.json',{'run_id':env['GITHUB_RUN_ID'],'attempt':env['GITHUB_RUN_ATTEMPT'],
                                               'supervisor_argv':supervisor_argv,'native_argv':argv})
            status,wall=runner.command(supervisor_argv,log=trial/'supervised-console.log',env=env,supervisor=True)
            write_json(trial/'execution.json',{'exit':status,'wall_seconds':wall,'argv':argv})
            # Archive before validating so every terminal failure retains original XML.
            runner.archive(trial)
            require(status == 0,'Native/monitor invocation failed')
            require({p:digest(source/p) for p in APKS}==apks,'APK changed during native run')
            runner.clean(sha)
            monitored=check_monitor(trial/'live',sha,args.api,lane,argv)
            result=parse_xml(trial/'raw/app/build/outputs/androidTest-results',trial_expected)
            # XML success remains visible even when diagnostic coverage fails.
            write_json(trial/'native-xml-verified.json', result)
            try:
                probes = read_probes(trial/'raw/app/build/outputs/androidTest-results', trial_expected)
            except (ValueError, OSError, UnicodeError) as error:
                write_json(trial/'diagnostic-failed.json', {'error': str(error), 'native_xml_verified': True,
                                                          'savings_conclusion_permitted': False})
                raise
            result['timing_probes'] = probes
            result.update(monitored,trial=trial_name,variant=variant,sha=sha,api=args.api,wall_seconds=wall,build_seconds=build_seconds)
            write_json(trial/'verified.json',result)
            if variant != 'precheck':
                rows.append(result)
        except BaseException as error:
            # Never advance to a replacement trial or treat a fast failure as a win.
            try:runner.archive(trial)
            except BaseException as archive_error:
                (trial/'archive-error.txt').write_text(str(archive_error)+'\n')
            try:runner.failure_context(trial)
            except BaseException as context_error:
                (trial/'failure-context-error.txt').write_text(str(context_error)+'\n')
            write_json(trial/'failed.json',{'error':str(error),'type':type(error).__name__,'invocation_exit':status})
            return status or (128+runner.cancelled if runner.cancelled else 2)
    summary={'label':label,'api':args.api,'baseline_sha':baseline,'candidate_sha':candidate,'order':'ABBA','trials':rows,
             'all_passed':True,'methods_per_trial':3,'release_certified':False,
             'interpretation':'Two paired focused samples on one emulator. Durations are observations, not full-suite savings.'}
    for metric in ('method_seconds','suite_seconds','wall_seconds'):
        a=sum(r[metric] for r in rows if r['variant']=='baseline')/2
        b=sum(r[metric] for r in rows if r['variant']=='candidate')/2
        summary[metric]={'baseline_mean':a,'candidate_mean':b,'candidate_minus_baseline':b-a,'percent':100*(b/a-1) if a else None}
    write_json(output/'comparison.json',summary)
    return 0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--api',required=True)
    args=parser.parse_args()
    try:return run(args)
    except (OSError,ValueError,KeyError,subprocess.CalledProcessError) as error:
        print('Paired validation rejected: '+str(error),file=sys.stderr)
        return 2


if __name__=='__main__':
    sys.exit(main())
