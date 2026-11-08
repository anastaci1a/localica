"""The read-only application key reaches APK compilers, never publishers or diagnostics."""
import copy
from pathlib import Path
import re
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
KEY = 'LASTFM_API_KEY'
EXPRESSION = '${{ secrets.LASTFM_API_KEY }}'
BUILD_WORKFLOWS = {'android.yml', 'prototype.yml'}


def produces_apk(step):
    command = step.get('run', '')
    return bool(re.search(r'\./gradlew\b[^\n]*(?:assemble|bundle)', command) or
                'bash .github/compatibility/build.sh' in command)


def validate(name, workflow):
    assert KEY not in workflow.get('env', {}), 'No workflow-wide key environment'
    for job in workflow.get('jobs', {}).values():
        assert KEY not in job.get('env', {}), 'No job-wide key environment'
        for step in job.get('steps', []):
            command = step.get('run', '')
            # Gradle consumes the inherited environment. No command needs to mention or print it.
            assert KEY not in command, 'Commands must never print or interpolate the key'
            assert not re.search(r'(?m)^\s*(?:env|printenv|export\s+-p|declare\s+-x)\s*(?:$|[|;])', command), 'No environment dumps'
            receives = KEY in step.get('env', {})
            needed = name in BUILD_WORKFLOWS and produces_apk(step)
            assert receives == needed, 'Only APK-producing build steps receive the key'
            if receives:
                assert step['env'][KEY] == EXPRESSION, 'The key comes only from the Actions secret'


class LastFmKeyContractTest(unittest.TestCase):
    def test_all_workflows_limit_key_to_apk_build_steps(self):
        for file in sorted((ROOT / '.github/workflows').glob('*.yml')):
            with self.subTest(workflow=file.name):
                validate(file.name, yaml.safe_load(file.read_text()))

    def test_release_distribution_and_reusable_prototype_receive_key(self):
        android = yaml.safe_load((ROOT / '.github/workflows/android.yml').read_text())
        distribution = [step for job in android['jobs'].values() for step in job.get('steps', [])
                        if 'assembleDistribution' in step.get('run', '')]
        self.assertEqual(1, len(distribution))
        self.assertEqual(EXPRESSION, distribution[0]['env'][KEY])
        # The tag release publishes the canonical distribution artifact; publishing needs no key.
        self.assertNotIn(KEY, android['jobs']['release'].get('env', {}))
        prototype = yaml.safe_load((ROOT / '.github/workflows/prototype.yml').read_text())
        trigger = prototype.get('on', prototype.get(True))
        self.assertFalse(trigger['workflow_call']['secrets'][KEY]['required'])
        # Any future reusable caller must explicitly forward the key or inherit secrets.
        for file in (ROOT / '.github/workflows').glob('*.yml'):
            for job in yaml.safe_load(file.read_text()).get('jobs', {}).values():
                if job.get('uses', '').endswith('/prototype.yml'):
                    self.assertTrue(job.get('secrets') == 'inherit' or KEY in job.get('secrets', {}))

    def test_contract_rejects_broader_scopes_prints_and_missing_injection(self):
        source = yaml.safe_load((ROOT / '.github/workflows/prototype.yml').read_text())
        for mutation in ('workflow', 'job', 'echo', 'dump', 'missing', 'publisher'):
            with self.subTest(mutation=mutation):
                workflow = copy.deepcopy(source)
                job = workflow['jobs']['build']
                build = next(step for step in job['steps'] if produces_apk(step))
                if mutation == 'workflow': workflow['env'] = {KEY: EXPRESSION}
                elif mutation == 'job': job['env'] = {KEY: EXPRESSION}
                elif mutation == 'echo': build['run'] += '\necho "$LASTFM_API_KEY"'
                elif mutation == 'dump': build['run'] += '\nprintenv'
                elif mutation == 'missing': del build['env'][KEY]
                else: job['steps'].append({'run': 'echo published', 'env': {KEY: EXPRESSION}})
                with self.assertRaises(AssertionError): validate('prototype.yml', workflow)

    def test_runner_diagnostics_do_not_dump_credentials(self):
        diagnostics = (ROOT / '.github/test-evidence/environment.sh').read_text()
        self.assertNotIn(KEY, diagnostics)
        self.assertNotRegex(diagnostics, r'(?m)^\s*(?:env|printenv|export\s+-p|declare\s+-x)\s*$')


if __name__ == '__main__':
    unittest.main()
