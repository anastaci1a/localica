import importlib.util
import tempfile
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('prototype_health', Path(__file__).parents[1] / 'prototype-health.py')
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class HealthClassification(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.alive = {'elapsed_seconds': 0, 'processes': [{'pid': 123}], 'adb': {'exit_code': 0}}
        self.dead = {'elapsed_seconds': 10, 'processes': [], 'adb': {'exit_code': 1}}
        self.samples = [self.alive, self.dead, dict(self.dead, elapsed_seconds=15)]

    def tearDown(self):
        self.temp.cleanup()

    def test_empty_utp_failure_and_confirmed_process_loss_is_infrastructure(self):
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        receipt = health.classify(1, self.samples, self.root)
        self.assertEqual(738, receipt['issue'])
        self.assertEqual('infrastructure', receipt['classification'])

    def test_assertion_always_wins_over_subsequent_emulator_loss(self):
        for failure in ('<failure>AssertionError</failure>', '<failure message="bad" />', '<error>crash</error>'):
            with self.subTest(failure=failure):
                (self.root / 'result.xml').write_text('<testsuite><testcase>' + failure + '</testcase></testsuite>')
                self.assertIsNone(health.classify(1, self.samples, self.root))

    def test_missing_xml_or_assertion_in_an_earlier_iteration_blocks_restart(self):
        self.assertIsNone(health.classify(1, self.samples, self.root))
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        prior = self.root / 'iterations'
        prior.mkdir()
        (prior / 'old.xml').write_text('<testsuite><testcase><failure>assertion</failure></testcase></testsuite>')
        self.assertIsNone(health.classify(1, self.samples, self.root, prior))

    def test_timeout_offline_without_process_loss_and_success_do_not_retry(self):
        for samples in ([self.alive, self.dead], [self.dead, self.dead],
                        [self.alive, dict(self.dead, processes=[{'pid': 123}]), self.dead],
                        [self.alive, dict(self.dead, adb={'exit_code': None}), self.dead]):
            self.assertIsNone(health.classify(1, samples, self.root))
        self.assertIsNone(health.classify(0, self.samples, self.root))

    def test_product_assertion_in_logcat_blocks_empty_xml_recovery(self):
        (self.root / 'result.xml').write_text('<testsuite><testcase><failure /></testcase></testsuite>')
        (self.root / 'logcat-test.txt').write_text('TestRunner: java.lang.AssertionError: expected true')
        self.assertIsNone(health.classify(1, self.samples, self.root))

    def test_malformed_xml_fails_closed(self):
        (self.root / 'result.xml').write_text('<broken')
        self.assertIsNone(health.classify(1, self.samples, self.root))


if __name__ == '__main__':
    unittest.main()
