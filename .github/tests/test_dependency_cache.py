"""Contract for the shared Gradle dependency cache and bounded dependency download retries (#681)."""
import os
import shlex
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
GITHUB = ROOT / '.github'
WORKFLOWS = GITHUB / 'workflows'
AUDIT = GITHUB / 'dependency-cache-audit.sh'
RETRY = GITHUB / 'gradle-transient-retry.sh'
WRAPPED = 'bash .github/gradle-transient-retry.sh ./gradlew'
SETUP_GRADLE = 'gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a'
DEFAULT_BRANCH_ONLY = 'github.event.repository != null && github.ref_name == github.event.repository.default_branch'
HOSTED_ONLY = "inputs.runner != 'self-hosted'"
KEY_JOB = 'GRADLE_BUILD_ACTION_CACHE_KEY_JOB'
KEY_INSTANCE = 'GRADLE_BUILD_ACTION_CACHE_KEY_JOB_INSTANCE'
SNAPSHOT = 'bash .github/dependency-cache-audit.sh snapshot'
REPORT = 'bash .github/dependency-cache-audit.sh report'
COMPATIBILITY_BUILD = 'bash .github/compatibility/build.sh'
# Every job that runs setup-gradle and the cache entry it restores. setup-gradle is read-only on every branch but
# main, so a reader must name an entry that a main job writes: dependency-cache.yml and the Android workflow.
CACHE_KEYS = {
    ('android.yml', 'test-apks'): 'localica-test-apks',
    ('android.yml', 'compatibility'): 'localica-test-apks',
    ('android.yml', 'static-checks'): 'localica-static-checks',
    ('android.yml', 'build'): 'localica-distribution',
    ('android.yml', 'device-tests'): 'localica-device-tests',
    ('prototype.yml', 'build'): 'localica-static-checks',
    ('prototype.yml', 'focused_26'): 'localica-test-apks',
    ('prototype.yml', 'focused_36'): 'localica-test-apks',
    ('prototype.yml', 'compatibility_26'): 'localica-test-apks',
    ('prototype.yml', 'compatibility_36'): 'localica-test-apks',
    ('dependency-cache.yml', 'static-checks'): 'localica-static-checks',
    ('dependency-cache.yml', 'test-apks'): 'localica-test-apks',
}
WARM_WRITERS = {
    'localica-static-checks': ('dependency-cache.yml', 'static-checks'),
    'localica-test-apks': ('dependency-cache.yml', 'test-apks'),
}
AUDITED = [key for key in CACHE_KEYS if key[0] in ('prototype.yml', 'dependency-cache.yml')]


def load(name):
    data = yaml.safe_load((WORKFLOWS / name).read_text())
    if 'on' not in data:
        data['on'] = data[True]  # an unquoted `on:` key parses as a boolean
    return data


UPLOAD_RETRY = './.github/actions/upload-artifact-retry'


def uses(step):
    return step.get('uses', '')


def run(step):
    return step.get('run', '') or ''


def gradle_tasks(command):
    """Task names from one shell invocation; quoted flags and option values are not tasks."""
    words = shlex.split(command)
    if words[:2] == WRAPPED.split()[:2]:
        words = words[2:]
    assert words and words[0] == './gradlew', command
    tasks = set()
    skip_value = False
    for word in words[1:]:
        if word in (';', '&&', '||', '|', '>'):
            break
        if skip_value:
            skip_value = False
            continue
        if word in ('-I', '--init-script', '-p', '--project-dir', '-b', '--build-file', '-c', '--settings-file', '--max-workers'):
            skip_value = True
        elif not word.startswith('-'):
            tasks.add(word)
    return tasks


def compile_steps(job):
    """Steps that compile before any emulator starts: inline gradlew lines and the compatibility build script."""
    return [step for step in job['steps']
            if run(step).startswith(('./gradlew', WRAPPED)) or COMPATIBILITY_BUILD in run(step)]


def compile_tasks(job):
    tasks = set()
    for step in compile_steps(job):
        if run(step).startswith(('./gradlew', WRAPPED)):
            for line in run(step).splitlines():
                if line.startswith(('./gradlew', WRAPPED)):
                    tasks |= gradle_tasks(line)
        else:
            script = (GITHUB / 'compatibility' / 'build.sh').read_text()
            lines = [line for line in script.splitlines() if './gradlew' in line]
            assert len(lines) == 1, lines
            tasks |= gradle_tasks(lines[0][lines[0].index('./gradlew'):].split(')')[0])
    return tasks


def step_index(job, predicate):
    matches = [index for index, step in enumerate(job['steps']) if predicate(step)]
    assert len(matches) == 1, matches
    return matches[0]


class SharedDependencyCacheContract(unittest.TestCase):
    def test_task_parser_retains_lint_tasks_and_ignores_quoted_flags_and_redirections(self):
        self.assertEqual({':lint:test', 'lintDebug'}, gradle_tasks(
            './gradlew :lint:test lintDebug "-PlocalicaSourceSha=$source_sha" -I init.gradle --max-workers 2 > result.txt'))
        self.assertEqual({'assembleDebug', 'assembleDebugAndroidTest'}, gradle_tasks(
            WRAPPED + ' assembleDebug assembleDebugAndroidTest "-PlocalicaSourceSha=$source_sha" --no-daemon'))

    def test_every_setup_gradle_job_names_the_shared_entry_it_restores(self):
        seen = set()
        for path in sorted(WORKFLOWS.glob('*.yml')):
            for name, job in load(path.name)['jobs'].items():
                with self.subTest(workflow=path.name, job=name):
                    setup = [step for step in job['steps'] if uses(step) == SETUP_GRADLE]
                    other = [uses(step) for step in job['steps'] if 'setup-gradle' in uses(step) and uses(step) != SETUP_GRADLE]
                    self.assertEqual([], other, 'setup-gradle must stay pinned to one reviewed commit')
                    if not setup:
                        self.assertNotIn((path.name, name), CACHE_KEYS)
                        continue
                    seen.add((path.name, name))
                    self.assertIn((path.name, name), CACHE_KEYS, 'a setup-gradle job must declare which shared entry it restores')
                    env = job.get('env') or {}
                    self.assertEqual(CACHE_KEYS[path.name, name], env.get(KEY_JOB))
                    self.assertEqual('shared', env.get(KEY_INSTANCE), 'one entry per workflow and SHA, independent of the matrix')
        self.assertEqual(set(CACHE_KEYS), seen)

    def test_readers_need_no_task_their_warm_writer_does_not_resolve(self):
        # cache-cleanup drops entries a writer did not use, so each reader's task set must be a subset of its writer's.
        for key, (workflow, writer_name) in WARM_WRITERS.items():
            writer = load(workflow)['jobs'][writer_name]
            setup = [step for step in writer['steps'] if uses(step) == SETUP_GRADLE]
            self.assertIs(False, setup[0]['with']['cache-read-only'], 'the warm writer saves even when dispatched on a branch')
            self.assertIs(True, setup[0]['with']['cache-overwrite-existing'], 'a complete set replaces an entry saved by a failed build')
            writer_tasks = compile_tasks(writer)
            self.assertTrue(writer_tasks)
            for (reader_workflow, reader_name), reader_key in CACHE_KEYS.items():
                if reader_key != key or (reader_workflow, reader_name) == (workflow, writer_name):
                    continue
                with self.subTest(reader=(reader_workflow, reader_name)):
                    reader_tasks = compile_tasks(load(reader_workflow)['jobs'][reader_name])
                    self.assertTrue(reader_tasks)
                    self.assertLessEqual(reader_tasks, writer_tasks)

    def test_warm_workflow_runs_on_main_dependency_changes_and_on_dispatch(self):
        workflow = load('dependency-cache.yml')
        self.assertIn('workflow_dispatch', workflow['on'])
        push = workflow['on']['push']
        self.assertEqual(['main'], push['branches'])
        for path in ('gradle.properties', 'settings.gradle.kts', 'build.gradle.kts', 'app/build.gradle.kts', 'gradle/**',
                     '.github/workflows/dependency-cache.yml'):
            self.assertIn(path, push['paths'])
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        self.assertEqual(set(WARM_WRITERS.values()), {('dependency-cache.yml', name) for name in workflow['jobs']})
        for job in workflow['jobs'].values():
            self.assertEqual('ubuntu-24.04', job['runs-on'])
            self.assertNotIn('secrets.', yaml.safe_dump(job), 'warming needs no signing key')

    def test_compile_jobs_record_dependency_downloads_around_the_compile_step(self):
        for workflow, name in AUDITED:
            job = load(workflow)['jobs'][name]
            with self.subTest(workflow=workflow, job=name):
                setup = step_index(job, lambda step: uses(step) == SETUP_GRADLE)
                snapshot = step_index(job, lambda step: run(step) == SNAPSHOT)
                report = step_index(job, lambda step: run(step) == REPORT)
                compiles = [job['steps'].index(step) for step in compile_steps(job)]
                self.assertTrue(compiles)
                self.assertLess(setup, snapshot)
                self.assertLess(snapshot, min(compiles))
                self.assertEqual(max(compiles) + 1, report, 'the report follows the compile step directly')
                self.assertIn('!cancelled()', job['steps'][report].get('if', ''))
                if workflow == 'prototype.yml' and name.startswith('focused_'):
                    self.assertEqual(HOSTED_ONLY, job['steps'][snapshot]['if'])
                    self.assertIn(HOSTED_ONLY, job['steps'][report]['if'])
                    self.assertEqual(HOSTED_ONLY, job['steps'][setup]['if'])

    def test_emulator_archive_and_system_image_caches_save_only_from_the_default_branch(self):
        for action in ('emulator-sdk-cache', 'system-image-cache'):
            steps = yaml.safe_load((GITHUB / 'actions' / action / 'action.yml').read_text())['runs']['steps']
            with self.subTest(action=action):
                saves = [step for step in steps if uses(step).startswith('actions/cache/save@')]
                restores = [step for step in steps if uses(step).startswith('actions/cache/restore@')]
                self.assertEqual(1, len(saves))
                self.assertEqual(1, len(restores))
                self.assertEqual(uses(saves[0]).split('@')[1].split()[0], uses(restores[0]).split('@')[1].split()[0])
                self.assertTrue(saves[0]['if'].endswith(' && ' + DEFAULT_BRANCH_ONLY), saves[0]['if'])
                self.assertNotIn('default_branch', restores[0].get('if', ''), 'branches still restore the shared copy')

    def test_every_compile_step_runs_gradle_through_the_bounded_transient_retry(self):
        # Gradle 8.13 retries nothing on dependency downloads; the wrapper re-runs a compile only on a transient signature.
        for (workflow, name) in CACHE_KEYS:
            job = load(workflow)['jobs'][name]
            with self.subTest(workflow=workflow, job=name):
                for step in job['steps']:
                    command = run(step)
                    if './gradlew' in command:
                        self.assertTrue(command.startswith(WRAPPED), command)
                        self.assertNotIn('connected', command, 'a device test run is never wrapped')
        build = (GITHUB / 'compatibility' / 'build.sh').read_text()
        self.assertIn('cd "$tree" && ' + WRAPPED + ' assembleDebug assembleDebugAndroidTest', build)
        for script in ('run-prototype-tests.sh', 'run-device-tests.sh', 'compatibility/run.sh', 'prepare-device-tests.sh'):
            self.assertNotIn('gradle-transient-retry', (GITHUB / script).read_text(), script + ' runs tests; never retried')

    def test_transient_retry_is_bounded_and_gradle_properties_carry_no_dead_retry_settings(self):
        text = RETRY.read_text()
        self.assertIn('attempts=${GRADLE_TRANSIENT_ATTEMPTS:-3}', text)
        self.assertIn('"${GRADLE_TRANSIENT_WAITS:-30 60}"', text)
        properties = (ROOT / 'gradle.properties').read_text()
        # org.gradle.internal.network.retry.* only affects Maven/Ivy publishing in Gradle 8.13.
        self.assertNotIn('network.retry', properties)
        self.assertNotIn('repository.max.retries', properties)

    def test_no_blanket_test_retries(self):
        for path in sorted(WORKFLOWS.glob('*.yml')):
            for name, job in load(path.name)['jobs'].items():
                for step in job['steps']:
                    with self.subTest(workflow=path.name, job=name):
                        # #670's local action retries artifact uploads only (see the test below).
                        if uses(step) != UPLOAD_RETRY:
                            self.assertNotIn('retry', uses(step).lower())
                        self.assertNotIn('rerunFailingTestsCount', run(step))
        for build in (ROOT / 'build.gradle.kts', ROOT / 'app' / 'build.gradle.kts', ROOT / 'gradle' / 'libs.versions.toml'):
            text = build.read_text()
            self.assertNotIn('test-retry', text)
            self.assertNotIn('rerunFailingTestsCount', text)
            self.assertNotIn('retry', text.lower())

    def test_upload_retry_action_only_uploads_artifacts(self):
        # The one allowed retry wraps artifact uploads after a step has run, never Gradle or tests.
        path = ROOT / UPLOAD_RETRY / 'action.yml'
        if not path.exists():
            self.skipTest('#670 upload retry action is not on this branch')
        action = yaml.safe_load(path.read_text())
        steps = action['runs']['steps']
        self.assertTrue(any(uses(step) for step in steps))
        for step in steps:
            if uses(step):
                self.assertTrue(uses(step).startswith('actions/upload-artifact@'), uses(step))
            for command in ('gradlew', 'gradle ', 'connectedDebugAndroidTest', 'am instrument', 'run-device-tests', 'run-prototype-tests'):
                self.assertNotIn(command, run(step))


class DependencyCacheAuditContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.home = self.root / 'gradle-home'
        self.work = self.root / 'work'
        self.work.mkdir()
        self.env = dict(os.environ, GRADLE_USER_HOME=str(self.home), RUNNER_TEMP=str(self.root / 'runner'))

    def tearDown(self):
        self.temp.cleanup()

    def module_file(self, group, name, version, digest, file):
        path = self.home / 'caches' / 'modules-2' / 'files-2.1' / group / name / version / digest / file
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'x')

    def audit(self, mode):
        return subprocess.run(['bash', str(AUDIT), mode], cwd=self.work, env=self.env, capture_output=True, text=True)

    def test_report_counts_only_module_files_added_after_the_snapshot(self):
        self.module_file('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.21', 'aaa', 'kotlin-stdlib-2.1.21.jar')
        snapshot = self.audit('snapshot')
        self.assertEqual(0, snapshot.returncode, snapshot.stderr)
        self.assertEqual('dependency-cache-restored-files=1\n', snapshot.stdout)
        (self.work / 'prototype-runner-environment.txt').write_text('identity\n')
        self.module_file('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.21', 'bbb', 'kotlin-compiler-embeddable-2.1.21.jar')
        self.module_file('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.21', 'ccc', 'kotlin-compiler-embeddable-2.1.21.pom')
        transform = self.home / 'caches' / 'transforms-4' / 'ddd' / 'transformed' / 'x.jar'
        transform.parent.mkdir(parents=True)
        transform.write_bytes(b'x')  # not a dependency download
        report = self.audit('report')
        self.assertEqual(0, report.returncode, report.stderr)
        expected = ('dependency-downloads=2\n'
                    'dependency-downloaded: org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21\n')
        self.assertTrue(report.stdout.startswith(expected), report.stdout)
        self.assertIn('::notice title=Dependency cache incomplete::2 dependency files', report.stdout)
        self.assertEqual('identity\n' + expected, (self.work / 'prototype-runner-environment.txt').read_text())

    def test_complete_cache_reports_zero_without_an_annotation_and_never_fails(self):
        self.module_file('androidx.compose.ui', 'ui', '1.8.0', 'aaa', 'ui-1.8.0.aar')
        self.assertEqual(0, self.audit('snapshot').returncode)
        report = self.audit('report')
        self.assertEqual(0, report.returncode, report.stderr)
        self.assertEqual('dependency-downloads=0\n', report.stdout)
        self.assertFalse((self.work / 'prototype-runner-environment.txt').exists())

    def test_missing_gradle_home_snapshot_is_empty_and_report_needs_a_snapshot(self):
        report = self.audit('report')
        self.assertEqual(2, report.returncode)
        self.assertIn('no snapshot', report.stderr)
        snapshot = self.audit('snapshot')
        self.assertEqual(0, snapshot.returncode, snapshot.stderr)
        self.assertEqual('dependency-cache-restored-files=0\n', snapshot.stdout)
        self.assertEqual(2, self.audit('nonsense').returncode)


class TransientRetryContract(unittest.TestCase):
    GATEWAY = ["> Could not download kotlin-compiler-embeddable-2.1.21.jar (org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21)",
               "> Could not GET 'https://github.com/JetBrains/kotlin/releases/download/v2.1.21/kotlin-compiler-embeddable-2.1.21.jar'."
               " Received status code 504 from server: Gateway Time-out"]

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.fake = self.root / 'fake-gradlew'
        self.counter = self.root / 'calls'
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root), GRADLE_TRANSIENT_WAITS='0 0')

    def tearDown(self):
        self.temp.cleanup()

    def gradlew(self, outcomes):
        """A fake build that prints the n-th outcome on its n-th call; the outcome 'ok' succeeds."""
        lines = ['#!/usr/bin/env bash',
                 'n=$(( $(cat "%s" 2>/dev/null || echo 0) + 1 ))' % self.counter,
                 'echo "$n" > "%s"' % self.counter,
                 'case "$n" in']
        for index, outcome in enumerate(outcomes, 1):
            if outcome == 'ok':
                lines.append('  %d) echo "BUILD SUCCESSFUL"; exit 0;;' % index)
            else:
                quoted = ' '.join("'%s'" % line.replace("'", "'\\''") for line in outcome)
                lines.append('  %d) printf "%%s\\n" %s; echo "FAILURE: Build failed with an exception."; exit 1;;' % (index, quoted))
        lines += ['esac', 'exit 99']
        self.fake.write_text('\n'.join(lines) + '\n')
        self.fake.chmod(0o755)
        if self.counter.exists():
            self.counter.unlink()

    def run_wrapper(self):
        return subprocess.run(['bash', str(RETRY), str(self.fake)], cwd=self.root, env=self.env,
                              capture_output=True, text=True)

    def calls(self):
        return int(self.counter.read_text())

    def test_transient_download_failure_is_retried_until_success_within_the_bound(self):
        (self.root / 'prototype-runner-environment.txt').write_text('identity\n')
        self.gradlew([self.GATEWAY, self.GATEWAY, 'ok'])
        result = self.run_wrapper()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(3, self.calls())
        self.assertEqual(2, result.stdout.count('::warning title=Transient dependency download failure::'))
        self.assertEqual('identity\ngradle-transient-retries=2\n',
                         (self.root / 'prototype-runner-environment.txt').read_text())

    def test_exhausted_attempts_fail_with_the_build_status(self):
        self.gradlew([self.GATEWAY, self.GATEWAY, self.GATEWAY, 'ok'])
        result = self.run_wrapper()
        self.assertEqual(1, result.returncode)
        self.assertEqual(3, self.calls())

    def test_test_failures_compile_errors_and_client_errors_never_retry(self):
        cases = {
            'tests': self.GATEWAY + ["> There were failing tests. See the report at: file:///w/app/build/reports/tests/index.html"],
            'compile': ["e: file:///w/app/src/main/java/Foo.kt:1:1 Unresolved reference: bar",
                        "> Compilation error. See log for more details"],
            'client': ["> Could not GET 'https://repo.maven.apache.org/maven2/x/y/1/y-1.jar'."
                       " Received status code 403 from server: Forbidden"],
            'timeout-in-test-task': ["Execution failed for task ':app:testDebugUnitTest'.",
                                     "> Could not download x.jar", "> Read timed out"],
        }
        for name, lines in cases.items():
            with self.subTest(case=name):
                self.gradlew([lines, 'ok'])
                result = self.run_wrapper()
                self.assertEqual(1, result.returncode, name)
                self.assertEqual(1, self.calls(), name)
                self.assertNotIn('::warning', result.stdout)

    def test_unreachable_network_retries_and_exhaustion_is_infrastructure(self):
        network = ["> Could not GET 'https://repo.maven.apache.org/maven2/gson.jar'.",
                   '> Network is unreachable']
        self.gradlew([network, 'ok'])
        self.assertEqual(0, self.run_wrapper().returncode)
        self.assertEqual(2, self.calls())
        self.gradlew([network, network, network])
        self.assertEqual(1, self.run_wrapper().returncode)
        receipt = (self.root / 'ci-infrastructure/dependencies.json').read_text()
        self.assertIn('"classification":"infrastructure"', receipt)
        self.assertEqual(3, self.calls())

    def test_successful_build_runs_once_and_records_zero_retries(self):
        (self.root / 'prototype-runner-environment.txt').write_text('')
        self.gradlew(['ok'])
        result = self.run_wrapper()
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, self.calls())
        self.assertEqual('gradle-transient-retries=0\n', (self.root / 'prototype-runner-environment.txt').read_text())


if __name__ == '__main__':
    unittest.main()
