"""Offline actual-runner/observer/supervisor contracts; adb, Gradle and publisher are stubs."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('guard_contract_fixture', ROOT/'.github/tests/test_prepare_device_tests.py')
guard_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard_fixture)
SHA = 'c8d5f906505c0d5dd6b67509d98760ef0a6ef96c'
SELECTED = 'io.github.anastaci1a.localica.SelectedTest'
FULL_ARGS = ['connectedDebugAndroidTest', '--no-daemon', '--max-workers=2']

GRADLE = r'''#!/usr/bin/env python3
import json, os, pathlib, signal, sys
root=pathlib.Path(os.environ['MOCK_ROOT']);p=root/'state.json';s=json.loads(p.read_text())
s['gradle']+=1
s.setdefault('gradleArgs',[]).append(sys.argv[1:]);p.write_text(json.dumps(s))
(root/('gradle-started-'+str(s['gradle']))).write_text('started')
def cancelled(sig, frame):
    (root/'gradle-cancelled').write_text(str(sig));sys.exit(128+sig)
signal.signal(signal.SIGTERM,cancelled);signal.signal(signal.SIGINT,cancelled)
if os.environ.get('MOCK_BLOCK')=='true':
    while True:signal.pause()
for folder in ('app/build/reports/androidTests','app/build/outputs/androidTest-results','app/build/outputs/connected_android_test_additional_output'):
    path=root/folder;path.mkdir(parents=True,exist_ok=True);(path/'stub.txt').write_text('owned stub evidence')
code=s['gradleExits'][min(s['gradle']-1,len(s['gradleExits'])-1)]
if code or os.environ.get('MOCK_OBSERVED_FAILURE')=='true':print('io.github.anastaci1a.localica.SelectedTest > actualFailure[owned-device] FAILED',flush=True)
sys.exit(code)
'''

NODE = r'''#!/usr/bin/env python3
import json, os, pathlib, sys, time
args=sys.argv[2:];options=dict(zip(args[::2],args[1::2]))
source=pathlib.Path(options['--evidence-dir']);state=pathlib.Path(options['--state-dir']);state.mkdir()
(state/'argv.json').write_text(json.dumps(sys.argv[1:]))
for _ in range(1000):
    try:observed=json.loads((source/'manifest.json').read_text())
    except (OSError,ValueError):time.sleep(.01);continue
    if observed['status']!='running':break
    time.sleep(.01)
else:sys.exit(12)
if os.environ.get('MOCK_PUBLISHER_FAILURE')=='true' or observed['status'] in ('cancelled','incomplete'):
    (state/'monitoring-degraded.json').write_text('{}');sys.exit(9)
count=observed['observed_failure_lines']
packets=1 if count or os.environ.get('MOCK_EMPTY_PACKET')=='true' else 0
if packets:
    (state/'packet-1.json').write_text('{}');(state/'receipt-1.json').write_text('{}')
complete=dict(status='monitoring_complete',sha=observed['sha'],api=observed['api'],lane=observed['lane'],command_id=observed['command_id'],observed_failures=count,observed_failure_lines=count,observer_status=observed['status'],uploaded_packets=packets,read_offset=(source/'events.jsonl').stat().st_size)
(state/'complete.json').write_text(json.dumps(complete))
'''


class FullLiveEvidenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root/'.github').symlink_to(ROOT/'.github', target_is_directory=True)
        adb = guard_fixture.MOCK.replace(
            "if a[:2]!=['-s','emulator-5554']:",
            """if a==['shell','getprop','ro.kernel.qemu']:done(out=s['qemu']+'\\n')
if len(a)==5 and a[:4]==['shell','pm','list','packages'] and a[4] in ('io.github.anastaci1a.localica.debug','io.github.anastaci1a.localica.debug.test'):done(out='package:'+a[4]+'\\n')
if len(a)==4 and a[:3]==['shell','pm','clear'] and a[3] in ('io.github.anastaci1a.localica.debug','io.github.anastaci1a.localica.debug.test'):
    s.setdefault('clears',[]).append(a[3]);done(out='Success\\n')
if a[:2]!=['-s','emulator-5554']:""")
        adb = adb.replace("elif s['restoreExit']:done(s['restoreExit'])", """else:
        live=root/'native-live-evidence'
        manifests=list(live.glob('**/manifest.json'))
        if any(json.loads(path.read_text())['status']=='running' for path in manifests):done(97,'Restoration raced active observation')
        if any((live/lane).exists() and not (live/lane/'supervision.json').exists() for lane in ('preflight','full')):done(98,'Restoration raced supervisor')
        s['restoreSawTerminal']=bool(manifests)
        if s['restoreExit']:done(s['restoreExit'])""")
        scripts = {'adb': adb, 'gradlew': GRADLE, 'node': NODE,
                   'git': '#!/usr/bin/env bash\nprintf "%s\\n" "'+SHA+'"\n'}
        for name, text in scripts.items():
            path = self.root/name
            path.write_text(text)
            path.chmod(0o755)
        source = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica/SelectedTest.kt'
        source.parent.mkdir(parents=True)
        source.write_text('class SelectedTest')
        self.state = dict(api='26', qemu='1', sdkExit=0, packages=f'package:{guard_fixture.PACKAGE}\n', enabled=0,
                          token='', boot='mock-boot', mutations=0, gradle=0, gradleExit=0, disableExit=0,
                          restoreExit=0, drift='', gradleExits=[0])
        self.env = dict(os.environ, PATH=str(self.root)+os.pathsep+os.environ['PATH'], MOCK_ROOT=str(self.root),
                        GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted', TEST_API='26',
                        GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1', GITHUB_SHA=SHA,
                        ANDROID_SERIAL='emulator-5554', RISK_CLASSES='', LOCALICA_LIVE_EVIDENCE='true')
        self.proc = None

    def start(self, **env):
        (self.root/'state.json').write_text(json.dumps(self.state))
        self.proc = subprocess.Popen(['bash', str(ROOT/'.github/run-device-tests.sh')], cwd=self.root,
                                     env=dict(self.env, **env), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     text=True, start_new_session=True)
        return self.proc

    def finish(self, expected):
        try:
            out, err = self.proc.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(self.proc.pid, signal.SIGKILL)
            self.proc.communicate()
            self.fail('Owned stub runner did not terminate')
        self.assertEqual(expected, self.proc.returncode, out+'\n'+err)
        self.state = json.loads((self.root/'state.json').read_text())

    def run_runner(self, expected=0, **env):
        self.start(**env)
        self.finish(expected)

    def manifest(self, lane='full'):
        suffix = lane+'/observed'
        return json.loads((self.root/'native-live-evidence'/suffix/'manifest.json').read_text())

    def assert_restored(self):
        self.assertEqual(0, self.state['enabled'])
        self.assertEqual('', self.state['token'])
        self.assertEqual(2, self.state['mutations'])
        self.assertTrue(self.state['restoreSawTerminal'])

    def test_full_is_unfiltered_and_monitoring_complete_before_restore(self):
        self.run_runner()
        self.assertEqual([FULL_ARGS], self.state['gradleArgs'])
        self.assertEqual(FULL_ARGS, self.manifest()['argv'][1:])
        self.assertEqual('native', self.manifest()['lane'])
        self.assertEqual('completed', self.manifest()['status'])
        supervision=json.loads((self.root/'native-live-evidence/full/supervision.json').read_text())
        self.assertTrue(supervision['monitoring_complete'])
        self.assert_restored()
        self.assertNotIn('clears', self.state)

    def test_test_failure_keeps_original_status_even_if_monitoring_and_restore_fail(self):
        self.state.update(gradleExits=[17], restoreExit=23)
        self.run_runner(17, MOCK_PUBLISHER_FAILURE='true')
        self.assertEqual(17, self.manifest()['child_returncode'])
        self.assertEqual(1, self.manifest()['observed_failure_lines'])
        self.assertEqual(3, self.state['enabled'])
        self.assertTrue(self.state['token'])

    def test_api36_full_is_monitored_without_api26_package_mutation(self):
        self.state['api']='36'
        self.run_runner(TEST_API='36')
        self.assertEqual([FULL_ARGS],self.state['gradleArgs'])
        self.assertEqual('36',self.manifest()['api'])
        self.assertEqual(0,self.state['mutations'])
        self.assertEqual('',self.state['token'])

    def test_monitoring_failure_cannot_turn_test_success_green(self):
        self.run_runner(3, MOCK_PUBLISHER_FAILURE='true')
        self.assertEqual(0, self.manifest()['child_returncode'])
        self.assert_restored()

    def test_cleanup_failure_cannot_turn_monitored_test_success_green(self):
        self.state['restoreExit']=23
        self.run_runner(3)
        self.assertTrue(self.state['token'])

    def test_passing_preflight_preserves_selector_then_runs_unfiltered_full(self):
        self.run_runner(RISK_CLASSES=SELECTED)
        self.assertEqual([FULL_ARGS+['-Pandroid.testInstrumentationRunnerArguments.class='+SELECTED],FULL_ARGS],self.state['gradleArgs'])
        self.assertEqual('preflight',self.manifest('preflight')['lane'])
        self.assertEqual('native',self.manifest()['lane'])
        preflight=json.loads((self.root/'native-live-evidence/preflight/publishing/complete.json').read_text())
        self.assertEqual('monitoring_complete',preflight['status'])
        self.assertEqual(0,preflight['uploaded_packets'])
        self.assertEqual(0,preflight['observed_failures'])
        # Existing publisher enforces <=6 for full; this admitted preflight used 0.
        self.assertEqual(0,len(list((self.root/'native-live-evidence/preflight/publishing').glob('packet-*.json'))))
        self.assertEqual(['io.github.anastaci1a.localica.debug','io.github.anastaci1a.localica.debug.test'],self.state['clears'])
        self.assertTrue((self.root/'preflight-reports/app/build/outputs/androidTest-results/stub.txt').exists())
        self.assertEqual((self.root/'preflight-reports/gradle.log').read_bytes(),(self.root/'native-live-evidence/preflight/observed/command.log').read_bytes())
        self.assert_restored()

    def test_failed_preflight_never_clears_or_starts_full(self):
        self.state['gradleExits']=[19]
        self.run_runner(19,RISK_CLASSES=SELECTED)
        self.assertEqual(1,self.state['gradle'])
        self.assertNotIn('clears',self.state)
        self.assertFalse((self.root/'native-live-evidence/full').exists())
        self.assertEqual(19,self.manifest('preflight')['child_returncode'])
        published=json.loads((self.root/'native-live-evidence/preflight/publishing/complete.json').read_text())
        self.assertEqual(1,published['uploaded_packets'])
        self.assert_restored()

    def test_preflight_publisher_failure_blocks_full_and_package_clears(self):
        self.run_runner(3,RISK_CLASSES=SELECTED,MOCK_PUBLISHER_FAILURE='true')
        self.assertEqual(1,self.state['gradle'])
        self.assertNotIn('clears',self.state)
        self.assertFalse((self.root/'native-live-evidence/full').exists())
        self.assertTrue((self.root/'native-live-evidence/preflight/publishing/monitoring-degraded.json').exists())
        self.assert_restored()

    def test_observed_preflight_failure_with_exit_zero_blocks_full_and_preserves_packet(self):
        self.run_runner(3,RISK_CLASSES=SELECTED,MOCK_OBSERVED_FAILURE='true',PYTHONOPTIMIZE='1')
        self.assertEqual(0,self.manifest('preflight')['child_returncode'])
        self.assertEqual(1,self.manifest('preflight')['observed_failure_lines'])
        self.assertEqual(1,self.state['gradle'])
        self.assertNotIn('clears',self.state)
        self.assertFalse((self.root/'native-live-evidence/full').exists())
        self.assertTrue((self.root/'native-live-evidence/preflight/publishing/packet-1.json').exists())
        self.assert_restored()

    def test_zero_event_preflight_with_nonzero_packet_receipt_cannot_spend_full_budget(self):
        self.run_runner(3,RISK_CLASSES=SELECTED,MOCK_EMPTY_PACKET='true')
        self.assertEqual(0,self.manifest('preflight')['observed_failure_lines'])
        self.assertEqual(1,self.state['gradle'])
        self.assertNotIn('clears',self.state)
        self.assertFalse((self.root/'native-live-evidence/full').exists())
        self.assert_restored()

    def test_stale_evidence_refuses_replay_before_guard_or_tests(self):
        root=self.root/'native-live-evidence';root.mkdir();(root/'sentinel').write_text('preserve')
        self.run_runner(2)
        self.assertEqual('preserve',(root/'sentinel').read_text())
        self.assertEqual(0,self.state['mutations'])
        self.assertEqual(0,self.state['gradle'])

    def test_invalid_identity_refuses_before_device_mutation(self):
        self.run_runner(2,GITHUB_SHA='f'*40)
        self.assertEqual(0,self.state['mutations'])
        self.assertEqual(0,self.state['gradle'])

    def test_term_drains_owned_full_process_before_package_restore(self):
        self.check_cancel('')

    def test_term_drains_owned_preflight_process_before_package_restore(self):
        self.check_cancel(SELECTED)

    def test_int_drains_owned_full_process_before_package_restore(self):
        self.check_cancel('', signal.SIGINT)

    def check_cancel(self, selector, sig=signal.SIGTERM):
        self.start(RISK_CLASSES=selector,MOCK_BLOCK='true')
        deadline=time.monotonic()+5
        while not (self.root/'gradle-started-1').exists():
            if self.proc.poll() is not None or time.monotonic()>deadline:
                self.finish(0)
                self.fail('Stub Gradle never reached controlled pending phase')
            time.sleep(.01)
        self.proc.send_signal(sig)
        self.finish(128+sig)
        self.assertTrue((self.root/'gradle-cancelled').exists())
        self.assertIn(self.manifest('preflight' if selector else 'full')['status'],('cancelled','failed'))
        self.assert_restored()

    def test_workflow_pins_tools_always_uploads_and_never_wraps_compatibility(self):
        workflow=(ROOT/'.github/workflows/android.yml').read_text()
        native=workflow.split('  device-tests:\n',1)[1].split('\n  compatibility:',1)[0]
        self.assertIn("LOCALICA_LIVE_EVIDENCE: 'true'",native)
        self.assertIn('actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020',native)
        self.assertIn("node-version: '24'",native)
        self.assertIn('npm ci --prefix .github/test-evidence --ignore-scripts --no-audit --no-fund',native)
        upload=native.split('- name: Preserve native monitoring evidence',1)[1].split('- name: Preserve focused',1)[0]
        self.assertIn('if: always()',upload)
        self.assertIn('path: native-live-evidence/',upload)
        self.assertIn('if-no-files-found: error',upload)
        package=json.loads((ROOT/'.github/test-evidence/package.json').read_text())
        self.assertEqual('6.3.1',package['dependencies']['@actions/artifact'])
        compatibility=workflow.split('\n  compatibility:',1)[1].split('\n  release:',1)[0]
        self.assertIn('bash .github/compatibility/run.sh',compatibility)
        self.assertNotIn('LOCALICA_LIVE_EVIDENCE',compatibility)
        self.assertNotIn('test-evidence/',compatibility)
        self.assertIn('maxArtifacts = 6',(ROOT/'.github/test-evidence/publish.mjs').read_text())


if __name__=='__main__':
    unittest.main(verbosity=2)
