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
        import yaml
        summary = yaml.safe_load(text)['jobs']['device-tests-summary']
        self.assertEqual({'identity', 'device-tests', 'test-apks', 'build', 'static-checks',
                          'verification-reuse'}, set(summary['needs']))

    def test_workflow_release_uses_exact_tree_selector(self):
        text=(ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('python3 .github/release-verification.py select',text)
        self.assertIn('python3 .github/release-verification.py record',text)
        self.assertIn('name: complete-verification',text)

    def test_release_assets_use_pre_release_name(self):
        text=(ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('artifacts/localica-$GITHUB_REF_NAME-pre-release.apk',text)
        self.assertIn('"localica-$GITHUB_REF_NAME-pre-release.apk.sha256"',text)
        self.assertNotIn('localica-$GITHUB_REF_NAME-debug',text)

    def test_split_provisional_union_requires_every_failed_shard_and_exact_identity(self):
        import hashlib
        import io
        import json
        from unittest.mock import patch
        import zipfile

        self.run['conclusion'] = 'failure'
        jobs = {job['name']: dict(job, id=index+1, started_at='2026-11-19T00:00:00Z',
                                  completed_at='2026-11-19T00:01:00Z')
                for index, job in enumerate(self.jobs)}
        for name in ('device-tests-summary', 'device-tests (36, 0)'):
            jobs[name]['conclusion'] = 'failure'
        self.unions['36'].update(passed=8, failure=1)
        progress = '\n'.join('2026-11-19T00:00:00Z '+line for entry in self.unions.values()
                             for line in json.dumps(entry, indent=2).splitlines()).encode()
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, 'w') as archive:
            archive.writestr('app/build/outputs/androidTest-results/TEST-fixture.xml',
                             '<testsuite><testcase classname="Example" name="case">'
                             '<failure>java.lang.AssertionError: held witness expected:&lt;0&gt; but was:&lt;1&gt;\n'
                             'at Example.case(Example.kt:42)</failure></testcase></testsuite>')
        archive_bytes = payload.getvalue()
        artifact = dict(name='device-reports-api-36-shard-0', expired=False, id=99,
                        size_in_bytes=len(archive_bytes), created_at='2026-11-19T00:00:30Z',
                        digest='sha256:'+hashlib.sha256(archive_bytes).hexdigest(),
                        workflow_run={'id':42,'head_sha':self.sha})
        with patch.object(module, 'gh', side_effect=[progress, archive_bytes]):
            unions, failed = module.provisional_native('anastaci1a/localica', self.run, jobs, [artifact])
        self.assertEqual(unions, self.unions)
        witness = b'java.lang.AssertionError: held witness expected:<0> but was:<1>'
        self.assertEqual(failed, {('36','Example#case'):hashlib.sha256(witness).hexdigest()})
        jobs['device-tests (36, 0)']['conclusion'] = 'success'
        with patch.object(module, 'gh', side_effect=[progress, archive_bytes]):
            self.assertEqual(module.provisional_native('anastaci1a/localica', self.run, jobs, [artifact]),
                             (unions, failed))
        with patch.object(module, 'gh', return_value=progress):
            with self.assertRaisesRegex(ValueError, 'artifact'):
                module.provisional_native('anastaci1a/localica', self.run, jobs, [])
        with patch.object(module, 'gh', return_value=b'no union'):
            with self.assertRaisesRegex(ValueError, 'complete unions'):
                module.provisional_native('anastaci1a/localica', self.run, jobs, [artifact])

        run = dict(self.run, id=44665987162)
        jobs['device-tests (36, 0)']['conclusion'] = 'success'
        job = jobs['device-tests (36, 6)']
        job.update(id=136057552596, conclusion='cancelled')
        archive = dict(artifact, name='device-reports-api-36-shard-6', id=14600427762,
                       digest='sha256:80980caeea641aa5f698affeece0644ddbb7d9cf7eda0061e24c9e55c28ba23a',
                       workflow_run={'id': run['id'], 'head_sha': self.sha})
        union = self.unions['36']
        union.update(expected=2637, executed_once=2637, passed=2635, deferred=[])
        wrong_shard = jobs['device-tests (36, 7)']
        cases = (
            ('pinned', {}, {}, None),
            ('wrong run', run, {'id': 43}, 'shard did not finish'),
            ('wrong attempt', run, {'run_attempt': 2}, 'shard did not finish'),
            ('wrong job', job, {'id': 99}, 'shard did not finish'),
            ('wrong status', job, {'conclusion': 'timed_out'}, 'shard did not finish'),
            ('second canceled shard', wrong_shard, {'conclusion': 'cancelled'}, 'shard did not finish'),
            ('incomplete union', union, {'executed_once': 2636}, 'Incomplete provisional native union'),
            ('wrong union size', union, {'expected': 2636, 'executed_once': 2636, 'passed': 2634},
             'shard did not finish'),
            ('deferred method', union, {'deferred': ['Example#other']}, 'shard did not finish'),
            ('missing archive', archive, {'expired': True}, 'retained artifact'),
            ('wrong archive', archive, {'id': 101}, 'Pinned provisional native archive mismatch'),
            ('wrong archive digest', archive, {'digest': 'sha256:'+'c'*64},
             'Pinned provisional native archive mismatch'),
        )
        for name, target, overrides, error in cases:
            with self.subTest(name=name), patch.dict(target, overrides):
                progress = '\n'.join('2026-11-19T00:00:00Z '+line for entry in self.unions.values()
                                     for line in json.dumps(entry, indent=2).splitlines()).encode()
                with patch.object(module, 'gh', return_value=progress), \
                     patch.object(module, 'archived_artifact',
                                  side_effect=lambda *_: zipfile.ZipFile(io.BytesIO(archive_bytes))) as opened:
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            module.provisional_native('anastaci1a/localica', run, jobs, [archive])
                    else:
                        self.assertEqual(module.provisional_native('anastaci1a/localica', run, jobs, [archive]),
                                         (self.unions, failed))
                        opened.assert_called_once_with('anastaci1a/localica', archive)

    def test_split_tag_cannot_be_activated_for_another_ref_or_actor(self):
        from unittest.mock import patch

        with patch.dict(module.os.environ, {'GITHUB_ACTOR':'other','GITHUB_REF':'refs/tags/v0.34.16'}):
            with self.assertRaisesRegex(ValueError, 'release maintainer'):
                module.select_v03416('anastaci1a/localica', self.sha, self.tree)
        with patch.dict(module.os.environ, {'GITHUB_ACTOR':'anastaci1a','GITHUB_REF':'refs/tags/v0.34.17'}):
            with self.assertRaisesRegex(ValueError, 'v0.34.16 tag'):
                module.select_v03416('anastaci1a/localica', self.sha, self.tree)

    def test_carried_native_scope_requires_ancestor_coverage_and_final_local_replays(self):
        from copy import deepcopy
        from unittest.mock import patch

        repository = 'anastaci1a/localica'
        prior = 'fca7f179700c6853509d919b103cdc3e57cf4139'
        changed = 'io.github.anastaci1a.localica.ChangedTest'
        untouched = 'io.github.anastaci1a.localica.UntouchedTest'
        held_method = untouched + '#priorFailure'
        packet = dict(affected_classes=[changed], carried_classes=[untouched], scoped_runs=[10],
                      carried_scoped_runs=[9], local={api: dict(sha=self.sha, exit=0, receipt_sha256='a'*64,
                          classes=[changed], passed_methods=[held_method]) for api in ('26', '36')})
        final_scope = ({'26': {changed}, '36': set()}, {'26': set(), '36': set()})
        prior_scope = ({'26': {untouched}, '36': {untouched}}, {'26': set(), '36': set()})
        domains = (final_scope, prior_scope)
        ancestor = dict(status='ahead', merge_base_commit={'sha':prior})
        failed = {('26', held_method):'b'*64}
        with patch.object(module, 'scoped_native_coverage', side_effect=domains) as coverage, \
             patch.object(module, 'gh', return_value=ancestor):
            module.verify_native_scope(repository, self.sha, packet, failed)
        self.assertEqual([call.args for call in coverage.call_args_list],
                         [(repository, self.sha, [10]), (repository, prior, [9])])

        overlap = deepcopy(packet)
        overlap['affected_classes'].append(untouched)
        stale_replay = deepcopy(packet)
        stale_replay['local']['26']['sha'] = prior
        missing_replay = deepcopy(packet)
        missing_replay['local']['26']['passed_methods'] = []
        cases = (
            ('ancestor', packet, domains, dict(status='diverged'), 'not an ancestor'),
            ('coverage', packet, (final_scope, ({'26': {untouched}, '36': set()}, prior_scope[1])),
             ancestor, 'Carried hosted class gap'),
            ('overlap', overlap, domains, ancestor, 'dependency partition'),
            ('source', stale_replay, domains, ancestor, 'held-failure verification incomplete'),
            ('replay', missing_replay, domains, ancestor, 'held-failure verification incomplete'),
        )
        for name, inputs, coverage, comparison, error in cases:
            with self.subTest(name=name), patch.object(module, 'scoped_native_coverage', side_effect=coverage), \
                 patch.object(module, 'gh', return_value=comparison), self.assertRaisesRegex(ValueError, error):
                module.verify_native_scope(repository, self.sha, inputs, failed)
        touched_failure = changed + '#priorFailure'
        touched_packet = deepcopy(packet)
        touched_packet['local']['26']['passed_methods'].append(touched_failure)
        with patch.object(module, 'scoped_native_coverage', side_effect=domains), \
             self.assertRaisesRegex(ValueError, 'hosted API 26 held method did not pass'):
            module.verify_native_scope(repository, self.sha, touched_packet, {('26', touched_failure):'b'*64})

    def test_native_scope_accepts_only_disclosed_source_bound_inherited_assertions(self):
        from unittest.mock import patch

        affected = 'io.github.anastaci1a.localica.PopupChromeDepthTest'
        method = affected + '#normalSelectionPillPixelsCoverOverlappingTabs'
        notes = '- `PopupChromeDepthTest#normalSelectionPillPixelsCoverOverlappingTabs` reported 61–69 pixels.'
        held = {('36', method): 'b'*64}
        entry = dict(method=method, base_sha='4d0d8bdf93b75c28fd230c353d0feec77058d141', final_sha=self.sha,
                     base_receipt_sha256='c'*64, final_receipt_sha256='d'*64,
                     base_assertion_sha256='e'*64, final_assertion_sha256='e'*64)
        packet = dict(affected_classes=[affected], carried_classes=[], scoped_runs=[10], carried_scoped_runs=[],
                      local={api: dict(sha=self.sha, exit=0, receipt_sha256='a'*64,
                                       classes=[affected], passed_methods=[]) for api in ('26', '36')})
        coverage = ({'26': {affected}, '36': set()}, {'26': set(), '36': set()})

        cases = (
            ('inherited', {}, notes, None),
            ('changed signature', {'final_assertion_sha256': 'f'*64}, notes, 'differs from its approved base'),
            ('untrusted base', {'base_sha': 'b'*40}, notes, 'differs from its approved base'),
            ('stale source', {'final_sha': 'b'*40}, notes, 'differs from its approved base'),
            ('missing base receipt', {'base_receipt_sha256': ''}, notes, 'differs from its approved base'),
            ('missing final receipt', {'final_receipt_sha256': ''}, notes, 'differs from its approved base'),
            ('wrong method', {'method': affected + '#other'}, notes, 'Invalid inherited local method outcome'),
            ('no method disclosure', {}, '- PopupChromeDepthTest ran.', 'missing from release notes'),
        )
        with patch.object(module, 'scoped_native_coverage', return_value=coverage):
            for name, overrides, release_notes, error in cases:
                with self.subTest(name=name):
                    local = packet['local']['36']
                    local['inherited_methods'] = [dict(entry, **overrides)]
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            module.verify_native_scope('anastaci1a/localica', self.sha, packet, held, release_notes)
                    else:
                        module.verify_native_scope('anastaci1a/localica', self.sha, packet, held, release_notes)

            local['inherited_methods'] = [entry, entry]
            with self.assertRaisesRegex(ValueError, 'Invalid inherited local method outcome'):
                module.verify_native_scope('anastaci1a/localica', self.sha, packet, held, notes)
            local['inherited_methods'] = [entry]
            local['passed_methods'] = [method]
            with self.assertRaisesRegex(ValueError, 'Invalid inherited local method outcome'):
                module.verify_native_scope('anastaci1a/localica', self.sha, packet, held, notes)
            del local['inherited_methods']
            packet['local']['26']['inherited_methods'] = [entry]
            coverage[1]['26'].add(method)
            api26_held = {('26', method): 'b'*64}
            with self.assertRaisesRegex(ValueError, 'Invalid inherited local method outcome'):
                module.verify_native_scope('anastaci1a/localica', self.sha, packet, api26_held, notes)

    def test_scoped_native_coverage_admits_only_authenticated_passes(self):
        from io import BytesIO
        import json
        from unittest.mock import patch
        from zipfile import ZipFile

        requested = 'io.github.anastaci1a.localica.Sample'
        method = requested + '#witness'
        manifest = dict(sha=self.sha, run_id='42', run_attempt='1', validation_errors=[], build_only=False,
                        requested_methods=[], checks={'focused_26':'passed'}, focused_evidence={'26':
                            dict(sha=self.sha, api=26, selectors=[requested], methods={method:'passed'})})
        job = dict(conclusion='success', started_at='1', completed_at='3')
        cases = (
            (None, None, None), ('sha', 'x'*40, 'whole-class proof'), ('build_only', True, 'whole-class proof'),
            ('requested_methods', [method], 'whole-class proof'), ('outcome', 'failed', 'scope did not pass'),
            ('outcome', 'skipped', None), ('unrecognized_api', None, 'scope did not pass'),
        )
        for field, value, error in cases:
            data = copy.deepcopy(manifest)
            if field == 'outcome':
                data['focused_evidence']['26']['methods'][method] = value
            elif field == 'unrecognized_api':
                data['focused_evidence']['99'] = data['focused_evidence'].pop('26')
            elif field:
                data[field] = value
            blob = BytesIO()
            with ZipFile(blob, 'w') as archive:
                archive.writestr('manifest.json', json.dumps(data))
            with self.subTest(field=field, value=value), \
                 patch.object(module, 'delta_run', return_value=(self.run, {'Prototype evidence and delivery decision':job})), \
                 patch.object(module, 'pages', return_value=[]), \
                 patch.object(module, 'artifact', return_value={'created_at':'2'}), \
                 patch.object(module, 'archived_artifact', return_value=ZipFile(blob)):
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        module.scoped_native_coverage('anastaci1a/localica', self.sha, [42])
                else:
                    coverage, passes = module.scoped_native_coverage('anastaci1a/localica', self.sha, [42])
                    self.assertEqual(coverage, {'26':{requested}, '36':set()})
                    self.assertEqual(passes['26'], set() if value == 'skipped' else {method})

    def test_split_selector_packet_and_jobs_fail_closed(self):
        import json
        from unittest.mock import patch

        method = 'io.github.anastaci1a.localica.Sample#witness'
        held = {('36', method):'e'*64}
        packet = dict(version='v0.34.16', source_sha=self.sha, tree=self.tree, provisional_run=44665987162,
                      schema=1, apk_sha256='c'*64, review_sha256='d'*64, final_build_run=9,
                      held_failures=[dict(api='36', method=method, held_signature_sha256='e'*64)],
                      provisional_a35=dict(sha=self.sha, restore_exit=0, receipt_sha256='1'*64, exit=0, pairs=5),
                      a35=dict(sha=self.sha, apk_sha256='c'*64, exit=0, smoke_exit=0, restore_exit=0,
                              receipt_sha256='2'*64))
        jobs = {job['name']:job for job in self.jobs}
        jobs['Commit identities and attribution'] = {'conclusion':'success'}
        final = dict(self.run, id=9, head_branch='proof/sol-release-tonight-final-case')
        cases = (
            ('green', None), ('stopped', None), ('tag_type', 'annotated tag'), ('tag_commit', 'tagged commit'),
            ('source_sha', 'reviewed split annotation'), ('tree', 'reviewed split annotation'),
            ('provisional_run', 'reviewed split annotation'), ('schema', 'reviewed split annotation'),
            ('held_failures', 'reviewed held disclosure'), ('final_branch', 'Final signed distribution'),
            ('final_job', 'Final signed distribution'), ('phone_stop', 'Provisional A35 disposition'),
            ('phone_sha', 'Final A35 proof'),
        )
        for mutation, error in cases:
            body, final_jobs, final_run = copy.deepcopy(packet), copy.deepcopy(jobs), final.copy()
            if mutation in ('source_sha', 'tree', 'provisional_run', 'schema', 'held_failures'):
                body[mutation] = [] if mutation == 'held_failures' else 0
            if mutation == 'final_branch':
                final_run['head_branch'] = 'proof/untrusted-alias'
            if mutation == 'final_job':
                final_jobs['distribution-smoke (26)']['conclusion'] = 'skipped'
            if mutation in ('stopped', 'phone_stop'):
                body['provisional_a35'].update(exit=1, pairs=1, decision='a35-restart-2226' if mutation == 'stopped' else 'unknown')
            if mutation == 'phone_sha':
                body['a35']['apk_sha256'] = '0'*64
            tag_ref = {'object':{'type':'commit' if mutation == 'tag_type' else 'tag', 'sha':'f'*40}}
            annotation = dict(object={'type':'commit', 'sha':'f'*40 if mutation == 'tag_commit' else self.sha},
                              tag='v0.34.16', message=json.dumps(body))
            with self.subTest(mutation=mutation), \
                 patch.dict(module.os.environ, GITHUB_ACTOR='anastaci1a', GITHUB_REF='refs/tags/v0.34.16'), \
                 patch.object(module, 'gh', side_effect=[tag_ref, annotation]), \
                 patch.object(module, 'delta_run', side_effect=[(self.run, jobs), (final_run, final_jobs)]), \
                 patch.object(module, 'pages', return_value=[]), patch.object(module, 'artifact', return_value={}), \
                 patch.object(module, 'provisional_native', return_value=(self.unions, held)), \
                 patch.object(module, 'verify_native_scope') as native_scope, patch.object(module, 'release_output') as output:
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        module.select_v03416('anastaci1a/localica', self.sha, self.tree)
                    output.assert_not_called()
                else:
                    module.select_v03416('anastaci1a/localica', self.sha, self.tree)
                    notes = (ROOT/'.github/RELEASE_NOTES.md').read_text(encoding='utf-8')
                    native_scope.assert_called_once_with('anastaci1a/localica', self.sha, body, held, notes)
                    self.assertEqual(output.call_args.args[:2], (9, body['apk_sha256']))

    def test_delta_workflow_keeps_distribution_and_compatibility_gates(self):
        import yaml

        jobs = yaml.safe_load((ROOT/'.github/workflows/android.yml').read_text())['jobs']
        with_mode = {name for name, job in jobs.items() if 'release_v03416_delta' in job.get('if', '')}
        self.assertEqual(with_mode, {'device-tests', 'device-tests-summary'})
        for name in with_mode:
            self.assertIn("!(github.event_name == 'workflow_dispatch' && inputs.release_v03416_delta)", jobs[name]['if'])
        for name in ('build', 'test-apks', 'static-checks', 'compatibility', 'distribution-smoke'):
            job = jobs[name]
            self.assertNotIn('device-tests-summary', job.get('needs', []))
            self.assertFalse(any('release_v03416_delta' in step.get('if', '') for step in job['steps']))
        guard = next(step for step in jobs['identity']['steps'] if 'release_v03416_delta' in step.get('if', ''))
        self.assertIn("github.event_name == 'workflow_dispatch'", guard['if'])
        for anchor in ('compatibility', 'v0.34.16', 'proof/sol-release-tonight-final-'):
            self.assertIn(anchor, guard['run'] + str(guard.get('env', {})))

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
