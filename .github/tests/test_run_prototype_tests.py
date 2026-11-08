"""Offline contract checks. No emulator, Gradle build or network required."""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
OBSERVER = Path(os.environ.get('LOCALICA_OBSERVER_TEST_SOURCE', ROOT/'.github/test-evidence/observe.py'))
REQUIRED = 'io.github.anastaci1a.localica.SearchImeMotionTest'
FOCUS = 'io.github.anastaci1a.localica.ListSearchTest'
CONTRAST = 'io.github.anastaci1a.localica.FrostedSurfaceTest'
AFFECTED = 'io.github.anastaci1a.localica.QueueRecoveryTest'
SHA = 'f'*40


class RunnerContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for d in ['.github/test-evidence', 'bin', 'app/src/androidTest/java/io/github/anastaci1a/localica', 'app/build/outputs/apk/debug', 'app/build/outputs/apk/androidTest/debug']:
            (self.root/d).mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/'.github/run-prototype-tests.sh', self.root/'runner.sh')
        for helper in ('run-prototype-tests.sh', 'run-prototype-repeat.py', 'prototype-methods.py'):
            shutil.copyfile(ROOT/'.github'/helper, self.root/'.github'/helper)
        (self.root/'.github/prototype-risk-classes.txt').write_text(REQUIRED+'\n'+FOCUS+'\n'+CONTRAST+'\n'+AFFECTED+'\n')
        for cls in [REQUIRED,FOCUS,CONTRAST,AFFECTED]:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text('package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+' { @org.junit.Test fun regression() {} }\n')
        for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            (self.root/apk).write_bytes(b'apk')
        if not OBSERVER.is_file():
            self.fail('Integrate the reviewed observer or provide LOCALICA_OBSERVER_TEST_SOURCE for read-only dependency testing')
        (self.root/'.github/test-evidence/observe.py').symlink_to(OBSERVER.resolve())
        (self.root/'.github/test-evidence/supervise.py').symlink_to((ROOT/'.github/test-evidence/supervise.py').resolve())
        (self.root/'.github/test-evidence/source_methods.py').symlink_to((ROOT/'.github/test-evidence/source_methods.py').resolve())
        (self.root/'.github/test-evidence/teardown_offline.py').symlink_to((ROOT/'.github/test-evidence/teardown_offline.py').resolve())
        self.executable('bin/node', '''#!/usr/bin/env python3
import json,pathlib,sys,time
a=sys.argv
e=pathlib.Path(a[a.index('--evidence-dir')+1]);s=pathlib.Path(a[a.index('--state-dir')+1]);s.mkdir()
while not (e/'manifest.json').exists(): time.sleep(.01)
while json.loads((e/'manifest.json').read_text())['status']=='running':time.sleep(.01)
m=json.loads((e/'manifest.json').read_text())
(s/'complete.json').write_text(json.dumps(dict(m,status='monitoring_complete',observed_failures=m['observed_failure_lines'])))
''')
        (self.root/'.github/prepare-device-tests.sh').write_text('''printf 'setup\\n' >> lifecycle
trap 'status=$?; printf "restore\\n" >> lifecycle; if [[ ${CLEANUP_FAIL:-0} == 1 && $status == 0 ]]; then status=3; fi; exit "$status"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
''')
        self.executable('bin/adb', '#!/bin/sh\nprintf \"%s\\n\" \"${ACTUAL_API:-$TEST_API}\"\n')
        self.executable('bin/git', '#!/bin/sh\nprintf "%s\\n" "$GITHUB_SHA"\n')
        self.executable('gradlew', '''#!/usr/bin/env python3
import json,os,pathlib,signal,sys,time,xml.etree.ElementTree as E
pathlib.Path('argv.json').write_text(json.dumps(sys.argv))
mode=os.environ.get('MODE','pass')
# Model the first connected task rebuilding APKs when its BuildConfig input changes.
if mode in ('repackage-unless-prebuilt', 'repackage-always'):
    if mode == 'repackage-always' or '.github/prebuilt-device-tests.gradle' not in sys.argv:
        for apk in ('app/build/outputs/apk/debug/app-debug.apk',
                    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
            pathlib.Path(apk).write_bytes(b'repackaged-with-a-different-build-config')
        pathlib.Path('repackaged').touch()
classes=sys.argv[-1].split('=',1)[1].split(',')
if mode=='cancel':
    def stop(sig,frame):
        pathlib.Path('child-stopped').touch()
        sys.exit(128+sig)
    signal.signal(signal.SIGTERM,stop)
    signal.signal(signal.SIGINT,stop)
    pathlib.Path('child-ready').touch()
    while True: time.sleep(.05)
if mode=='exit7': sys.exit(7)
if mode=='silent': sys.exit(0)
if mode=='observed': print(classes[0]+' > regression[emulator-5554 - 16] FAILED',flush=True)
with pathlib.Path('gradle-invocations').open('a') as log: log.write('x'+chr(10))
invocation=len(pathlib.Path('gradle-invocations').read_text().split())
# #675: one invocation completes every test, then loses adb during UTP teardown.
offline=mode.startswith('teardown-offline') and invocation==int(os.environ.get('OFFLINE_ON_INVOCATION','1'))
if offline: pathlib.Path('offline-attempt').touch()
root=pathlib.Path('app/build/outputs/androidTest-results/connected/debug');root.mkdir(parents=True,exist_ok=True)
for i,selector in enumerate(os.environ['CASES'].split(',') if os.environ.get('CASES') else classes):
    cls, _, method = selector.partition('#')
    method = method or 'regression'
    if mode=='missing-class' and i==1: continue
    suite=E.Element('testsuite',tests='1')
    case=E.SubElement(suite,'testcase',classname=cls,name=method)
    if mode in ('xml-failure','teardown-offline-failure') and i==0:E.SubElement(case,'failure')
    if mode=='all-skipped' and i==0:E.SubElement(case,'skipped')
    if os.environ.get('SKIP_CLASS')==cls:
        method=os.environ['SKIP_METHOD']
        skipped=E.SubElement(suite,'testcase',classname=cls,name=method)
        skip=E.SubElement(skipped,'skipped')
        if os.environ.get('SKIP_XML_REASON'):skip.set('message',os.environ['SKIP_XML_REASON'])
        suite.set('tests','2')
        if os.environ.get('SKIP_ONLY')==cls:
            suite.remove(case);suite.set('tests','1')
        if os.environ.get('SKIP_LOG','yes')=='yes':
            folder=root/'emulator-5554 - fixture';folder.mkdir(exist_ok=True)
            marker=method+'('+cls+')'
            reason=os.environ.get('SKIP_REASON','')
            lines=['10-27 12:00:00.000 123 456 I TestRunner: started: '+marker,
                '10-27 12:00:00.001 123 456 E TestRunner: assumption failed: '+marker]
            if reason:lines.append('10-27 12:00:00.002 123 456 E TestRunner: org.junit.AssumptionViolatedException: '+reason)
            if os.environ.get('SKIP_CALLSITE'):lines.append('10-27 12:00:00.002 123 456 E TestRunner: '+os.environ['SKIP_CALLSITE'])
            if os.environ.get('SKIP_FINISHED')=='yes':lines.append('10-27 12:00:00.003 123 456 I TestRunner: finished: '+marker)
            (folder/('logcat-'+cls+'-'+method+'.txt')).write_text(chr(10).join(lines)+chr(10))
    if mode=='bad-count':suite.set('tests','2')
    E.ElementTree(suite).write(root/('TEST-'+str(i)+'.xml'))
if offline:
    n=len(classes)
    print('emulator-5554 - 16 Tests %d/%d completed. (0 skipped) (0 failed)'%(n,n))
    print('Finished %d tests on emulator-5554 - 16'%n)
    print('Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin: device offline')
    print('com.android.ddmlib.AdbCommandRejectedException: device offline',flush=True)
    sys.exit(1)
''')
        self.env = dict(os.environ, PATH=str(self.root/'bin')+os.pathsep+os.environ['PATH'], AFFECTED_CLASSES=AFFECTED, TEST_API='36', GITHUB_SHA=SHA, GITHUB_RUN_ID='1', GITHUB_RUN_ATTEMPT='1', GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted')

    def test_exact_method_proof_runs_mandatory_gate_once(self):
        self.run_case(PROTOTYPE_REPEAT='2', PROTOTYPE_METHODS=AFFECTED+'#regression', _timeout=30)
        root = self.root/'prototype-evidence'
        summary = json.loads((root/'iterations/summary.json').read_text())
        self.assertEqual('methods', summary['kind'])
        self.assertEqual('passed', summary['gate']['status'])
        gate = json.loads((root/'reconciled-tests.json').read_text())
        self.assertEqual({REQUIRED, FOCUS, CONTRAST, AFFECTED}, set(gate['selectors']))
        for item in summary['iterations']:
            self.assertEqual('passed', item['status'])
            receipt = json.loads((root/f"iterations/iteration-{item['iteration']:02d}/prototype-evidence/reconciled-tests.json").read_text())
            self.assertEqual({AFFECTED+'#regression': 'passed'}, receipt['methods'])
            self.assertEqual([], receipt['required_selectors'])
        self.assertEqual(3, (self.root/'lifecycle').read_text().count('setup'))

    def test_green_method_repetition_is_accepted_by_the_delivery_decision(self):
        # #657: the decision must accept the runner's own green repeat_methods evidence on both APIs.
        import yaml, hashlib
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        script=next(step['run'] for step in workflow['jobs']['evidence']['steps']
                    if step.get('name') == 'Record passed, failed and not-run checks')
        decision=self.root/'decision'; evidence=decision/'evidence'
        for api in ('26','36'):
            self.run_case(PROTOTYPE_REPEAT='2', PROTOTYPE_METHODS=AFFECTED+'#regression', TEST_API=api, _timeout=30)
            shutil.move(str(self.root/'prototype-evidence'), evidence/f'prototype-focused-api-{api}'/'prototype-evidence')
            (self.root/'lifecycle').unlink()
            compatibility=evidence/f'prototype-compatibility-api-{api}'; compatibility.mkdir()
            (compatibility/'provenance.json').write_text(json.dumps({'source_sha':SHA}))
        canonical=evidence/'prototype-build-internal-unvalidated'; canonical.mkdir()
        (canonical/'app-debug.apk').write_bytes(b'canonical')
        (canonical/'provenance.json').write_text(json.dumps({'sha':SHA,'apk_sha256':hashlib.sha256(b'canonical').hexdigest(),'run_id':'1','run_attempt':'1','signing_certificate':'verified-test-certificate'}))
        checks={k:{'result':'success'} for k in workflow['jobs']['evidence']['needs']}
        env=dict(os.environ,PROTOTYPE_METHODS=AFFECTED+'#regression',PROTOTYPE_REPEAT='2',CHECKS=json.dumps(checks),KNOWN_ISSUES='Not a release',AFFECTED_CLASSES=AFFECTED,COVERAGE_REASON='Queue regression',GITHUB_SHA=SHA,GITHUB_REF='refs/heads/test',GITHUB_RUN_ID='1',GITHUB_RUN_ATTEMPT='1',GITHUB_STEP_SUMMARY=str(decision/'summary'))
        result=subprocess.run(['bash','-c',script],cwd=decision,env=env,capture_output=True,text=True)
        manifest=json.loads((decision/'prototype-delivery/manifest.json').read_text())
        self.assertEqual([],manifest['validation_errors'],result.stdout+'\n'+result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertEqual(0,result.returncode,result.stderr)

    def test_method_selection_rejects_unknown_source_method(self):
        self.run_case(1, PROTOTYPE_METHODS=AFFECTED+'#unknown')
        self.assertFalse((self.root/'lifecycle').exists())

    def test_method_selection_rejects_duplicate_and_missing_class(self):
        for methods in (AFFECTED+'#regression,'+AFFECTED+'#regression', REQUIRED+'#regression'):
            result = subprocess.run(['bash', 'runner.sh'], cwd=self.root,
                env=dict(self.env, PROTOTYPE_METHODS=methods), capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((self.root/'lifecycle').exists())
            shutil.rmtree(self.root/'prototype-iterations')

    def test_method_only_failure_is_retained_without_a_retry(self):
        # A silent failure of the mandatory gate also prevents method iterations.
        self.run_case(7, PROTOTYPE_REPEAT='2', PROTOTYPE_METHODS=AFFECTED+'#regression', MODE='exit7')
        summary=json.loads((self.root/'prototype-evidence/iterations/summary.json').read_text())
        self.assertEqual('failed', summary['gate']['status'])
        self.assertTrue(all(item['status']=='not-run' for item in summary['iterations']))

    def reconnecting_adb(self):
        # The same guest answers again after the drop unless ADB_STATE or ADB_BOOT_ID_AFTER says otherwise.
        self.executable('bin/adb', '''#!/bin/sh
case "$*" in
  *get-state*) echo "${ADB_STATE:-device}" ;;
  *sys.boot_completed*) echo 1 ;;
  *ro.build.version.sdk*) echo "${ACTUAL_API:-$TEST_API}" ;;
  *boot_id*) if [ -e offline-attempt ]; then echo "${ADB_BOOT_ID_AFTER:-boot-1}"; else echo boot-1; fi ;;
  *uninstall*) echo Success ;;
  *"pm list packages"*) [ -z "${ADB_STUCK_PACKAGE:-}" ] || echo "package:$ADB_STUCK_PACKAGE" ;;
  *) echo "${ACTUAL_API:-$TEST_API}" ;;
esac
''')

    def gradle_invocations(self):
        path=self.root/'gradle-invocations'
        return len(path.read_text().splitlines()) if path.exists() else 0

    def test_teardown_offline_after_a_complete_pass_accepts_the_first_attempt(self):
        self.reconnecting_adb()
        result=self.run_case(0, _timeout=30, MODE='teardown-offline')
        # Never a second run: it would give every test another chance.
        self.assertEqual(1, self.gradle_invocations())
        self.assertIn('::warning title=#675 adb offline after a complete pass::', result.stdout)
        offline=self.root/'prototype-evidence/teardown-offline'
        observed=json.loads((self.root/'prototype-evidence/live/observed/manifest.json').read_text())
        self.assertEqual(('failed', 1, 0), (observed['status'], observed['child_returncode'], observed['observed_failure_lines']))
        self.assertTrue(json.loads((offline/'classification.json').read_text())['accepted'])
        recovery=json.loads((offline/'recovery.json').read_text())
        self.assertTrue(recovery['recovered'])
        self.assertEqual('boot-1', recovery['observed_boot_id'])
        uninstalled=json.loads((offline/'uninstall.json').read_text())
        self.assertEqual(['io.github.anastaci1a.localica.debug', 'io.github.anastaci1a.localica.debug.test'], sorted(uninstalled))
        self.assertTrue(all(entry['removed'] for entry in uninstalled.values()))
        self.assertEqual('first_attempt_exit=1\n', (offline/'recovered.txt').read_text())
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual({REQUIRED,FOCUS,CONTRAST,AFFECTED}, set(evidence['counts']))
        self.assertEqual('setup\nrestore\n', (self.root/'lifecycle').read_text())
        self.assertIn('boot_id=boot-1', (self.root/'prototype-evidence/context.txt').read_text())

    def test_teardown_offline_with_a_failed_test_keeps_the_failure(self):
        self.reconnecting_adb()
        self.run_case(1, _timeout=30, MODE='teardown-offline-failure')
        self.assertEqual(1, self.gradle_invocations())
        record=json.loads((self.root/'prototype-evidence/teardown-offline/classification.json').read_text())
        self.assertFalse(record['accepted'])
        self.assertTrue(any(reason.startswith('Failed test ') for reason in record['reasons']), record['reasons'])
        self.assertFalse((self.root/'prototype-evidence/teardown-offline/recovery.json').exists())
        self.assertFalse((self.root/'prototype-evidence/reconciled-tests.json').exists())
        self.assertEqual('setup\nrestore\n', (self.root/'lifecycle').read_text())

    def test_teardown_offline_with_a_rebooted_guest_keeps_the_failure(self):
        self.reconnecting_adb()
        self.run_case(1, _timeout=30, MODE='teardown-offline', ADB_BOOT_ID_AFTER='boot-2')
        self.assertEqual(1, self.gradle_invocations())
        recovery=json.loads((self.root/'prototype-evidence/teardown-offline/recovery.json').read_text())
        self.assertFalse(recovery['recovered'])
        self.assertIn('rebooted', recovery['reason'])
        self.assertFalse((self.root/'prototype-evidence/teardown-offline/recovered.txt').exists())
        self.assertFalse((self.root/'prototype-evidence/reconciled-tests.json').exists())

    def test_teardown_offline_without_a_returning_device_keeps_the_failure(self):
        self.reconnecting_adb()
        self.run_case(1, _timeout=40, MODE='teardown-offline', ADB_STATE='offline', PROTOTYPE_OFFLINE_RECOVERY_SECONDS='1')
        self.assertEqual(1, self.gradle_invocations())
        recovery=json.loads((self.root/'prototype-evidence/teardown-offline/recovery.json').read_text())
        self.assertFalse(recovery['recovered'])
        self.assertIn('did not return', recovery['reason'])
        self.assertTrue(all(state['state']=='offline' for state in recovery['states']))
        self.assertFalse((self.root/'prototype-evidence/reconciled-tests.json').exists())

    def test_teardown_offline_with_an_incomplete_uninstall_keeps_the_failure(self):
        self.reconnecting_adb()
        self.run_case(1, _timeout=30, MODE='teardown-offline', ADB_STUCK_PACKAGE='io.github.anastaci1a.localica.debug')
        offline=self.root/'prototype-evidence/teardown-offline'
        self.assertTrue(json.loads((offline/'recovery.json').read_text())['recovered'])
        uninstalled=json.loads((offline/'uninstall.json').read_text())
        self.assertFalse(uninstalled['io.github.anastaci1a.localica.debug']['removed'])
        self.assertFalse((offline/'recovered.txt').exists())
        self.assertFalse((self.root/'prototype-evidence/reconciled-tests.json').exists())

    def test_teardown_offline_with_a_publisher_failure_keeps_the_failure(self):
        self.reconnecting_adb()
        self.executable('bin/node', '#!/bin/sh\nexit 17\n')
        self.run_case(1, _timeout=30, MODE='teardown-offline')
        offline=self.root/'prototype-evidence/teardown-offline'
        self.assertTrue(json.loads((offline/'recovery.json').read_text())['recovered'])
        self.assertFalse((offline/'recovered.txt').exists())
        self.assertFalse((self.root/'prototype-evidence/reconciled-tests.json').exists())

    def test_repeat_records_the_recovered_iteration(self):
        self.reconnecting_adb()
        result=self.run_case(0, _timeout=40, PROTOTYPE_REPEAT='2', MODE='teardown-offline')
        self.assertEqual(2, self.gradle_invocations())
        summary=json.loads((self.root/'prototype-evidence/iterations/summary.json').read_text())
        first, second=summary['iterations']
        self.assertEqual(('passed', 0, 'passed', 0), (first['status'], first['exit_code'], second['status'], second['exit_code']))
        self.assertEqual({'issue': 675, 'signature': 'teardown-device-offline', 'first_attempt_exit': 'first_attempt_exit=1',
                          'evidence': 'prototype-evidence/teardown-offline'},
                         {k: v for k, v in first['infrastructure_recovered'].items() if k != 'recovered_after_seconds'})
        self.assertNotIn('infrastructure_recovered', second)
        self.assertIn('Prototype iteration 1/2: passed (recovered from a #675 teardown adb drop)', result.stdout)
        self.assertIn('Prototype iteration 2/2: passed\n', result.stdout)
        self.assertTrue((self.root/'prototype-evidence/iterations/iteration-01/prototype-evidence/teardown-offline/recovery.json').is_file())

    def test_exact_method_iterations_are_not_recovered(self):
        # The delivery decision requires a clean observer exit for every exact-method iteration.
        self.reconnecting_adb()
        self.run_case(1, _timeout=40, PROTOTYPE_REPEAT='2', PROTOTYPE_METHODS=AFFECTED+'#regression', MODE='teardown-offline', OFFLINE_ON_INVOCATION='2')
        self.assertEqual(2, self.gradle_invocations())
        summary=json.loads((self.root/'prototype-evidence/iterations/summary.json').read_text())
        self.assertEqual('passed', summary['gate']['status'])
        self.assertEqual(('failed', 1, 'not-run'), (summary['iterations'][0]['status'], summary['iterations'][0]['exit_code'], summary['iterations'][1]['status']))
        self.assertFalse((self.root/'prototype-evidence/iterations/iteration-01/prototype-evidence/teardown-offline').exists())

    def executable(self,path,text):
        target=self.root/path;target.write_text(text);target.chmod(0o755)

    def run_case(self, expected=0, _timeout=12, **env):
        result=subprocess.run(['bash','runner.sh'],cwd=self.root,env=dict(self.env,**env),capture_output=True,text=True,timeout=_timeout)
        self.assertEqual(expected,result.returncode,result.stdout+'\n'+result.stderr)
        return result

    def test_five_class_repeat_reuses_the_precompiled_pair_when_rebuild_inputs_change(self):
        import hashlib
        affected=[AFFECTED] + [self.gated_source('Affected'+str(i)+'Test',
                    '@org.junit.Test fun regression() {}') for i in range(4)]
        self.run_case(PROTOTYPE_REPEAT='3', MODE='repackage-unless-prebuilt',
                      AFFECTED_CLASSES=','.join(affected), _timeout=40)
        self.assertFalse((self.root/'repackaged').exists())
        args=json.loads((self.root/'argv.json').read_text())
        self.assertIn('.github/prebuilt-device-tests.gradle', args)
        root=self.root/'prototype-evidence'
        summary=json.loads((root/'iterations/summary.json').read_text())
        self.assertEqual(3,len(summary['iterations']))
        recorded=(root/'test-input-apks.sha256').read_text()
        for path in ('app/build/outputs/apk/debug/app-debug.apk',
                     'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
            self.assertIn(hashlib.sha256((self.root/path).read_bytes()).hexdigest()+'  '+path,recorded)
        for item in summary['iterations']:
            self.assertEqual('passed', item['status'])
            folder=root/f"iterations/iteration-{item['iteration']:02d}/prototype-evidence"
            self.assertEqual(recorded,(folder/'test-input-apks.sha256').read_text())
            receipt=json.loads((folder/'reconciled-tests.json').read_text())
            self.assertEqual(set(affected+[REQUIRED,FOCUS,CONTRAST]),set(receipt['selectors']))

    def test_forbidden_repackaging_cannot_produce_a_passing_receipt(self):
        result=self.run_case(1, MODE='repackage-always')
        self.assertIn('app-debug.apk: FAILED', result.stdout)
        self.assertFalse((self.root/'prototype-evidence/reconciled-tests.json').exists())

    def test_duplicate_selectors_deduplicate(self):
        self.run_case(AFFECTED_CLASSES=AFFECTED+','+AFFECTED)
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(4,len(args[-1].split('=',1)[1].split(',')))

    def test_focused_only_and_restoration(self):
        self.run_case()
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(1,args.count('connectedDebugAndroidTest'))
        self.assertEqual(set(args[-1].split('=',1)[1].split(',')),{REQUIRED,FOCUS,CONTRAST,AFFECTED})
        self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual({REQUIRED,FOCUS,CONTRAST,AFFECTED},set(evidence['counts']))
        self.assertFalse(evidence['release_certified'])

    def test_publisher_failure_holds_success(self):
        self.executable('bin/node', '#!/bin/sh\nexit 17\n')
        self.run_case(3)
        receipt=json.loads((self.root/'prototype-evidence/live/supervision.json').read_text())
        self.assertEqual(0,receipt['observer_exit'])
        self.assertEqual(17,receipt['publisher_exit'])
        self.assertFalse(receipt['monitoring_complete'])

    def test_test_failure_wins_over_publisher_failure(self):
        self.executable('bin/node', '#!/bin/sh\nexit 17\n')
        self.run_case(7,MODE='exit7')

    def test_invalid_publisher_completion_holds_success(self):
        self.executable('bin/node', '''#!/usr/bin/env python3
import pathlib,sys
p=pathlib.Path(sys.argv[sys.argv.index('--state-dir')+1]);p.mkdir();(p/'complete.json').write_text('{}')
''')
        self.run_case(3)

    def test_omitted_source_method_blocks_green_xml(self):
        path=self.root/('app/src/androidTest/java/'+AFFECTED.replace('.','/')+'.kt')
        path.write_text(path.read_text().rsplit('}',1)[0]+' @org.junit.Test fun omitted() {} }')
        self.run_case(1)

    def test_empty(self): self.run_case(2,AFFECTED_CLASSES='')
    def test_locked_down_self_hosted_jit_runner_is_admitted(self):
        self.run_case(RUNNER_ENVIRONMENT='self-hosted', LOCALICA_JIT_RUNNER='1', LOCALICA_EPHEMERAL_RUNNER='localica-kvm')
        self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
    def test_other_self_hosted_or_local_environments_are_refused(self):
        for env in ({'RUNNER_ENVIRONMENT':'self-hosted'}, {'RUNNER_ENVIRONMENT':'self-hosted','LOCALICA_JIT_RUNNER':'1'},
                    {'RUNNER_ENVIRONMENT':'self-hosted','LOCALICA_EPHEMERAL_RUNNER':'localica-kvm'},
                    {'RUNNER_ENVIRONMENT':'','LOCALICA_JIT_RUNNER':'1','LOCALICA_EPHEMERAL_RUNNER':'localica-kvm'},
                    {'GITHUB_ACTIONS':'false','RUNNER_ENVIRONMENT':'self-hosted','LOCALICA_JIT_RUNNER':'1','LOCALICA_EPHEMERAL_RUNNER':'localica-kvm'}):
            with self.subTest(env=env):
                self.run_case(2, **env)
                self.assertFalse((self.root/'lifecycle').exists())
    def test_mandatory_alone_is_not_affected_coverage(self): self.run_case(2,AFFECTED_CLASSES=REQUIRED)
    def test_injection(self):
        self.run_case(2,AFFECTED_CLASSES=AFFECTED+';touch PWNED')
        self.assertFalse((self.root/'PWNED').exists())
    def test_unknown(self): self.run_case(2,AFFECTED_CLASSES='io.github.anastaci1a.localica.UnknownTest')
    def test_twenty_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = ['io.github.anastaci1a.localica.Additional%dTest' % i for i in range(20)]
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as inventory:
            inventory.write('\n'.join(classes)+'\n')
        for cls in classes:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
                'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
                ' { @org.junit.Test fun regression() {} }\n')
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))

    def distinct_affected_sources(self, count):
        classes = ['io.github.anastaci1a.localica.Coverage%dTest' % i for i in range(count)]
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as inventory:
            inventory.write('\n'.join(classes)+'\n')
        for cls in classes:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
                'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
                ' { @org.junit.Test fun regression() {} }\n')
        return classes

    def test_twenty_one_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = self.distinct_affected_sources(21)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(args[-1].split('=',1)[1].split(',')))

    def test_seventy_four_distinct_affected_classes_are_rejected_before_instrumentation(self):
        classes = self.distinct_affected_sources(74)
        self.run_case(2,AFFECTED_CLASSES=','.join(classes))
        self.assertFalse((self.root/'argv.json').exists())

    def test_forty_seven_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = self.distinct_affected_sources(47)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(args[-1].split('=',1)[1].split(',')))

    def test_fifty_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = self.distinct_affected_sources(50)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(args[-1].split('=',1)[1].split(',')))

    def test_fifty_one_distinct_affected_classes_keep_all_mandatory_regressions(self):
        classes = self.distinct_affected_sources(51)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(evidence['counts']))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(set(classes)|{REQUIRED,FOCUS,CONTRAST},set(args[-1].split('=',1)[1].split(',')))

    def test_fifty_six_distinct_affected_classes_keep_every_method_and_sentinel(self):
        classes = self.distinct_affected_sources(56)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        expected=set(classes)|{REQUIRED,FOCUS,CONTRAST}
        self.assertEqual(expected,set(evidence['counts']))
        self.assertEqual({c+'#regression' for c in expected},set(evidence['methods']))
        self.assertEqual({REQUIRED,FOCUS,CONTRAST},set(evidence['required_selectors']))
        self.assertTrue(all(outcome == 'passed' for outcome in evidence['methods'].values()))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(expected,set(args[-1].split('=',1)[1].split(',')))

    def test_fifty_seven_distinct_affected_classes_keep_every_method_and_sentinel(self):
        classes = self.distinct_affected_sources(57)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        expected=set(classes)|{REQUIRED,FOCUS,CONTRAST}
        self.assertEqual(expected,set(evidence['counts']))
        self.assertEqual({c+'#regression' for c in expected},set(evidence['methods']))
        self.assertEqual({REQUIRED,FOCUS,CONTRAST},set(evidence['required_selectors']))
        self.assertTrue(all(outcome == 'passed' for outcome in evidence['methods'].values()))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(expected,set(args[-1].split('=',1)[1].split(',')))

    def test_sixty_distinct_affected_classes_keep_every_method_and_sentinel(self):
        classes = self.distinct_affected_sources(60)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        expected=set(classes)|{REQUIRED,FOCUS,CONTRAST}
        self.assertEqual(expected,set(evidence['counts']))
        self.assertEqual({c+'#regression' for c in expected},set(evidence['methods']))
        self.assertEqual({REQUIRED,FOCUS,CONTRAST},set(evidence['required_selectors']))
        self.assertTrue(all(outcome == 'passed' for outcome in evidence['methods'].values()))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(expected,set(args[-1].split('=',1)[1].split(',')))

    def test_sixty_nine_distinct_affected_classes_keep_every_method_and_sentinel(self):
        classes = self.distinct_affected_sources(69)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        expected=set(classes)|{REQUIRED,FOCUS,CONTRAST}
        self.assertEqual(expected,set(evidence['counts']))
        self.assertEqual({c+'#regression' for c in expected},set(evidence['methods']))
        self.assertEqual({REQUIRED,FOCUS,CONTRAST},set(evidence['required_selectors']))
        self.assertTrue(all(outcome == 'passed' for outcome in evidence['methods'].values()))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(expected,set(args[-1].split('=',1)[1].split(',')))

    def test_seventy_three_distinct_affected_classes_keep_every_method_and_sentinel(self):
        classes = self.distinct_affected_sources(73)
        self.run_case(AFFECTED_CLASSES=','.join(classes))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        expected=set(classes)|{REQUIRED,FOCUS,CONTRAST}
        self.assertEqual(expected,set(evidence['counts']))
        self.assertEqual({c+'#regression' for c in expected},set(evidence['methods']))
        self.assertEqual({REQUIRED,FOCUS,CONTRAST},set(evidence['required_selectors']))
        self.assertTrue(all(outcome == 'passed' for outcome in evidence['methods'].values()))
        args=json.loads((self.root/'argv.json').read_text())
        self.assertEqual(expected,set(args[-1].split('=',1)[1].split(',')))

    def test_menu_classes_run_in_full_with_all_mandatory_regressions(self):
        menu=['io.github.anastaci1a.localica.GlassMenuContentTest',
              'io.github.anastaci1a.localica.GlassMenuViewportTouchTest']
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as inventory:
            inventory.write('\n'.join(menu)+'\n')
        for cls in menu:
            (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
                'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
                ' { @org.junit.Test fun regression() {} }\n')
        self.run_case(AFFECTED_CLASSES=','.join(menu))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual({c+'#regression' for c in set(menu)|{REQUIRED,FOCUS,CONTRAST}},
                         set(evidence['methods']))

    def skip_source(self, cls, method):
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as stream:
            stream.write(cls+'\n')
        (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
            'package io.github.anastaci1a.localica\nclass '+cls.rsplit('.',1)[1]+
            ' { @org.junit.Test fun regression() {} @org.junit.Test fun '+method+'() {} }\n')
        return dict(AFFECTED_CLASSES=cls, SKIP_CLASS=cls, SKIP_METHOD=method)

    def test_profile_skip_requires_exact_identity_reason_and_keeps_evidence(self):
        cls='io.github.anastaci1a.localica.DialogChromeLayoutTest'
        method='native360x640Font2RetainsSharedChromeAndEveryRepeatControl'
        reason='Requires sufficient display space and actual Android font scale 2'
        self.run_case(**self.skip_source(cls,method),SKIP_REASON=reason)
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual([cls+'#'+method],[x['id'] for x in evidence['qualified_skips']])
        self.assertEqual(reason,evidence['qualified_skips'][0]['reason'])
        self.assertEqual('36',str(evidence['qualified_skips'][0]['api']))
        self.assertIn('logcat-',evidence['qualified_skips'][0]['evidence'])

    def accepted_profile(self, cls, method, reason, api):
        self.run_case(**self.skip_source('io.github.anastaci1a.localica.'+cls,method),TEST_API=api,SKIP_REASON=reason)
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual(1,len(evidence['qualified_skips']))
        self.assertEqual(reason,evidence['qualified_skips'][0]['reason'])

    def test_repeat_exact360_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native360x640Font2SectionsExposeEveryControlAndStageDrafts',
            'Exact native window profile requires sufficient display space and actual Android font scale 2','26')

    def test_repeat_exact400_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native400x800Font2SectionsExposeEveryControlAndStageDrafts',
            'Exact native window profile requires sufficient display space and actual Android font scale 2','36')

    def test_repeat_phone360_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native360x640PhoneFont2SectionsFitSafeInsetsWithoutWindowResize',
            'Requires the lead-configured 360x640 phone/font2 focused run','26')

    def test_repeat_phone400_observed_profile_reason(self):
        self.accepted_profile('RepeatOptionsEnvelopeTest','native400x800PhoneFont2SectionsFitSafeInsetsWithoutWindowResize',
            'Requires the lead-configured 400x800 phone/font2 focused run','36')

    def test_chrome400_observed_profile_reason(self):
        self.accepted_profile('DialogChromeLayoutTest','native400x800Font2RetainsSharedChromeAndEveryRepeatControl',
            'Requires sufficient display space and actual Android font scale 2','26')

    def test_tag_editor_observed_profile_reason(self):
        self.accepted_profile('TagEditorChromeLayoutTest','native360x640Font2KeepsSharedChromeAndScrollableFullSizeFields',
            'Requires sufficient display space and the requested actual system font scale','36')

    def test_phone_profile_cannot_borrow_the_other_dimension_reason(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.RepeatOptionsEnvelopeTest',
            'native360x640PhoneFont2SectionsFitSafeInsetsWithoutWindowResize'),
            SKIP_REASON='Requires the lead-configured 400x800 phone/font2 focused run')

    def test_xml_reason_cannot_contradict_original_method_log(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),
            SKIP_REASON='Requires sufficient display space and actual Android font scale 2',SKIP_XML_REASON='Contradiction')

    def test_profile_skip_wrong_method_is_rejected(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest','wrongMethod'),
            SKIP_REASON='Requires sufficient display space and actual Android font scale 2')

    def test_profile_skip_wrong_reason_is_rejected(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),SKIP_REASON='Different reason')

    def test_profile_skip_missing_observed_reason_is_rejected(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),SKIP_REASON='')

    def test_empty_xml_skip_without_method_log_is_not_guessed(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.DialogChromeLayoutTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),SKIP_LOG='no')

    def test_api26_native_blur_skip_is_qualified_by_observed_default_junit_reason(self):
        cls='io.github.anastaci1a.localica.PlayerBackgroundPolarityTest'
        method='nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers'
        self.run_case(**self.skip_source(cls,method),TEST_API='26',SKIP_REASON='got: <false>, expected: is <true>')
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual('got: <false>, expected: is <true>',evidence['qualified_skips'][0]['reason'])

    # #464: the expected inventory follows the same SdkSuppress/assumeTrue API gates as the runner.
    PKG='io.github.anastaci1a.localica.'
    GATE='@androidx.test.filters.SdkSuppress(minSdkVersion = 33)'

    def gated_source(self, name, body, prefix=''):
        cls=self.PKG+name
        with (self.root/'.github/prototype-risk-classes.txt').open('a') as stream:
            stream.write(cls+'\n')
        (self.root/('app/src/androidTest/java/'+cls.replace('.','/')+'.kt')).write_text(
            'package io.github.anastaci1a.localica\n'+prefix+'class '+name+' { '+body+' }\n')
        return cls

    def cases(self, *extra):
        return ','.join([c+'#regression' for c in (REQUIRED,FOCUS,CONTRAST)]+list(extra))

    def mixed(self):
        cls=self.gated_source('GatedMixedTest', '@org.junit.Test fun plain() {} '+self.GATE+' @org.junit.Test fun gated() {} '
            '@org.junit.Test '+self.GATE+' fun gatedAfter() {}')
        return cls

    def rerun(self, *args, **env):
        for stale in ('prototype-evidence', 'app/build/outputs/androidTest-results', 'lifecycle'):
            target = self.root/stale
            if target.is_dir(): shutil.rmtree(target)
            elif target.exists(): target.unlink()
        return self.run_case(*args, **env)

    def test_api26_expects_sdk_gated_methods_absent_and_passes(self):
        cls=self.mixed()
        self.run_case(AFFECTED_CLASSES=cls, TEST_API='26', CASES=self.cases(cls+'#plain'))
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual({'passed':1,'failed':0,'errors':0,'skipped':0}, evidence['counts'][cls])
        inventory=json.loads((self.root/'prototype-evidence/expected-methods.json').read_text())
        self.assertEqual([cls+'#gated',cls+'#gatedAfter'], inventory['gated_absent_methods'])

    def test_api36_still_requires_sdk_gated_methods(self):
        cls=self.mixed()
        self.run_case(AFFECTED_CLASSES=cls, TEST_API='36', CASES=self.cases(cls+'#plain',cls+'#gated',cls+'#gatedAfter'))
        self.rerun(1, AFFECTED_CLASSES=cls, TEST_API='36', CASES=self.cases(cls+'#plain',cls+'#gated'))

    def test_api26_rejects_a_gated_method_that_ran_anyway(self):
        cls=self.mixed()
        self.run_case(1, AFFECTED_CLASSES=cls, TEST_API='26', CASES=self.cases(cls+'#plain',cls+'#gated'))

    def test_api26_rejects_a_missing_ungated_method(self):
        cls=self.mixed()
        self.run_case(1, AFFECTED_CLASSES=cls, TEST_API='26', CASES=self.cases())

    def test_class_level_gate_expects_the_whole_class_absent_below_its_api(self):
        cls=self.gated_source('GatedWholeTest', '@org.junit.Test fun one() {} @org.junit.Test fun two() {}', self.GATE+'\n')
        self.run_case(AFFECTED_CLASSES=cls, TEST_API='26', CASES=self.cases())
        self.rerun(AFFECTED_CLASSES=cls, TEST_API='36', CASES=self.cases(cls+'#one',cls+'#two'))
        self.rerun(1, AFFECTED_CLASSES=cls, TEST_API='36', CASES=self.cases())
        self.rerun(1, AFFECTED_CLASSES=cls, TEST_API='26', CASES=self.cases(cls+'#one'))

    def assume_gated(self, name='AssumeGatedTest'):
        cls=self.gated_source(name, '@org.junit.Test fun regression() {} @org.junit.Test fun gatedByAssume() { '
            'org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)\n val x = 1 }')
        return dict(AFFECTED_CLASSES=cls, SKIP_CLASS=cls, SKIP_METHOD='gatedByAssume')

    def test_assume_gated_skip_below_its_api_is_qualified_without_an_allow_list_entry(self):
        env=self.assume_gated()
        self.run_case(TEST_API='26', SKIP_REASON='got: <false>, expected: is <true>', **env)
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual('skipped', evidence['methods'][env['SKIP_CLASS']+'#gatedByAssume'])
        self.assertEqual(1, len(evidence['qualified_skips']))

    def test_class_whose_only_method_is_a_source_gated_skip_may_run_nothing_else(self):
        cls=self.gated_source('OnlyAssumeTest', '@org.junit.Test fun gatedByAssume() { '
            'org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)\n val x = 1 }')
        self.run_case(TEST_API='26', AFFECTED_CLASSES=cls, SKIP_CLASS=cls, SKIP_METHOD='gatedByAssume',
                      SKIP_REASON='got: <false>, expected: is <true>', SKIP_ONLY=cls)
        evidence=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual({'passed':0,'failed':0,'errors':0,'skipped':1}, evidence['counts'][cls])


    def test_assume_gated_skip_at_or_above_its_api_is_rejected(self):
        self.run_case(1, TEST_API='36', SKIP_REASON='got: <false>, expected: is <true>', **self.assume_gated())

    def test_ungated_skip_is_still_rejected(self):
        cls=self.gated_source('PlainSkipTest', '@org.junit.Test fun regression() {} @org.junit.Test fun skipsAnyway() {}')
        self.run_case(1, TEST_API='26', AFFECTED_CLASSES=cls, SKIP_CLASS=cls, SKIP_METHOD='skipsAnyway',
                      SKIP_REASON='got: <false>, expected: is <true>')

    def selection_sdk_skip(self, method, line, **overrides):
        cls='io.github.anastaci1a.localica.SelectionLensTest'
        env=dict(self.skip_source(cls,method),TEST_API='26',SKIP_REASON='got: <false>, expected: is <true>',
            SKIP_CALLSITE='at '+cls+'.'+method+'(SelectionLensTest.kt:'+str(line)+')',SKIP_FINISHED='yes')
        env.update(overrides)
        return env

    def test_selection_simple_api26_sdk_guard_keeps_skip_not_pass(self):
        method='translatingASelectionReusesItsDistanceField'
        self.run_case(**self.selection_sdk_skip(method,303))
        result=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        identity='io.github.anastaci1a.localica.SelectionLensTest#'+method
        self.assertEqual('skipped',result['methods'][identity])
        self.assertEqual(1,result['counts']['io.github.anastaci1a.localica.SelectionLensTest']['skipped'])
        self.assertIn('API36 must execute',result['qualified_skips'][0]['qualification'])

    def test_selection_irregular_api26_sdk_guard_keeps_skip_not_pass(self):
        self.run_case(**self.selection_sdk_skip('translatingAnIrregularSelectionKeepsItsMapButChangingItsHoleRebuildsIt',315))
        result=json.loads((self.root/'prototype-evidence/reconciled-tests.json').read_text())
        self.assertEqual('skipped',result['methods']['io.github.anastaci1a.localica.SelectionLensTest#translatingAnIrregularSelectionKeepsItsMapButChangingItsHoleRebuildsIt'])

    def test_selection_simple_api36_cannot_skip(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',303,TEST_API='36'))

    def test_selection_irregular_api36_cannot_skip(self):
        self.run_case(1,**self.selection_sdk_skip('translatingAnIrregularSelectionKeepsItsMapButChangingItsHoleRebuildsIt',315,TEST_API='36'))

    def test_selection_wrong_sdk_callsite_rejected(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',315))

    def test_selection_missing_sdk_callsite_rejected(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',303,SKIP_CALLSITE=''))

    def test_selection_missing_terminal_rejected(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',303,SKIP_FINISHED='no'))

    def test_selection_wrong_assumption_reason_rejected(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',303,SKIP_REASON='Different assumption'))

    def test_selection_missing_assumption_log_rejected(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',303,SKIP_LOG='no'))

    def test_selection_unlisted_method_cannot_borrow_sdk_reason(self):
        self.run_case(1,**self.selection_sdk_skip('otherSelectionMethod',303))

    def test_selection_xml_reason_cannot_contradict_sdk_log(self):
        self.run_case(1,**self.selection_sdk_skip('translatingASelectionReusesItsDistanceField',303,SKIP_XML_REASON='Contradiction'))

    def test_api36_native_blur_must_run(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.PlayerBackgroundPolarityTest',
            'nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers'),SKIP_REASON='got: <false>, expected: is <true>')

    def test_unrelated_class_cannot_borrow_profile_reason(self):
        self.run_case(1,**self.skip_source('io.github.anastaci1a.localica.OtherProfileTest',
            'native360x640Font2RetainsSharedChromeAndEveryRepeatControl'),
            SKIP_REASON='Requires sufficient display space and actual Android font scale 2')

    def test_optimized_python_cannot_disable_reconciliation(self):
        self.run_case(1,PYTHONOPTIMIZE='1')

    def test_too_many(self): self.run_case(2,AFFECTED_CLASSES=','.join([AFFECTED]*74))
    def test_too_long(self): self.run_case(2,AFFECTED_CLASSES='x'*4097)
    def test_missing_source(self):
        (self.root/('app/src/androidTest/java/'+AFFECTED.replace('.','/')+'.kt')).unlink()
        self.run_case(2)
    def test_missing_observer(self):
        (self.root/'.github/test-evidence/observe.py').unlink();self.run_case(2)
    def test_non_hosted(self): self.run_case(2,RUNNER_ENVIRONMENT='self-hosted')
    def test_actual_sdk_must_match_declared_api(self): self.run_case(2,ACTUAL_API='26')
    def test_bad_api(self): self.run_case(2,TEST_API='35')
    def test_bad_sha(self): self.run_case(2,GITHUB_SHA='main')
    def test_stale_evidence(self):
        (self.root/'prototype-evidence').mkdir();self.run_case(2)
    def test_stale_xml(self):
        path=self.root/'app/build/outputs/androidTest-results';path.mkdir();(path/'old.xml').touch();self.run_case(2)
    def test_original_failure_preserved(self):
        self.run_case(7,MODE='exit7');self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
    def test_cleanup_failure_blocks_success(self): self.run_case(3,CLEANUP_FAIL='1')
    def test_observed_failure_blocks_zero_exit(self): self.run_case(1,MODE='observed')
    def test_missing_xml_blocks_zero_exit(self): self.run_case(1,MODE='silent')
    def test_xml_failure_blocks_zero_exit(self): self.run_case(1,MODE='xml-failure')
    def test_missing_required_class(self): self.run_case(1,MODE='missing-class')
    def test_skipped_required_class(self): self.run_case(1,MODE='all-skipped')
    def test_bad_xml_count(self): self.run_case(1,MODE='bad-count')
    def test_cancel_restores_after_child_stops(self):
        child=subprocess.Popen(['bash','runner.sh'],cwd=self.root,env=dict(self.env,MODE='cancel'),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            deadline=time.monotonic()+5
            while not (self.root/'child-ready').exists() and time.monotonic()<deadline: time.sleep(.02)
            self.assertTrue((self.root/'child-ready').exists())
            child.send_signal(signal.SIGTERM)
            out,err=child.communicate(timeout=8)
            self.assertEqual(143,child.returncode,(out,err))
            self.assertTrue((self.root/'child-stopped').exists())
            self.assertEqual('setup\nrestore\n',(self.root/'lifecycle').read_text())
        finally:
            if child.poll() is None:child.kill();child.communicate()


class WorkflowContract(unittest.TestCase):
    def decision(self, failed=False, missing=False, wrong_sha=False, contradictory=False, malformed=False, partial_methods=False, skipped_required_method=False, build_attempt="1", decision_attempt="1", build_run="1", wrong_digest=False, repetitions=1, bad_iteration=False, missing_iteration=False, method_mode=False, missing_gate=False, bad_method=False, duplicate_command=False, affected_classes=None, changed_iteration_apks=False):
        import yaml, hashlib
        affected_classes=affected_classes or [AFFECTED]
        selected=[REQUIRED,FOCUS,CONTRAST]+affected_classes
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        script=next(step['run'] for step in workflow['jobs']['evidence']['steps']
                    if step.get('name') == 'Record passed, failed and not-run checks')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); evidence=root/'evidence';evidence.mkdir()
            canonical=evidence/'prototype-build-internal-unvalidated';canonical.mkdir()
            (canonical/'app-debug.apk').write_bytes(b'canonical')
            (canonical/'provenance.json').write_text(json.dumps({'sha':'a'*40 if wrong_sha else SHA,'apk_sha256':hashlib.sha256(b'wrong' if wrong_digest else b'canonical').hexdigest(),'run_id':build_run,'run_attempt':build_attempt,'signing_certificate':'verified-test-certificate'}))
            checks={k:{'result':'success'} for k in workflow['jobs']['evidence']['needs']}
            if failed:checks['focused_36']['result']='failure'
            if not missing:
                for api in (26,36):
                    focused=evidence/f'prototype-focused-api-{api}'/'prototype-evidence';focused.mkdir(parents=True)
                    inventory={'selectors':selected,'runtime_methods':[c+'#regression' for c in selected]}
                    inventory_bytes=json.dumps(inventory).encode()
                    (focused/'expected-methods.json').write_bytes(inventory_bytes)
                    (focused/'reconciled-tests.json').write_text(json.dumps({'source_inventory_sha256':hashlib.sha256(inventory_bytes).hexdigest(),'required_selectors':[REQUIRED,FOCUS,CONTRAST],'methods':{c+'#regression':'passed' for c in selected},'sha':SHA,'api':str(api),'selectors':selected,'counts':{c:{'passed':1,'failed':0,'errors':0,'skipped':0} for c in selected}}))
                    if partial_methods:
                        data=json.loads((focused/'reconciled-tests.json').read_text())
                        data['methods'].pop(REQUIRED+'#regression')
                        (focused/'reconciled-tests.json').write_text(json.dumps(data))
                    if skipped_required_method:
                        inventory['runtime_methods'].append(REQUIRED+'#skippedRequired')
                        inventory_bytes=json.dumps(inventory).encode()
                        (focused/'expected-methods.json').write_bytes(inventory_bytes)
                        data=json.loads((focused/'reconciled-tests.json').read_text())
                        data['source_inventory_sha256']=hashlib.sha256(inventory_bytes).hexdigest()
                        data['methods'][REQUIRED+'#skippedRequired']='skipped'
                        data['counts'][REQUIRED]['skipped']=1
                        (focused/'reconciled-tests.json').write_text(json.dumps(data))
                    if contradictory:
                        data=json.loads((focused/'reconciled-tests.json').read_text());data['counts'][REQUIRED]['failed']=1
                        (focused/'reconciled-tests.json').write_text(json.dumps(data))
                    if malformed:(focused/'reconciled-tests.json').write_text('{broken')
                    (focused/'test-input-apks.sha256').write_text('independently-built-test-inputs')
                    if repetitions > 1:
                        iterations = focused/'iterations'
                        iterations.mkdir()
                        summary = {'sha': SHA, 'api': str(api), 'requested': repetitions, 'iterations': []}
                        for index in range(1, repetitions + 1):
                            folder = iterations/f'iteration-{index:02d}'/'prototype-evidence'
                            shutil.copytree(focused, folder, ignore=shutil.ignore_patterns('iterations'))
                            if changed_iteration_apks and index == 1:
                                (folder/'test-input-apks.sha256').write_text('changed APK pair')
                            raw = (folder/'reconciled-tests.json').read_bytes()
                            item = {'iteration': index, 'status': 'passed', 'exit_code': 0,
                                    'receipt_sha256': hashlib.sha256(raw).hexdigest()}
                            if bad_iteration and index == 1:
                                item['status'] = 'failed'
                            summary['iterations'].append(item)
                        if method_mode:
                            summary.update(kind='methods', methods=[AFFECTED+'#regression'], gate={
                                'class': AFFECTED, 'status': 'failed' if missing_gate else 'passed', 'exit_code': 0,
                                'receipt_sha256': hashlib.sha256((focused/'reconciled-tests.json').read_bytes()).hexdigest()})
                            method_inventory={'selectors':[AFFECTED], 'runtime_methods':[AFFECTED+'#regression']}
                            method_bytes=json.dumps(method_inventory).encode()
                            (iterations/'requested-methods.json').write_bytes(method_bytes)
                            for item in summary['iterations']:
                                folder=iterations/f"iteration-{item['iteration']:02d}"/'prototype-evidence'
                                repeated=json.loads((folder/'reconciled-tests.json').read_text())
                                repeated.update(selectors=[AFFECTED], required_selectors=[],
                                    methods={AFFECTED+'#regression':'failed' if bad_method else 'passed'},
                                    counts={AFFECTED:{'passed':1,'failed':0,'errors':0,'skipped':0}},
                                    source_inventory_sha256=hashlib.sha256(method_bytes).hexdigest())
                                (folder/'expected-methods.json').write_bytes(method_bytes)
                                (folder/'reconciled-tests.json').write_text(json.dumps(repeated))
                                item['receipt_sha256']=hashlib.sha256((folder/'reconciled-tests.json').read_bytes()).hexdigest()
                                observed=folder/'live/observed'
                                observed.mkdir(parents=True)
                                (observed/'manifest.json').write_text(json.dumps({'sha':SHA,'api':str(api),
                                    'status':'completed','child_returncode':0,'observer_exit_status':0,'observed_failure_lines':0,
                                    'command_id':'same' if duplicate_command else str(item['iteration'])}))
                        if missing_iteration:
                            summary['iterations'].pop(0)
                        (iterations/'summary.json').write_text(json.dumps(summary))
                    compatibility=evidence/f'prototype-compatibility-api-{api}';compatibility.mkdir()
                    (compatibility/'provenance.json').write_text(json.dumps({'source_sha':SHA}))
            env=dict(os.environ,PROTOTYPE_METHODS=AFFECTED+'#regression' if method_mode else '',PROTOTYPE_REPEAT=str(repetitions),CHECKS=json.dumps(checks),KNOWN_ISSUES='Not a release',AFFECTED_CLASSES=','.join(affected_classes),COVERAGE_REASON='Queue regression',GITHUB_SHA=SHA,GITHUB_REF='refs/heads/test',GITHUB_RUN_ID='1',GITHUB_RUN_ATTEMPT=decision_attempt,GITHUB_STEP_SUMMARY=str(root/'summary'))
            result=subprocess.run(['bash','-c',script],cwd=root,env=env,capture_output=True,text=True)
            out=root/'prototype-delivery'
            manifest=json.loads((out/'manifest.json').read_text()) if (out/'manifest.json').exists() else None
            return result,manifest,list(out.glob('*.apk'))

    def test_five_class_repeat_manifest_passes_and_names_changed_apks(self):
        affected=[AFFECTED]+['io.github.anastaci1a.localica.Affected'+str(i)+'Test' for i in range(4)]
        result,manifest,apks=self.decision(repetitions=3,affected_classes=affected)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertEqual(5,len(manifest['requested_affected_classes']))
        result,manifest,apks=self.decision(repetitions=3,affected_classes=affected,changed_iteration_apks=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)
        self.assertIn('Focused API 26 evidence is not passing: AssertionError: API 26 iteration 1: '
                      'Iteration test APK digests differ from the focused receipt',manifest['validation_errors'])

    def test_every_delivery_validator_assertion_has_a_diagnostic_message(self):
        import ast, yaml
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        script=next(step['run'] for step in workflow['jobs']['evidence']['steps']
                    if step.get('name') == 'Record passed, failed and not-run checks')
        tree=ast.parse('\n'.join(script.splitlines()[1:-1]))
        assertions=[node for node in ast.walk(tree) if isinstance(node,ast.Assert)]
        self.assertEqual(51,len(assertions))
        self.assertTrue(all(node.msg is not None for node in assertions))

    def test_method_delivery_requires_gate_and_exact_repeated_methods(self):
        result, manifest, apks = self.decision(repetitions=2, method_mode=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertEqual([AFFECTED+'#regression'], manifest['requested_methods'])
        for mismatch in ('missing_gate', 'bad_method', 'duplicate_command', 'missing_iteration'):
            with self.subTest(mismatch=mismatch):
                result, manifest, apks = self.decision(repetitions=2, method_mode=True, **{mismatch: True})
                self.assertFalse(manifest['ready_for_prototype_delivery'])
                self.assertEqual([], apks)

    def test_repeated_delivery_requires_every_iteration(self):
        result, manifest, apks = self.decision(repetitions=3)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertEqual(3, manifest['requested_iterations'])
        for mismatch in ('bad_iteration', 'missing_iteration'):
            with self.subTest(mismatch=mismatch):
                result, manifest, apks = self.decision(repetitions=3, **{mismatch: True})
                self.assertFalse(manifest['ready_for_prototype_delivery'])
                self.assertEqual([], apks)

    def test_delivery_requires_all_five_green_and_exact_evidence(self):
        result,manifest,apks=self.decision()
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertFalse(manifest['release_certified'])
        self.assertEqual(1,len(apks))
        self.assertEqual('not-run',manifest['full_native_api_36'])

    def test_single_job_retry_reuses_matching_earlier_build(self):
        result,manifest,apks=self.decision(build_attempt='1',decision_attempt='2')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertTrue(manifest['ready_for_prototype_delivery'])
        self.assertEqual('1',manifest['build_run_attempt'])
        self.assertEqual('2',manifest['run_attempt'])
        self.assertEqual(1,len(apks))

    def test_single_job_retry_rejects_mismatched_build_provenance(self):
        for invalid in ({'wrong_digest':True}, {'wrong_sha':True}, {'build_run':'2'},
                        {'build_attempt':'3'}, {'build_attempt':'0'}, {'build_attempt':'invalid'}):
            with self.subTest(invalid=invalid):
                result,manifest,apks=self.decision(decision_attempt='2',**invalid)
                self.assertNotEqual(0,result.returncode)
                self.assertFalse(manifest['ready_for_prototype_delivery'])
                self.assertTrue(any('Canonical APK provenance' in error
                                    for error in manifest['validation_errors']))
                self.assertEqual([],apks)

    def test_failed_check_produces_manifest_without_delivery_apk(self):
        result,manifest,apks=self.decision(failed=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual('failed',manifest['checks']['focused_36'])
        self.assertEqual([],apks)

    def test_missing_receipts_block_green_statuses(self):
        result,manifest,apks=self.decision(missing=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)

    def test_contradictory_receipts_block_green_statuses(self):
        result,manifest,apks=self.decision(contradictory=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertTrue(manifest['validation_errors'])
        self.assertEqual([],apks)

    def test_malformed_receipts_still_produce_held_manifest(self):
        result,manifest,apks=self.decision(malformed=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertTrue(manifest['validation_errors'])
        self.assertEqual([],apks)

    def test_class_counts_cannot_hide_an_omitted_method(self):
        result,manifest,apks=self.decision(partial_methods=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)

    def test_a_passing_method_cannot_hide_a_skipped_required_regression(self):
        result,manifest,apks=self.decision(skipped_required_method=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertEqual([],apks)

    def test_wrong_sha_blocks_delivery(self):
        result,manifest,apks=self.decision(wrong_sha=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(manifest['ready_for_prototype_delivery'])
        self.assertTrue(manifest['validation_errors'])
        self.assertEqual([],apks)

    def test_compatibility_archive_keeps_the_original_report_root(self):
        import yaml
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        for api in (26,36):
            steps=workflow['jobs'][f'compatibility_{api}']['steps']
            upload=next(s for s in steps if s.get('with',{}).get('name')==f'prototype-compatibility-api-{api}')
            self.assertEqual('${{ runner.temp }}/shuffle-compatibility/reports/',upload['with']['path'])
            preserve=next(s for s in steps if s.get('name')=='Preserve compatibility runner identity')
            self.assertEqual('always()',preserve['if'])
            self.assertIn('reports/prototype-runner-environment.txt',preserve['run'])

    def test_selected_gate_bounds_hangs_and_retains_original_menu_images(self):
        import yaml
        jobs=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())['jobs']
        for api in (26,36):
            job=jobs[f'focused_{api}']
            self.assertEqual("${{ inputs.repeat_methods != '' && 60 || inputs.repeat > 1 && 360 || 35 }}",job['timeout-minutes'])
            upload=next(s for s in job['steps']
                if s.get('with',{}).get('name')==f'prototype-focused-api-{api}')
            self.assertEqual('always()',upload['if'])
            self.assertEqual('error',upload['with']['if-no-files-found'])
            paths=upload['with']['path'].splitlines()
            self.assertIn('app/build/outputs/connected_android_test_additional_output/',paths)
            self.assertIn('app/build/outputs/androidTest-results/',paths)
            self.assertIn('prototype-evidence/',paths)
            self.assertIn('prototype-iterations/',paths)
            self.assertEqual(60,jobs[f'compatibility_{api}']['timeout-minutes'])
        self.assertEqual(20,jobs['build']['timeout-minutes'])
        self.assertEqual(10,jobs['evidence']['timeout-minutes'])

    def test_prototype_build_compiles_both_variants_with_the_android_static_checks_heap(self):
        # #636: debug and release Kotlin compile concurrently in one Kotlin daemon,
        # which inherits the Gradle heap; the 2g default ran out of memory.
        import re, yaml
        prototype=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())['jobs']['build']
        android=yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())['jobs']['static-checks']
        build=next(s for s in prototype['steps'] if s.get('name')=='Build and check')['run']
        checks=next(s for s in android['steps'] if s.get('name')=='Unit tests, lint and release build')['run']
        for task in ('testDebugUnitTest','lintDebug','assembleDebug','assembleRelease'):
            self.assertIn(task,build.split())
        heap=lambda run: re.search(r"(-Dorg\.gradle\.jvmargs='[^']*')",run).group(1)
        self.assertEqual("-Dorg.gradle.jvmargs='-Xmx6g -Dfile.encoding=UTF-8'",heap(build))
        self.assertEqual(heap(checks),heap(build))
        self.assertIn('--max-workers=4',build.split())
        self.assertNotIn('kotlin.daemon.jvmargs',build)

    def test_full_shards_bound_hangs_and_summary_rejects_non_success(self):
        import yaml
        jobs = yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())['jobs']
        shards = jobs['device-tests']
        self.assertEqual(35, shards['timeout-minutes'])
        self.assertFalse(shards['strategy']['fail-fast'])
        self.assertFalse(shards.get('continue-on-error', False))
        summary = jobs['device-tests-summary']
        self.assertIn('always()', summary['if'])
        self.assertIn('device-tests', summary['needs'])
        gate = next(step for step in summary['steps']
                    if step.get('name') == 'Require every shard job to have succeeded')
        self.assertEqual('always()', gate['if'])
        self.assertFalse(gate.get('continue-on-error', False))
        # GitHub exposes timed-out jobs as failure (or cancellation) in needs.result.
        for shard_result in ('success', 'failure', 'cancelled', 'skipped'):
            for static_result in ('success', 'failure'):
                with self.subTest(shard=shard_result, static=static_result):
                    script = gate['run'].replace('${{ needs.device-tests.result }}', shard_result)
                    script = script.replace('${{ needs.static-checks.result }}', static_result)
                    result = subprocess.run(['bash', '-e', '-c', script], capture_output=True)
                    self.assertEqual(shard_result == static_result == 'success', result.returncode == 0)

    def test_android_full_gate_retains_complete_additional_output_and_xml(self):
        import yaml
        workflow = yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())
        steps = workflow['jobs']['device-tests']['steps']
        upload = next(step for step in steps
                      if step.get('with', {}).get('name') == 'device-reports-api-${{ matrix.api }}-shard-${{ matrix.shard }}')
        self.assertEqual('always()', upload['if'])
        paths = upload['with']['path'].splitlines()
        self.assertIn('app/build/outputs/connected_android_test_additional_output/', paths)
        self.assertIn('app/build/outputs/androidTest-results/', paths)
        self.assertIn('app/build/reports/androidTests/', paths)

    def test_native_emulator_failures_retain_crash_database_and_kernel_output(self):
        import yaml
        for filename, jobs in [('android.yml', ('device-tests',)),
                               ('prototype.yml', ('focused_26', 'focused_36'))]:
            workflow = yaml.safe_load((ROOT/'.github/workflows'/filename).read_text())
            for job in jobs:
                with self.subTest(workflow=filename, job=job):
                    steps = workflow['jobs'][job]['steps']
                    emulator = next(step for step in steps if step.get('uses', '').startswith(
                        'reactivecircus/android-emulator-runner@'))
                    self.assertIn('-show-kernel', emulator['with']['emulator-options'])
                    uploads = [step for step in steps if step.get('uses', '').startswith(
                        ('actions/upload-artifact@', './.github/actions/upload-artifact-retry')) and step.get('with', {}).get('path') ==
                        '/tmp/android-runner/emu-crash-*/']
                    self.assertEqual(1, len(uploads), 'Missing retained emulator crash database')
                    self.assertIn(uploads[0]['if'], ('failure()', 'always()'))
                    self.assertEqual('warn', uploads[0]['with']['if-no-files-found'])

    def test_api26_avoids_guest_kernel_dma_path_in_every_emulator_lane(self):
        import yaml
        for filename in ('android.yml', 'prototype.yml'):
            workflow = yaml.safe_load((ROOT/'.github/workflows'/filename).read_text())
            lanes = 0
            for name, job in workflow['jobs'].items():
                for step in job.get('steps', []):
                    if not step.get('uses', '').startswith('reactivecircus/android-emulator-runner@'):
                        continue
                    lanes += 1
                    options = step['with']['emulator-options']
                    with self.subTest(workflow=filename, job=name):
                        api = str(step['with']['api-level'])
                        if api == '26':
                            self.assertIn('-feature -GLDMA', options)
                        elif api == '36':
                            self.assertNotIn('GLDMA', options)
                        else:
                            self.assertEqual('${{ matrix.api }}', api)
                            self.assertIn("${{ matrix.api == 26 && '-feature -GLDMA' || '' }}", options)
                        self.assertIn('-memory 3072', options)
            self.assertEqual(3 if filename == 'android.yml' else 4, lanes)

    def test_opt_in_only_and_five_required_jobs(self):
        import yaml
        workflow=yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        self.assertEqual({'workflow_dispatch','workflow_call'},set(workflow['on']))
        self.assertEqual({'affected_classes','coverage_reason','known_issues','repeat','repeat_methods','runner'},set(workflow['on']['workflow_call']['inputs']))
        self.assertEqual({'ANDROID_DEBUG_KEYSTORE','LASTFM_API_KEY'},set(workflow['on']['workflow_call']['secrets']))
        self.assertFalse(workflow['on']['workflow_call']['secrets']['LASTFM_API_KEY']['required'])
        self.assertEqual({'contents':'read'},workflow['permissions'])
        jobs=workflow['jobs']
        self.assertEqual({'build','focused_26','focused_36','compatibility_26','compatibility_36'},set(jobs['evidence']['needs']))
        self.assertEqual('${{ always() }}',jobs['evidence']['if'])
        for name in ('focused_26','focused_36'):
            script='\n'.join(s.get('with',{}).get('script','') for s in jobs[name]['steps'])
            self.assertIn('bash .github/run-prototype-tests.sh',script)
            self.assertNotIn('run-device-tests.sh',script)
        for name in ('compatibility_26','compatibility_36'):
            self.assertTrue(any('bash .github/compatibility/run.sh' in s.get('with',{}).get('script','') for s in jobs[name]['steps']))
        self.assertNotIn('release',jobs)
        for job in jobs.values():
            for step in job['steps']:
                if 'run' in step:
                    subprocess.run(['bash','-n'],input=step['run'],text=True,check=True)
                if 'uses' in step:
                    if step['uses'].startswith('./.github/actions/'):
                        action_file = (ROOT / step['uses'][2:] / 'action.yml').resolve()
                        self.assertTrue(action_file.is_relative_to((ROOT / '.github/actions').resolve()))
                        action = yaml.safe_load(action_file.read_text())
                        self.assertEqual('composite', action['runs']['using'])
                        for nested in action['runs']['steps']:
                            if 'run' in nested:
                                subprocess.run(['bash', '-n'], input=nested['run'], text=True, check=True)
                            if 'uses' in nested:
                                self.assertRegex(nested['uses'], r'@[0-9a-f]{40}$')
                    else:
                        self.assertRegex(step['uses'],r'@[0-9a-f]{40}$')



class CompatibilityRunnerContract(unittest.TestCase):
    def module(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('compatibility_runner', ROOT/'.github/compatibility/instrumentation_runner.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_archived_runner_is_explicit_reviewed_and_required_in_provenance(self):
        module = self.module()
        for runner in sorted(module.SUPPORTED):
            self.assertEqual(runner, module.from_source('        testInstrumentationRunner = "'+runner+'"\n'))
        for text in ['', 'testInstrumentationRunner = provider()',
                     'testInstrumentationRunner = "unknown.Runner"',
                     'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"\n'*2]:
            with self.assertRaises(ValueError): module.from_source(text)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'provenance.json'
            for value in [None, '', 'unknown.Runner', ['androidx.test.runner.AndroidJUnitRunner']]:
                path.write_text(json.dumps({'instrumentation_runner': value}))
                with self.assertRaises(ValueError): module.from_provenance(path)
            path.write_text('{}')
            with self.assertRaises(KeyError): module.from_provenance(path)

    def test_apk_requires_one_exact_candidate_runner_and_target(self):
        import sys
        package = 'io.github.anastaci1a.localica.debug'
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); badging = root/'badging.txt'; manifest = root/'manifest.txt'
            badging.write_text("package: name='"+package+".test' versionCode='0'\n")
            def block(name, target):
                return ('E: instrumentation (line=1)\n'
                        '  A: android:name(0x01010003)="'+name+'"\n'
                        '  A: android:targetPackage(0x01010021)="'+target+'"\n')
            def check(expected, actual, target=package, copies=1):
                manifest.write_text(block(actual, target)*copies)
                return subprocess.run([sys.executable, str(ROOT/'.github/compatibility/check-apk.py'),
                    str(badging), 'test', '1900000001', str(manifest), expected], capture_output=True).returncode
            runners = sorted(self.module().SUPPORTED)
            for runner in runners:
                self.assertEqual(0, check(runner, runner))
                self.assertNotEqual(0, check(runner, next(x for x in runners if x != runner)))
                self.assertNotEqual(0, check(runner, runner, target='wrong.package'))
                self.assertNotEqual(0, check(runner, runner, copies=0))
                self.assertNotEqual(0, check(runner, runner, copies=2))
            self.assertNotEqual(0, check('unknown.Runner', 'unknown.Runner'))
            runner = runners[0]
            primary = block(runner, package)
            def run(text):
                manifest.write_text(text)
                return subprocess.run([sys.executable, str(ROOT/'.github/compatibility/check-apk.py'),
                    str(badging), 'test', '1900000001', str(manifest), runner], capture_output=True).returncode
            extra = block('io.github.anastaci1a.localica.StartupProcessDiagnostics', package+'.test')
            self.assertEqual(0, run(primary+extra))
            self.assertEqual(0, run(extra+primary))
            self.assertNotEqual(0, run(primary+extra+extra))
            self.assertNotEqual(0, run(primary+block('io.github.anastaci1a.localica.StartupProcessDiagnostics', package)))
            self.assertNotEqual(0, run(primary+block('other.Controller', package+'.test')))
            self.assertNotEqual(0, run(extra))
            badging.write_text("package: name='wrong.package.test' versionCode='0'\n")
            self.assertNotEqual(0, check(runners[0], runners[0]))

    def test_every_compatibility_invocation_uses_verified_runner_without_resident_opt_in(self):
        import hashlib
        import sys
        for runner in sorted(self.module().SUPPORTED):
            with self.subTest(runner=runner), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); output = root/'shuffle-compatibility'; reports = output/'reports'
                reports.mkdir(parents=True); binary = root/'bin'; binary.mkdir()
                variants = {phase: {'version_code': 1900000001+i} for i, phase in enumerate(('seed','bridge','verify'))}
                (reports/'provenance.json').write_text(json.dumps({'instrumentation_runner':runner,'variants':variants}))
                for phase in variants:
                    target = output/'apks'/phase; target.mkdir(parents=True)
                    for kind in ('app','test'):
                        apk = target/f'compatibility-{phase}-{kind}.apk'; apk.write_bytes(b'fixture')
                        apk.with_suffix('.apk.sha256').write_text(hashlib.sha256(b'fixture').hexdigest()+'  '+apk.name+'\n')
                adb = binary/'adb'
                adb.write_text('#!'+sys.executable+'\n'+r'''
import json, os, pathlib, sys
root=pathlib.Path(os.environ['MOCK_COMPAT_ROOT']); args=sys.argv[1:]
if args[0]=='install':
    phase=pathlib.Path(args[-1]).parent.name
    (root/'phase').write_text(phase); print('Success')
elif args[:3]==['shell','dumpsys','package']:
    print('versionCode='+str(1900000001+['seed','bridge','verify'].index((root/'phase').read_text())))
elif args[:3]==['shell','pm','path']: print('package:/fixture/base.apk')
elif args[:3]==['shell','am','instrument']:
    assert args[-1]=='io.github.anastaci1a.localica.debug.test/'+os.environ['EXPECTED_RUNNER']
    assert not any('localicaOwnedSession' in arg for arg in args)
    with (root/'commands.jsonl').open('a') as out: out.write(json.dumps(args)+'\n')
    selector=args[args.index('class')+1]
    count=int(os.environ['EXPECTED_LEGACY_COUNT']) if selector.endswith('LegacyJourneyCompatibilityTest') else 6 if selector.endswith('MetadataRefreshJournalTest') else 1
    for _ in range(count): print('INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0')
    print('INSTRUMENTATION_CODE: -1\nOK (%d tests)'%count)
else: raise SystemExit('Unexpected fixture adb command: '+repr(args))
''')
                adb.chmod(0o755)
                env = dict(os.environ, RUNNER_TEMP=str(root), GITHUB_WORKSPACE=str(ROOT),
                    MOCK_COMPAT_ROOT=str(root), EXPECTED_RUNNER=runner,
                    EXPECTED_LEGACY_COUNT=str((ROOT/'.github/compatibility/LegacyJourneyCompatibilityTest.kt').read_text().count('@Test fun ')),
                    PATH=str(binary)+os.pathsep+os.environ['PATH'])
                result = subprocess.run(['bash', str(ROOT/'.github/compatibility/run.sh')], env=env,
                    capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stdout+result.stderr)
                commands = [json.loads(line) for line in (root/'commands.jsonl').read_text().splitlines()]
                self.assertEqual(12, len(commands))
                self.assertTrue(all(command[-1].endswith('/'+runner) for command in commands))


class RepeatContract(unittest.TestCase):
    def test_bounds(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('repeat_runner', (ROOT / '.github/run-prototype-repeat.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for value in ('1', '20', '30'):
            self.assertEqual(int(value), module.repeat_count(value))
        for value in ('0', '31', '-1', '1.5', '01', '', '1\n', 'abc'):
            with self.assertRaises(ValueError):
                module.repeat_count(value)

    def run_case(self, fail_at=0, missing_at=0, count=3):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / '.github').mkdir()
        (root / '.github/run-prototype-tests.sh').write_text('''#!/bin/bash
set -eu
[[ "$PROTOTYPE_REPEAT" == 1 ]]
[[ ! -e prototype-evidence && ! -e app/build/outputs/androidTest-results ]]
n=$(cat calls 2>/dev/null || echo 0)
n=$((n+1))
echo "$n" > calls
mkdir -p prototype-evidence app/build/outputs/androidTest-results app/build/reports/androidTests app/build/outputs/connected_android_test_additional_output
printf '<testsuite iteration="%s"/>' "$n" > app/build/outputs/androidTest-results/result.xml
printf '{"iteration":%s}' "$n" > prototype-evidence/reconciled-tests.json
if [[ "$n" == "$MISSING_AT" ]]; then rm prototype-evidence/reconciled-tests.json; fi
if [[ "$n" == "$FAIL_AT" ]]; then exit 7; fi
''')
        env = dict(os.environ, PROTOTYPE_REPEAT=str(count), GITHUB_SHA='a'*40,
                   TEST_API='26', FAIL_AT=str(fail_at), MISSING_AT=str(missing_at))
        result = subprocess.run(['python3', str((ROOT / '.github/run-prototype-repeat.py'))], cwd=root, env=env, capture_output=True, text=True)
        summary = json.loads((root / 'prototype-evidence/iterations/summary.json').read_text())
        return root, result, summary

    def test_twenty_independent_iterations_keep_xml(self):
        root, result, summary = self.run_case(count=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(20, len(summary['iterations']))
        for index, item in enumerate(summary['iterations'], 1):
            self.assertEqual('passed', item['status'])
            xml = root / f'prototype-evidence/iterations/iteration-{index:02d}/androidTest-results/result.xml'
            self.assertIn(f'iteration="{index}"', xml.read_text())
        self.assertEqual({'iteration': 20}, json.loads((root / 'prototype-evidence/reconciled-tests.json').read_text()))

    def test_failure_preserves_xml_and_never_retries(self):
        root, result, summary = self.run_case(fail_at=2)
        self.assertEqual(7, result.returncode)
        self.assertEqual(['passed', 'failed', 'not-run'], [i['status'] for i in summary['iterations']])
        self.assertEqual('2', (root / 'calls').read_text().strip())
        self.assertTrue((root / 'prototype-evidence/iterations/iteration-02/androidTest-results/result.xml').exists())

    def test_cancellation_retains_partial_iteration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.github').mkdir()
            (root / '.github/run-prototype-tests.sh').write_text('''#!/bin/bash
trap 'exit 143' TERM
mkdir prototype-evidence
touch ready
while true; do sleep .05; done
''')
            env = dict(os.environ, PROTOTYPE_REPEAT='3', GITHUB_SHA='a'*40, TEST_API='26')
            child = subprocess.Popen(['python3', str((ROOT / '.github/run-prototype-repeat.py'))], cwd=root, env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 5
                while not (root/'ready').exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue((root/'ready').exists())
                child.send_signal(signal.SIGTERM)
                child.communicate(timeout=5)
                self.assertEqual(143, child.returncode)
                summary = json.loads((root/'prototype-evidence/iterations/summary.json').read_text())
                self.assertEqual(['failed', 'not-run', 'not-run'], [i['status'] for i in summary['iterations']])
            finally:
                if child.poll() is None:
                    child.kill()
                    child.communicate()

    def test_missing_receipt_fails_closed(self):
        _, result, summary = self.run_case(missing_at=1)
        self.assertEqual(2, result.returncode)
        self.assertEqual(['failed', 'not-run', 'not-run'], [i['status'] for i in summary['iterations']])


class RiskInventoryContract(unittest.TestCase):
    """The reviewed prototype inventory names only existing top-level test classes, once each."""

    def test_every_listed_class_exists_once(self):
        lines = (ROOT/'.github/prototype-risk-classes.txt').read_text().splitlines()
        names = [line for line in lines if line.startswith('io.')]
        self.assertEqual(sorted(set(names)), sorted(names), 'Duplicate inventory lines')
        source = ROOT/'app/src/androidTest/java/io/github/anastaci1a/localica'
        prefix = 'io.github.anastaci1a.localica.'
        missing = []
        for name in names:
            self.assertTrue(name.startswith(prefix) and '.' not in name[len(prefix):], name)
            simple = name[len(prefix):]
            path = source/(simple+'.kt')
            if not path.is_file() or ('class '+simple) not in path.read_text():
                missing.append(name)
        self.assertEqual([], missing, 'Inventory names classes without a test source')

if __name__=='__main__':unittest.main()
