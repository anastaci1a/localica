"""Contracts for a single immutable native APK build shared by every shard."""
import hashlib
import io
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]

class SharedApksTest(unittest.TestCase):
    def test_prototype_reuse_is_opt_in_and_gates_only_compilation(self):
        import yaml
        workflow = yaml.safe_load((ROOT/'.github/workflows/prototype.yml').read_text())
        for event in ('workflow_dispatch', 'workflow_call'):
            self.assertEqual({'description': 'Optional earlier prototype run with a verified signed APK pair for this exact source SHA',
                              'required': False, 'default': '', 'type': 'string'},
                             workflow['on'][event]['inputs']['apk_run'])
        self.assertEqual('read', workflow['permissions']['actions'])
        for name in ('build', 'focused_26', 'focused_36'):
            steps = workflow['jobs'][name]['steps']
            reuse = next(step for step in steps if step.get('id') == 'reuse-apks')
            self.assertEqual("inputs.apk_run != ''", reuse['if'])
            self.assertEqual('${{ inputs.apk_run }}', reuse['env']['APK_RUN'])
            self.assertEqual('python3 .github/shared-test-apks.py reuse --run "$APK_RUN"', reuse['run'])
            compile = next(step for step in steps if 'assembleDebug' in step.get('run', ''))
            self.assertEqual("steps.reuse-apks.outputs.reused != 'true'", compile['if'])
            self.assertLess(steps.index(reuse), steps.index(compile))
        steps = workflow['jobs']['build']['steps']
        freeze = next(step for step in steps if step.get('name') == 'Freeze shared instrumentation inputs')
        upload = next(step for step in steps if step.get('with', {}).get('name') == 'prototype-signed-instrumentation-pair')
        self.assertIn('shared-test-apks.py freeze --output prototype-shared-test-apks', freeze['run'])
        self.assertIn('prototype-shared-test-apks/', upload['with']['path'])
        self.assertLess(steps.index(freeze), steps.index(upload))

    def test_prior_run_reuse_verifies_all_inputs_and_falls_back_on_mismatch(self):
        spec = importlib.util.spec_from_file_location('shared_apks', ROOT/'.github/shared-test-apks.py')
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old = Path.cwd()
            os.chdir(root)
            self.addCleanup(os.chdir, old)
            env = dict(GITHUB_SHA='a'*40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                       GITHUB_REPOSITORY='example/repo', GITHUB_OUTPUT=str(root/'output'))
            with mock.patch.dict(os.environ, env), mock.patch('sys.stdout', new_callable=io.StringIO):
                for name in helper.PATHS[:-1]:
                    path = Path(name)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b'producer bytes')
                compiled = Path('app/build/tmp/kotlin-classes/debugAndroidTest/example/Test.class')
                compiled.parent.mkdir(parents=True)
                compiled.write_bytes(b'compiled inventory')
                helper.freeze(Path('prototype-shared-test-apks'))
                def archive():
                    result = io.BytesIO()
                    with zipfile.ZipFile(result, 'w') as bundle:
                        for path in Path('prototype-shared-test-apks').rglob('*'):
                            if path.is_file():
                                bundle.write(path, str(path))
                    return result.getvalue()
                original = archive()
                for case in ('valid', 'sha', 'workflow', 'failed-build', 'expired', 'artifact-digest',
                             'file-digest', 'manifest-sha', 'manifest-run', 'missing-inventory', 'missing-artifact', 'download'):
                    with self.subTest(case=case):
                        data = original
                        if case in ('file-digest', 'manifest-sha', 'manifest-run', 'missing-inventory'):
                            manifest = Path('prototype-shared-test-apks/manifest.json')
                            saved = manifest.read_bytes()
                            receipt = json.loads(saved)
                            if case == 'file-digest':
                                receipt['files'][helper.PATHS[0]] = '0'*64
                            elif case == 'manifest-sha':
                                receipt['sha'] = 'b'*40
                            elif case == 'manifest-run':
                                receipt['run_id'] = '122'
                            else:
                                del receipt['files'][helper.PATHS[-1]]
                            manifest.write_text(json.dumps(receipt))
                            data = archive()
                            manifest.write_bytes(saved)
                        responses = [json.dumps({'head_sha': ('b' if case == 'sha' else 'a')*40,
                                                 'path': 'other.yml' if case == 'workflow' else '.github/workflows/prototype.yml'}).encode(),
                                     json.dumps({'jobs': [{'name': 'Prototype build, unit tests and lint',
                                                           'conclusion': 'failure' if case == 'failed-build' else 'success'}]}).encode(),
                                     json.dumps({'artifacts': [] if case == 'missing-artifact' else [{'id': 1,
                                         'name': 'prototype-signed-instrumentation-pair', 'expired': case == 'expired',
                                         'digest': 'sha256:'+('0'*64 if case == 'artifact-digest' else hashlib.sha256(data).hexdigest())}]}).encode(),
                                     data]
                        os.environ['GITHUB_RUN_ID'] = '124'
                        Path(helper.PATHS[0]).write_bytes(b'consumer sentinel')
                        side_effect = subprocess.CalledProcessError(1, 'gh') if case == 'download' else responses
                        with mock.patch.object(subprocess, 'check_output', side_effect=side_effect):
                            helper.reuse('123')
                        self.assertEqual('reused='+str(case == 'valid').lower(), Path('output').read_text().splitlines()[-1])
                        self.assertEqual(b'producer bytes' if case == 'valid' else b'consumer sentinel', Path(helper.PATHS[0]).read_bytes())
                        if case == 'valid':
                            self.assertEqual(b'compiled inventory', compiled.read_bytes())
                            self.assertTrue(Path('native-test-apks.sha256').is_file())

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
