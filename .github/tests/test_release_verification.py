"""Release admission requires exact trees and complete trusted producer evidence."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release_verification', ROOT/'.github/release-verification.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseVerificationTest(unittest.TestCase):
    def setUp(self):
        self.sha, self.tree = 'a'*40, 'b'*40
        self.run = dict(id=42, run_attempt=1, head_sha=self.sha, status='completed', conclusion='success',
                        event='workflow_dispatch', path='.github/workflows/android.yml',
                        repository={'full_name':'anastaci1a/localica'}, head_repository={'full_name':'anastaci1a/localica'})
        self.jobs = [dict(name=name, conclusion='success', head_sha=self.sha, run_id=42, run_attempt=1)
                     for name in module.REQUIRED_JOBS]
        self.receipt = dict(schema_version=1, sha=self.sha, tree=self.tree, run_id='42', attempt='1',
                            scope='complete_native_union', apk_sha256='c'*64)
        self.unions = {api: dict(api=api, shards=module.DEVICE_SHARDS, xml_roots=module.DEVICE_SHARDS, expected=10, executed_once=10,
                                passed=9, skipped=1, failure=0, error=0) for api in ('26','36')}

    def validate(self):
        module.validate(self.run, self.jobs, self.receipt, self.unions, 'anastaci1a/localica', self.tree, {'26':10,'36':10})

    def test_identical_tree_integration_run_with_complete_union_is_eligible(self):
        self.validate()

    def test_wrong_tree_incomplete_union_untrusted_event_and_skipped_job_rejected(self):
        for target, key, value in ((self.receipt,'tree','d'*40), (self.receipt,'scope','risk_only'),
                                   (self.receipt,'attempt','2'), (self.run,'event','pull_request'),
                                   (self.run,'conclusion','failure'), (self.jobs[0],'conclusion','skipped'),
                                   (self.jobs[0],'run_attempt',2), (self.unions['26'],'executed_once',9),
                                   (self.unions['26'],'failure',1), (self.unions['26'],'expected',9)):
            with self.subTest(key=key, value=value):
                original=target[key]; target[key]=value
                with self.assertRaises(ValueError): self.validate()
                target[key]=original
        self.jobs.append(copy.deepcopy(self.jobs[0]))
        with self.assertRaises(ValueError): self.validate()

    def test_wrong_repository_source_and_workflow_rejected(self):
        for target,key,value in ((self.run['repository'],'full_name','someone/fork'),
                                 (self.run['head_repository'],'full_name','someone/fork'),
                                 (self.run,'path','.github/workflows/prototype.yml'),
                                 (self.receipt,'sha','e'*40)):
            original=target[key]; target[key]=value
            with self.assertRaises(ValueError): self.validate()
            target[key]=original

    def test_workflow_shard_count_matches_required_jobs(self):
        text=(ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn(f"LOCALICA_DEVICE_SHARDS: '{module.DEVICE_SHARDS}'",text)
        self.assertIn('shard: ['+', '.join(map(str,range(module.DEVICE_SHARDS)))+']',text)
        self.assertIn('static-checks',module.REQUIRED_JOBS)
        self.assertIn('needs: [device-tests, test-apks, build, static-checks, verification-reuse]',text)

    def test_workflow_release_uses_exact_tree_selector(self):
        text=(ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('python3 .github/release-verification.py select',text)
        self.assertIn('python3 .github/release-verification.py record',text)
        self.assertIn('name: complete-verification',text)

    def test_distribution_smoke_binds_expected_hash_to_the_build_output(self):
        import yaml
        jobs=yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())['jobs']
        step=next(step for step in jobs['distribution-smoke']['steps'] if step.get('name')=='Verify the downloaded APK')
        self.assertEqual('${{ needs.build.outputs.delivery-apk-sha256 }}',step.get('env',{}).get('VERIFIED_APK_SHA256'))
        self.assertIn('sha256sum --check',step['run'])
        self.assertIn('verify-non-debuggable.sh',step['run'])
        import hashlib, os, subprocess, tempfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/'artifacts').mkdir()
            (root/'.github').mkdir()
            (root/'.github/verify-non-debuggable.sh').write_text('exit 0\n')
            apk=b'fixture canonical APK'
            (root/'artifacts/app-debug.apk').write_bytes(apk)
            digest=hashlib.sha256(apk).hexdigest()
            (root/'artifacts/app-debug.apk.sha256').write_text(digest+'  app-debug.apk\n')
            for expected, success in ((digest, True), ('0'*64, False)):
                result=subprocess.run(['bash','-e','-c',step['run']],cwd=root,
                                      env=dict(os.environ,VERIFIED_APK_SHA256=expected),capture_output=True)
                self.assertEqual(success,result.returncode==0,result.stderr)



class ProducerArchiveTest(unittest.TestCase):
    setUp = ReleaseVerificationTest.setUp
    def test_selector_binds_archive_digest_to_exact_tree_and_downloaded_apk(self):
        import contextlib
        import hashlib
        import io
        import json
        import os
        import tempfile
        import zipfile
        from unittest.mock import patch
        self.run['head_sha'] = self.sha
        for job in self.jobs:
            job.update(started_at='2026-11-03T00:00:00Z', completed_at='2026-11-03T00:01:00Z')
        # Model upload-artifact's paths from the real producer, rather than a
        # separately maintained three-member fixture that can silently drift.
        import yaml
        workflow = yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())
        upload = next(step for step in workflow['jobs']['device-tests-summary']['steps']
                      if step.get('with', {}).get('name') == 'complete-verification')
        files = {'verification.json': json.dumps(self.receipt),
                 **{f'union-api-{api}.json': json.dumps(union) for api, union in self.unions.items()},
                 'class-durations.csv': 'api,class,tests,total_seconds,skipped\n26,ExampleTest,1,2,0\n'}
        self.assertEqual(set(files), set(upload['with']['path'].splitlines()))
        data = io.BytesIO()
        with zipfile.ZipFile(data,'w') as archive:
            for path in upload['with']['path'].splitlines():
                archive.writestr(path, files[path])
        data = data.getvalue()
        artifacts = [dict(id=number, name=name, expired=False, digest='sha256:'+hashlib.sha256(data).hexdigest(),
                          workflow_run={'id':42,'head_sha':self.sha}, created_at='2026-11-03T00:00:30Z')
                     for number,name in enumerate(('complete-verification','localica-debug'),1)]
        def api(path,binary=False):
            if path.endswith('/commits/main'): return {'sha':'f'*40}
            if '/compare/' in path: return {'status':'identical'}
            if '/commits/' in path: return {'commit':{'tree':{'sha':self.tree}}}
            if '/runs?' in path: return {'workflow_runs':[self.run]}
            if path.endswith('/runs/42'): return self.run
            if path.endswith('/zip'): return data
            raise AssertionError(path)
        def pages(path,key): return self.jobs if key=='jobs' else artifacts
        with tempfile.TemporaryDirectory() as temp:
            old = Path.cwd()
            os.chdir(temp)
            try:
                output = Path(temp)/'output'
                with patch.dict(os.environ, GITHUB_REPOSITORY='anastaci1a/localica',GITHUB_OUTPUT=str(output)), \
                     patch.object(module,'gh',side_effect=api), patch.object(module,'pages',side_effect=pages), \
                     patch.object(module,'expected_for',return_value=(None,None,range(10))), \
                     patch.object(module.subprocess,'check_output',side_effect=['e'*40+'\n',self.tree+'\n']), \
                     contextlib.redirect_stdout(io.StringIO()):
                    module.select()
                provenance=json.loads(Path('verified-provenance.json').read_text())
                self.assertEqual('e'*40, provenance['release_commit'])
                self.assertEqual(self.sha, provenance['producer_receipt']['sha'])
                self.assertIn('run-id=42',output.read_text())
                artifacts[0]['digest']='sha256:'+'0'*64
                with patch.dict(os.environ, GITHUB_REPOSITORY='anastaci1a/localica',GITHUB_OUTPUT=str(output)), \
                     patch.object(module,'gh',side_effect=api), patch.object(module,'pages',side_effect=pages), \
                     patch.object(module,'expected_for',return_value=(None,None,range(10))), \
                     patch.object(module.subprocess,'check_output',side_effect=['e'*40+'\n',self.tree+'\n']), \
                     contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(ValueError): module.select()
            finally: os.chdir(old)

if __name__ == '__main__': unittest.main()
