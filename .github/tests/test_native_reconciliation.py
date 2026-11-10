"""Receipt controls use synthetic XML only; they provide no native acceptance."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('native_reconciliation', ROOT/'.github/test-evidence/native_reconciliation.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeReconciliation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.evidence = self.root/'prototype-evidence'
        (self.evidence/'live/observed').mkdir(parents=True)
        self.xml = self.root/'app/build/outputs/androidTest-results/final.xml'
        self.xml.parent.mkdir(parents=True)
        self.source = 'a'*40
        self.identity = 'sample.Native#realMethod'
        self.inventory = {'api': 26, 'selectors': ['sample.Native'], 'runtime_methods': [self.identity]}
        (self.evidence/'expected-methods.json').write_text(json.dumps(self.inventory))
        (self.evidence/'live/observed/manifest.json').write_text(json.dumps({'sha': self.source, 'api': '26'}))
        (self.evidence/'test-input-apks.sha256').write_text('')

    def run_receipt(self, body='', exit_code=1, tests=1):
        self.xml.write_text(f'<testsuite tests="{tests}"><testcase classname="sample.Native" name="realMethod">{body}</testcase></testsuite>')
        output = self.root/'separate/reconciled-tests.json'
        report = module.record(self.root, output, exit_code, self.source, 26)
        self.assertEqual(report, json.loads(output.read_text()))
        self.assertFalse(report['qualification_passed'])
        self.assertFalse(report['release_certified'])
        return report

    def test_failed_native_method_is_preserved(self):
        report = self.run_receipt('<failure>unchanged original failure</failure>', exit_code=7)
        self.assertEqual('failed', report['methods'][self.identity])
        self.assertEqual(7, report['native_runner_exit_code'])

    def test_raw_skip_never_becomes_qualified_or_passed(self):
        report = self.run_receipt('<skipped/>')
        self.assertEqual('skipped', report['methods'][self.identity])
        self.assertEqual([], report['qualified_skips'])

    def test_all_pass_xml_with_failed_runner_stays_unqualified(self):
        report = self.run_receipt(exit_code=7)
        self.assertEqual('passed', report['methods'][self.identity])
        self.assertEqual(7, report['native_runner_exit_code'])

    def test_all_pass_xml_with_zero_exit_still_requires_strict_qualification(self):
        self.run_receipt(exit_code=0)

    def test_missing_method_is_reported_without_invention(self):
        self.inventory['runtime_methods'].append('sample.Native#missing')
        (self.evidence/'expected-methods.json').write_text(json.dumps(self.inventory))
        report = self.run_receipt()
        self.assertEqual(['sample.Native#missing'], report['missing_methods'])
        self.assertNotIn('sample.Native#missing', report['methods'])

    def test_bad_xml_count_and_observer_identity_are_errors(self):
        (self.evidence/'live/observed/manifest.json').write_text(json.dumps({'sha': 'b'*40, 'api': '36'}))
        report = self.run_receipt(tests=2)
        self.assertGreaterEqual(len(report['reconciliation_errors']), 2)
        self.assertEqual('b'*40, report['sha'])
        self.assertEqual('36', report['api'])

    def test_missing_producer_is_not_an_intact_pair(self):
        self.assertFalse(self.run_receipt()['producer_unchanged'])


if __name__ == '__main__':
    unittest.main()
