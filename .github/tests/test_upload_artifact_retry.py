"""Offline upload retry contracts; upload results are mocked, failure shell is real."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
ACTION = './.github/actions/upload-artifact-retry'
PIN = 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02'


class UploadRetryContract(unittest.TestCase):
    def setUp(self):
        self.action = yaml.safe_load((ROOT / ACTION / 'action.yml').read_text())

    def exercise(self, outcomes, path):
        """Walk the actual action conditions, mocking only upload calls and sleep."""
        steps = {}
        calls, delays, results = [], [], []
        for step in self.action['runs']['steps']:
            condition = step['if']
            self.assertTrue(condition.startswith('always()'))
            match = re.fullmatch(r"always\(\)(?: && steps\.(\w+)\.outcome == 'failure')?", condition)
            self.assertIsNotNone(match, condition)
            if match[1] and steps.get(match[1], 'skipped') != 'failure':
                continue
            if 'uses' in step:
                self.assertEqual(PIN, step['uses'])
                self.assertTrue(step['continue-on-error'])
                self.assertEqual('${{ inputs.name }}', step['with']['name'])
                self.assertEqual('${{ inputs.path }}', step['with']['path'])
                self.assertEqual('${{ inputs.if-no-files-found }}', step['with']['if-no-files-found'])
                self.assertEqual(bool(calls), step['with'].get('overwrite', False))
                steps[step['id']] = outcomes[len(calls)]
                calls.append(step['id'])
            elif step['run'].startswith('sleep '):
                delays.append(int(step['run'].split()[1]))
            else:
                self.assertEqual({'ARTIFACT': '${{ inputs.name }}',
                                  'PATHS': '${{ inputs.path }}'}, step['env'])
                result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']],
                                        env={**os.environ, 'ARTIFACT': 'contract', 'PATHS': path},
                                        capture_output=True, text=True)
                results.append(result)
        return calls, delays, results

    def test_first_success_skips_both_retries(self):
        self.assertEqual((['first'], [], []), self.exercise(['success'], '/unused'))

    def test_first_failure_recovers_on_second_attempt(self):
        self.assertEqual((['first', 'second'], [15], []),
                         self.exercise(['failure', 'success'], '/unused'))

    def test_two_failures_recover_on_last_attempt(self):
        self.assertEqual((['first', 'second', 'third'], [15, 45], []),
                         self.exercise(['failure', 'failure', 'success'], '/unused'))

    def test_exhaustion_fails_and_classifies_infrastructure(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / 'report.txt'
            artifact.write_text('report')
            calls, delays, results = self.exercise(['failure'] * 3, str(artifact))
        self.assertEqual(['first', 'second', 'third'], calls)
        self.assertEqual([15, 45], delays)
        self.assertEqual(1, len(results))
        self.assertEqual(1, results[0].returncode)
        self.assertIn('::error title=Infrastructure: artifact upload (#670)', results[0].stdout)

    def test_missing_files_are_not_mislabeled_as_outage(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, results = self.exercise(['failure'] * 3, directory + '/missing-*')
        self.assertEqual(1, results[0].returncode)
        self.assertIn('::error title=Missing artifact files', results[0].stdout)
        self.assertNotIn('::error title=Infrastructure', results[0].stdout)

    def test_every_target_workflow_upload_retries_after_checkout(self):
        for filename in ('android.yml', 'prototype.yml'):
            workflow = yaml.safe_load((ROOT / '.github/workflows' / filename).read_text())
            uploads = 0
            for job in workflow['jobs'].values():
                checkout = False
                for step in job.get('steps', []):
                    action = step.get('uses', '')
                    if action.startswith('actions/checkout@'):
                        checkout = True
                    self.assertFalse(action.startswith('actions/upload-artifact@'), filename)
                    if action == ACTION:
                        self.assertTrue(checkout, filename)
                        self.assertFalse(step.get('continue-on-error', False))
                        self.assertTrue(set(step['with']) <= set(self.action['inputs']))
                        uploads += 1
            self.assertGreater(uploads, 0, filename)

    def test_contract_is_required_by_tooling_workflow(self):
        workflow = (ROOT / '.github/workflows/tooling-contracts.yml').read_text()
        self.assertIn("'test_upload_artifact_retry.py'", workflow)


if __name__ == '__main__':
    unittest.main()
