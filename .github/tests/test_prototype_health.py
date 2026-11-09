import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('prototype_health', Path(__file__).parents[1] / 'prototype-health.py')
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class HealthClassification(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.alive = {'elapsed_seconds': 0, 'processes': [{'pid': 123, 'start_ticks': '100'}],
                      'adb': {'exit_code': 0},
                      'boot_id': {'exit_code': 0, 'stdout': '12345678-1234-1234-1234-123456789abc'}}
        self.evidence = self.root / 'prototype-evidence'
        observed = self.evidence / 'live/observed'
        observed.mkdir(parents=True)
        (observed / 'command.log').write_text('device emulator-5554 not found; incomplete run')
        (observed / 'manifest.json').write_text(json.dumps({'status': 'failed', 'oversize_lines_not_parsed': 0,
            'observer_error': None, 'cancel_signal': None, 'sha': 'f'*40, 'api': '36'}))
        (self.evidence / 'context.txt').write_text('candidate_sha=' + 'f'*40 +
            '\napi=36\nactual_api=36\nserial=emulator-5554\nboot_id=12345678-1234-1234-1234-123456789abc\n')
        (observed.parent / 'supervision.json').write_text(json.dumps({'monitoring_complete': True, 'supervisor_error': None, 'sha': 'f'*40, 'api': '36'}))
        self.dead = {'elapsed_seconds': 10, 'processes': [], 'adb': {'exit_code': 1}}
        self.samples = [self.alive, self.dead, dict(self.dead, elapsed_seconds=15)]

    def classify(self, result, samples, results, iterations=None):
        return health.classify(result, samples, results, iterations, self.evidence)

    def tearDown(self):
        self.temp.cleanup()

    def test_empty_utp_failure_and_confirmed_process_loss_is_infrastructure(self):
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        receipt = self.classify(1, self.samples, self.root)
        self.assertEqual(738, receipt['issue'])
        self.assertEqual('infrastructure', receipt['classification'])

    def test_recovery_before_failure_preinitial_loss_and_replaced_guest_are_refused(self):
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        cases = [self.samples + [dict(self.alive, elapsed_seconds=20)],
                 [self.dead, dict(self.dead, elapsed_seconds=15), dict(self.alive, elapsed_seconds=20)],
                 [self.alive, dict(self.alive, elapsed_seconds=5, boot_id={'exit_code': 0, 'stdout': 'changed'}),
                  self.dead, dict(self.dead, elapsed_seconds=15)],
                 [self.alive, dict(self.alive, elapsed_seconds=5, processes=[{'pid': 123, 'start_ticks': '101'}]),
                  self.dead, dict(self.dead, elapsed_seconds=15)]]
        for samples in cases:
            self.assertIsNone(self.classify(1, samples, self.root))

    def test_original_utp_stderr_is_allowed_but_added_diagnostics_are_refused(self):
        diagnostic = "com.android.ddmlib.AdbCommandRejectedException: device 'emulator-5554' not found\n" + \
                     'at com.android.ddmlib.AdbHelper.setDevice(AdbHelper.java:967)\n' + \
                     'Test run failed to complete. Expected 136 tests, received 12'
        for extra, expected in (('', True), ('\nunknown error', False), ('\nAssertionError: bad', False)):
            (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase><system-err>' +
                diagnostic + extra + '</system-err></testsuite>')
            self.assertEqual(expected, self.classify(1, self.samples, self.root) is not None)

    def test_xml_diagnostics_and_observer_console_assertions_block_recovery(self):
        for field in ('system-out', 'system-err'):
            with self.subTest(field=field):
                (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase><' + field +
                    '>java.lang.AssertionError: product mismatch</' + field + '></testsuite>')
                self.assertIsNone(self.classify(1, self.samples, self.root))
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        (self.evidence / 'live/observed/command.log').write_text('java.lang.AssertionError: earlier failure')
        self.assertIsNone(self.classify(1, self.samples, self.root))

    def test_junit_diagnostic_families_block_every_stream_and_earlier_iteration(self):
        observed_log = self.evidence / 'live/observed/command.log'
        for diagnostic in ('org.junit.ComparisonFailure: product mismatch',
                           'junit.framework.AssertionFailedError: product mismatch',
                           'org.opentest4j.AssertionFailedError: product mismatch',
                           'org.opentest4j.MultipleFailuresError: product mismatch',
                           'java.lang.NoClassDefFoundError: product mismatch',
                           'org.junit.runners.model.TestTimedOutException: product mismatch'):
            for stream in ('system-out', 'system-err', 'console', 'logcat', 'earlier-xml', 'earlier-console'):
                with self.subTest(diagnostic=diagnostic, stream=stream):
                    root = ET.Element('testsuite')
                    ET.SubElement(ET.SubElement(root, 'testcase'), 'failure')
                    observed_log.write_text('device emulator-5554 not found')
                    logcat = self.root / 'logcat-diagnostic.txt'
                    if logcat.exists():
                        logcat.unlink()
                    iterations = self.root / 'iterations'
                    if iterations.exists():
                        import shutil
                        shutil.rmtree(iterations)
                    if stream in ('system-out', 'system-err'):
                        ET.SubElement(root, stream).text = diagnostic
                    elif stream == 'console':
                        observed_log.write_text(diagnostic)
                    elif stream == 'logcat':
                        logcat.write_text(diagnostic)
                    elif stream == 'earlier-xml':
                        iterations.mkdir()
                        old = ET.Element('testsuite')
                        ET.SubElement(old, 'system-out').text = diagnostic
                        ET.ElementTree(old).write(iterations / 'earlier.xml')
                    else:
                        earlier = iterations / 'iteration-01/prototype-evidence/live/observed'
                        earlier.mkdir(parents=True)
                        (earlier / 'command.log').write_text(diagnostic)
                        (earlier / 'manifest.json').write_bytes((observed_log.parent / 'manifest.json').read_bytes())
                        (earlier.parent / 'supervision.json').write_bytes((observed_log.parent.parent / 'supervision.json').read_bytes())
                    ET.ElementTree(root).write(self.root / 'result.xml')
                    self.assertIsNone(self.classify(1, self.samples, self.root, iterations))

    def test_missing_or_degraded_observer_evidence_blocks_recovery(self):
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        (self.evidence / 'live/observed/command.log').unlink()
        self.assertIsNone(self.classify(1, self.samples, self.root))

    def test_process_inventory_selects_only_the_requested_port_and_start_identity(self):
        proc = self.root / 'proc'
        for pid, port in ((123, '5554'), (456, '5556')):
            folder = proc / str(pid)
            folder.mkdir(parents=True)
            (folder / 'cmdline').write_bytes(('qemu-system-x86_64\0-port\0' + port + '\0').encode())
            (folder / 'stat').write_text(str(pid) + ' (qemu) ' + ' '.join(['R'] + ['0'] * 18 + ['12345']))
        selected = health.emulator_processes('emulator-5554', proc)
        self.assertEqual([123], [p['pid'] for p in selected])
        self.assertEqual('12345', selected[0]['start_ticks'])

    def test_assertion_always_wins_over_subsequent_emulator_loss(self):
        for failure in ('<failure>AssertionError</failure>', '<failure message="bad" />', '<error>crash</error>'):
            with self.subTest(failure=failure):
                (self.root / 'result.xml').write_text('<testsuite><testcase>' + failure + '</testcase></testsuite>')
                self.assertIsNone(self.classify(1, self.samples, self.root))

    def test_missing_xml_or_assertion_in_an_earlier_iteration_blocks_restart(self):
        self.assertIsNone(self.classify(1, self.samples, self.root))
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        prior = self.root / 'iterations'
        prior.mkdir()
        for failure in ('<testcase><failure>assertion</failure></testcase>',
                        '<system-err>java.lang.AssertionError: earlier failure</system-err>',
                        '<system-out>java.lang.AssertionError: earlier failure</system-out>'):
            (prior / 'old.xml').write_text('<testsuite>' + failure + '</testsuite>')
            self.assertIsNone(self.classify(1, self.samples, self.root, prior))

    def test_timeout_offline_without_process_loss_and_success_do_not_retry(self):
        for samples in ([self.alive, self.dead], [self.dead, self.dead],
                        [self.alive, dict(self.dead, processes=[{'pid': 123}]), self.dead],
                        [self.alive, dict(self.dead, adb={'exit_code': None}), self.dead]):
            self.assertIsNone(self.classify(1, samples, self.root))
        self.assertIsNone(self.classify(0, self.samples, self.root))

    def test_product_assertion_in_logcat_blocks_empty_xml_recovery(self):
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        (self.root / 'logcat-test.txt').write_text('TestRunner: java.lang.AssertionError: expected true')
        self.assertIsNone(self.classify(1, self.samples, self.root))

    def test_prepare_restart_archives_all_outputs_and_cannot_restart_twice(self):
        infra = self.root / 'ci-infrastructure'
        infra.mkdir()
        (infra / 'emulator.json').write_text(json.dumps({'classification': 'infrastructure', 'issue': 738}))
        for name in ('prototype-iterations', 'app/build/outputs/androidTest-results',
                     'app/build/outputs/connected_android_test_additional_output', 'app/build/reports/androidTests'):
            path = self.root / name
            path.mkdir(parents=True)
            (path / 'retained.txt').write_text(name)
        process = subprocess.run([sys.executable, spec.origin, '--prepare-restart'], cwd=self.root,
                                 capture_output=True, text=True)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertFalse(infra.exists())
        archived = self.root / 'prototype-infrastructure/attempt-1'
        self.assertTrue((archived / 'ci-infrastructure/emulator.json').is_file())
        self.assertTrue((archived / 'prototype-evidence/live/observed/command.log').is_file())
        self.assertEqual(4, len(list(archived.rglob('retained.txt'))))
        again = subprocess.run([sys.executable, spec.origin, '--prepare-restart'], cwd=self.root,
                               capture_output=True, text=True)
        self.assertNotEqual(0, again.returncode)

    def test_malformed_xml_fails_closed(self):
        (self.root / 'result.xml').write_text('<broken')
        self.assertIsNone(self.classify(1, self.samples, self.root))


if __name__ == '__main__':
    unittest.main()
