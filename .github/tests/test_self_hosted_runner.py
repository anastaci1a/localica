"""Contract for the opt-in, owner-only self-hosted prototype path (#592)."""
import copy
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / '.github' / 'workflows'
HOSTED_ONLY = "inputs.runner != 'self-hosted'"
GUARD = ("(github.event_name == 'workflow_dispatch' && github.repository == 'anastaci1a/localica' "
         "&& github.actor == 'anastaci1a' && github.triggering_actor == 'anastaci1a' "
         "&& startsWith(github.workflow_ref, 'anastaci1a/localica/.github/workflows/prototype.yml@refs/heads/'))")
RUNS_ON = ("${{ inputs.runner == 'self-hosted' && fromJSON(format('[\"self-hosted\",\"Linux\",\"X64\","
           "\"localica-jit\",\"ljit-{0}\"]', github.run_id)) || 'ubuntu-24.04' }}")


def load(name):
    return yaml.safe_load((WORKFLOWS / name).read_text())


class SelfHostedPrototypeContract(unittest.TestCase):
    def setUp(self):
        self.workflow = load('prototype.yml')
        self.jobs = self.workflow['jobs']

    def test_runner_input_defaults_to_hosted(self):
        dispatch = self.workflow['on']['workflow_dispatch']['inputs']['runner']
        self.assertEqual('hosted', dispatch['default'])
        self.assertEqual(['hosted', 'self-hosted'], dispatch['options'])
        self.assertEqual('hosted', self.workflow['on']['workflow_call']['inputs']['runner']['default'])
        self.assertEqual({'workflow_dispatch', 'workflow_call'}, set(self.workflow['on']))

    def test_only_focused_jobs_can_reach_the_self_hosted_runner_and_only_for_owner_dispatch(self):
        self.assertEqual('${{ inputs.runner != \'self-hosted\' || ' + GUARD + ' }}', self.jobs['focused_26']['if'])
        self.assertEqual('${{ !cancelled() && (inputs.runner != \'self-hosted\' || ' + GUARD + ') }}',
                         self.jobs['focused_36']['if'])
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                if name in ('focused_26', 'focused_36'):
                    self.assertEqual(RUNS_ON, job['runs-on'])
                    # The bundled read-only key is the sole exception; no signing or machine credentials.
                    audited = copy.deepcopy(job)
                    for step in audited['steps']:
                        env = step.get('env', {})
                        if 'LASTFM_API_KEY' in env:
                            self.assertEqual('Compile instrumentation APKs before starting emulator', step['name'])
                            self.assertEqual('${{ secrets.LASTFM_API_KEY }}', env.pop('LASTFM_API_KEY'))
                    self.assertNotIn('secrets.', yaml.safe_dump(audited))
                else:
                    self.assertEqual('ubuntu-24.04', job['runs-on'])

    def test_no_other_workflow_targets_a_self_hosted_runner(self):
        for path in sorted(WORKFLOWS.glob('*.yml')):
            if path.name == 'prototype.yml':
                continue
            with self.subTest(workflow=path.name):
                text = path.read_text()
                self.assertNotIn('self-hosted', text)
                self.assertNotIn('localica-jit', text)

    def test_self_hosted_emulator_mirrors_the_hosted_action_and_hosted_only_steps_are_skipped(self):
        for api in (26, 36):
            steps = self.jobs[f'focused_{api}']['steps']
            with self.subTest(api=api):
                hosted = next(s for s in steps if s.get('uses', '').startswith('reactivecircus/android-emulator-runner@'))
                local = [s for s in steps if s.get('run') == 'bash .github/local/with-emulator.sh']
                self.assertEqual(1, len(local))
                local = local[0]
                self.assertEqual("${{ inputs.runner == 'self-hosted' }}", local['if'])
                env, inputs = local['env'], hosted['with']
                self.assertEqual(str(inputs['api-level']), env['EMULATOR_API'])
                self.assertEqual(inputs['target'], env['EMULATOR_TARGET'])
                self.assertEqual(inputs['profile'], env['EMULATOR_PROFILE'])
                self.assertEqual('x86_64', inputs['arch'])
                self.assertEqual(inputs['emulator-options'], env['EMULATOR_OPTIONS'])
                self.assertEqual(str(inputs['disable-animations']).lower(), env['EMULATOR_DISABLE_ANIMATIONS'])
                self.assertEqual(inputs['script'], env['EMULATOR_SCRIPT'])
                self.assertIn('bash .github/run-prototype-tests.sh', env['EMULATOR_SCRIPT'])
                # The self-hosted step runs before the hosted SDK/emulator steps, after compilation.
                names = [s.get('name', s.get('uses', '')) for s in steps]
                self.assertLess(names.index('Compile instrumentation APKs before starting emulator'), steps.index(local))
                self.assertLess(steps.index(local), names.index('Prepare the verified emulator SDK'))
                for step in steps:
                    label = step.get('name', step.get('uses', ''))
                    # The JIT image carries JDK 17, Node 24 and the Gradle wrapper: toolchain actions would fetch GitHub releases (#681).
                    needs_host = ('sudo ' in step.get('run', '') or label.startswith(('gradle/actions/setup-gradle@', 'actions/setup-java@', 'actions/setup-node@'))
                                  or label in ('Prepare the verified emulator SDK', 'Restore the exact SDK system image revision',
                                               'Cache the installed SDK system image') or step is hosted)
                    if needs_host:
                        self.assertIn(HOSTED_ONLY, step.get('if', ''), label)

    def test_evidence_still_requires_both_focused_jobs(self):
        # A skipped (refused) self-hosted job is "not-run", never a pass.
        self.assertIn('focused_26', self.jobs['evidence']['needs'])
        self.assertIn('focused_36', self.jobs['evidence']['needs'])
        self.assertIn("'skipped': 'not-run'", next(step['run'] for step in self.jobs['evidence']['steps']
                           if step.get('name') == 'Record passed, failed and not-run checks'))


class EmulatorWrapperContract(unittest.TestCase):
    def run_wrapper(self, **env):
        base = {k: v for k, v in os.environ.items() if not k.startswith(('LOCALICA_', 'RUNNER_', 'GITHUB_'))}
        base.update(EMULATOR_API='36', EMULATOR_TARGET='default', EMULATOR_PROFILE='pixel_2',
                    EMULATOR_OPTIONS='-no-window', EMULATOR_DISABLE_ANIMATIONS='false', EMULATOR_SCRIPT='true',
                    ANDROID_HOME='/nonexistent')
        base.update(env)
        return subprocess.run(['bash', str(ROOT / '.github/local/with-emulator.sh')], env=base,
                              capture_output=True, text=True, timeout=30)

    def test_refuses_everywhere_except_the_locked_down_runner(self):
        for env in ({}, {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted'},
                    {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'},
                    {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted', 'LOCALICA_JIT_RUNNER': '1'}):
            with self.subTest(env=env):
                result = self.run_wrapper(**env)
                self.assertEqual(2, result.returncode)
                self.assertIn('not the locked-down self-hosted JIT runner', result.stderr)

    def test_refuses_long_repeats_so_they_stay_hosted(self):
        # Lead decision (#592, revised 2026-11-07): up to 5 repeats run self-hosted in the 10g container.
        marker = dict(GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='self-hosted', LOCALICA_JIT_RUNNER='1',
                      LOCALICA_EPHEMERAL_RUNNER='localica-kvm')
        for repeat in ({'PROTOTYPE_REPEAT': '6'}, {'PROTOTYPE_REPEAT': '30'}, {'PROTOTYPE_REPEAT': '0'},
                       {'PROTOTYPE_REPEAT': '5;id'}):
            with self.subTest(repeat=repeat):
                result = self.run_wrapper(**marker, **repeat)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn('at most 5 repeats', result.stderr)
        with tempfile.TemporaryDirectory() as home:
            for ok in ({'PROTOTYPE_REPEAT': '1', 'PROTOTYPE_METHODS': ''},
                       {'PROTOTYPE_REPEAT': '5', 'PROTOTYPE_METHODS': 'io.github.anastaci1a.localica.X#m'}):
                with self.subTest(ok=ok):
                    # Allowed counts get past the guard to the image checks (no SDK here, so it stops there).
                    result = self.run_wrapper(HOME=home, **marker, **ok)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertNotIn('at most 5 repeats', result.stderr)

    def test_rejects_unknown_images_before_touching_an_emulator(self):
        marker = dict(GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='self-hosted', LOCALICA_JIT_RUNNER='1',
                      LOCALICA_EPHEMERAL_RUNNER='localica-kvm')
        with tempfile.TemporaryDirectory() as home:
            for bad in ({'EMULATOR_API': '36;id'}, {'EMULATOR_DISABLE_ANIMATIONS': 'yes'}, {}):
                with self.subTest(bad=bad):
                    result = self.run_wrapper(HOME=home, **marker, **bad)
                    self.assertEqual(2, result.returncode, result.stderr)


class ScriptLinesContract(unittest.TestCase):
    """The emulator script runs line by line like the hosted action; nothing may end it early as a pass."""

    def run_lines(self, script, cwd):
        return subprocess.run(['bash', str(ROOT / '.github/local/run-script-lines.sh'), script], cwd=cwd,
                              input='', capture_output=True, text=True, timeout=30)

    def test_a_line_that_reads_stdin_cannot_swallow_the_rest(self):
        # 2026-11-07: `adb shell` on the first line consumed the remaining lines, so the tests never ran
        # and the self-hosted focused job passed with no results.
        with tempfile.TemporaryDirectory() as tmp:
            script = ('cat > consumed.txt\n'
                      '  # comment\n'
                      '\n'
                      '  echo second >> ran.txt\n'
                      'echo last >> ran.txt')
            result = self.run_lines(script, tmp)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('second\nlast\n', (Path(tmp) / 'ran.txt').read_text())
            self.assertEqual('', (Path(tmp) / 'consumed.txt').read_text())

    def test_stops_at_the_first_failing_line_with_its_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.run_lines('echo one >> ran.txt\nexit 7\necho never >> ran.txt', tmp)
            self.assertEqual(7, result.returncode)
            self.assertIn('script line failed (7): exit 7', result.stdout)
            self.assertEqual('one\n', (Path(tmp) / 'ran.txt').read_text())

    def test_wrapper_runs_the_script_through_it(self):
        wrapper = (ROOT / '.github/local/with-emulator.sh').read_text()
        self.assertIn('bash "$here/run-script-lines.sh" "$script"', wrapper)
        self.assertNotIn('<<< "$script"', wrapper)


if __name__ == '__main__':
    unittest.main()
