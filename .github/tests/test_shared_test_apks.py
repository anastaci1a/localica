"""Contracts for a single immutable native APK build shared by every shard."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class SharedApksTest(unittest.TestCase):
    def test_workflow_builds_once_and_binds_every_shard(self):
        import yaml
        jobs = yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())['jobs']
        producer = '\n'.join(step.get('run', '') for step in jobs['test-apks']['steps'])
        delivery = '\n'.join(step.get('run', '') for step in jobs['build']['steps'])
        shard = '\n'.join(step.get('run', '') for step in jobs['device-tests']['steps'])
        self.assertIn('assembleDebugAndroidTest', producer)
        self.assertIn('shared-test-apks.py freeze', producer)
        # The R8 delivery APK builds in parallel with, not before, the instrumentation APKs.
        self.assertNotIn('assembleDistribution', producer)
        self.assertIn('assembleDistribution', delivery)
        self.assertNotIn('assembleDebugAndroidTest', delivery)
        self.assertNotIn('shared-test-apks.py', delivery)
        self.assertNotIn('assembleDebug', shard)
        self.assertEqual({'identity', 'test-apks'}, set(jobs['device-tests']['needs']))
        self.assertIn('shared-test-apks.py verify', shard)
        self.assertIn('needs.test-apks.outputs.test-apks-sha256', str(jobs['device-tests']))

    def test_archive_rejects_changed_bytes_metadata_identity_and_manifest(self):
        helper = ROOT/'.github/shared-test-apks.py'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            paths = ['app/build/outputs/apk/debug/app-debug.apk',
                     'app/build/outputs/apk/debug/output-metadata.json',
                     'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
                     'app/build/outputs/apk/androidTest/debug/output-metadata.json',
                     'app/build/generated/standalone-test-runtime/kotlin-stdlib-standalone.jar',
                     'app/build/generated/standalone-test-runtime/binding.txt',
                     'app/build/generated/shared-test-apks/test-classes.zip']
            for path in paths:
                file = root/path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(path)
            compiled = root/'app/build/tmp/kotlin-classes/debugAndroidTest/example/Test.class'
            compiled.parent.mkdir(parents=True)
            compiled.write_bytes(b'producer class fixture')
            env = dict(os.environ, GITHUB_SHA='a'*40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1')
            def run(*args):
                return subprocess.run(['python3', str(helper), *args], cwd=root, env=env, capture_output=True)
            result = run('freeze', '--output', 'bundle')
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = root/'bundle/manifest.json'
            digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
            self.assertEqual(0, run('verify', '--bundle', 'bundle', '--digest', digest).returncode)
            for path in paths:
                target = root/'bundle'/path
                original = target.read_bytes()
                target.write_bytes(b'tampered')
                self.assertNotEqual(0, run('verify', '--bundle', 'bundle', '--digest', digest).returncode)
                target.write_bytes(original)
            env['GITHUB_SHA'] = 'b'*40
            self.assertNotEqual(0, run('verify', '--bundle', 'bundle', '--digest', digest).returncode)
            env['GITHUB_SHA'] = 'a'*40
            env['GITHUB_RUN_ID'] = '124'
            self.assertNotEqual(0, run('verify', '--bundle', 'bundle', '--digest', digest).returncode)
            env['GITHUB_RUN_ID'] = '123'
            manifest.write_text(manifest.read_text()+' ')
            self.assertNotEqual(0, run('verify', '--bundle', 'bundle', '--digest', digest).returncode)

class PrebuiltRunnerTest(unittest.TestCase):
    def setUp(self):
        from test_full_live_evidence import FullLiveEvidenceTest
        self.fixture=FullLiveEvidenceTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        root=self.fixture.root
        paths=sorted(root.glob('app/build/outputs/apk/**/*.apk'))
        (root/'native-test-apks.sha256').write_text(''.join(
            hashlib.sha256(path.read_bytes()).hexdigest()+'  '+str(path.relative_to(root))+'\n'
            for path in paths))

    def test_prebuilt_mode_passes_init_script_and_preserves_normal_xml(self):
        fixture=self.fixture
        fixture.run_runner(LOCALICA_PREBUILT_TEST_APKS='true')
        self.assertIn('-I',fixture.state['gradleArgs'][0])
        self.assertIn('.github/prebuilt-device-tests.gradle',fixture.state['gradleArgs'][0])
        self.assertTrue((fixture.root/'app/build/outputs/androidTest-results/TEST.xml').is_file())

    def test_changed_apk_fails_green_execution(self):
        self.fixture.run_runner(3,LOCALICA_PREBUILT_TEST_APKS='true',MOCK_CHANGE_APK='true')

    def test_changed_apk_keeps_original_native_failure(self):
        self.fixture.state['gradleExits']=[7]
        self.fixture.run_runner(7,LOCALICA_PREBUILT_TEST_APKS='true',MOCK_CHANGE_APK='true')

if __name__ == '__main__':
    unittest.main()
