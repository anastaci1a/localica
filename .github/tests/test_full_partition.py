"""Small offline rejection matrix for exact-once complete native coverage."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'.github/test-evidence'))
import full_partition as partition


class FullPartitionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.folder = self.root/'app/src/androidTest/java/example'
        self.folder.mkdir(parents=True)
        for cls in ('RiskTest','OtherTest'):
            (self.folder/(cls+'.kt')).write_text('package example\nimport org.junit.Test\nclass '+cls+' { @Test fun one() {} @Test fun two() {} }')
        self.manifest = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        self.preflight = self.root/'preflight';self.preflight.mkdir()
        self.remaining = self.root/'remaining';self.remaining.mkdir()
        self.xml(self.preflight, self.manifest['preflight'])
        self.xml(self.remaining, self.manifest['remaining'])

    def xml(self, folder, ids, outcome=None):
        suite = ET.Element('testsuite', tests=str(len(ids)), failures=str(len(ids) if outcome=='failure' else 0), errors='0', skipped=str(len(ids) if outcome=='skipped' else 0))
        for identity in ids:
            cls, method = identity.split('#')
            case = ET.SubElement(suite,'testcase',classname=cls,name=method,time='0.1')
            if outcome: ET.SubElement(case,outcome)
            if outcome=='skipped':
                (folder/('logcat-'+cls+'-'+method+'.txt')).write_text('TestRunner: started: '+method+'('+cls+')\nTestRunner: assumption failed: '+method+'('+cls+')\nTestRunner: org.junit.AssumptionViolatedException: Existing environment assumption\n')
        ET.ElementTree(suite).write(folder/'TEST.xml')

    def complete(self):
        return partition.reconcile(self.root,self.manifest,self.preflight,self.remaining)

    def test_disjoint_complete_union_and_preserved_metadata(self):
        receipt=self.complete()
        self.assertEqual('complete_union',receipt['status'])
        self.assertEqual(4,receipt['executed_once'])
        self.assertEqual('a'*40,receipt['sha'])
        self.assertEqual(0,receipt['skipped'])
        self.assertEqual(64,len(receipt['remaining_xml'][0]['sha256']))

    def test_missing_extra_repeated_or_duplicate_id_rejected(self):
        cases=[self.manifest['remaining'][:-1],self.manifest['remaining']+['example.OtherTest#extra'],self.manifest['remaining']+self.manifest['preflight'],self.manifest['remaining']*2]
        for ids in cases:
            with self.subTest(ids=ids):
                self.xml(self.remaining,ids)
                with self.assertRaises(ValueError):self.complete()

    def test_missing_failed_or_skipped_preflight_cannot_admit_remainder(self):
        for ids,status in [(self.manifest['preflight'][:-1],None),(self.manifest['preflight'],'failure'),(self.manifest['preflight'],'skipped')]:
            with self.subTest(status=status):
                self.xml(self.preflight,ids,status)
                with self.assertRaises(ValueError):partition.reconcile(self.root,self.manifest,self.preflight)

    def test_remaining_failure_cannot_pass(self):
        self.xml(self.remaining,self.manifest['remaining'],'failure')
        with self.assertRaises(ValueError):self.complete()

    def test_original_assumption_is_recorded_not_counted_as_pass(self):
        self.xml(self.remaining,self.manifest['remaining'],'skipped')
        receipt=self.complete()
        self.assertEqual(2,receipt['skipped'])
        self.assertTrue(all(row['reason']=='Existing environment assumption' for row in receipt['remaining'].values()))
        next(self.remaining.glob('logcat-*')).unlink()
        with self.assertRaises(ValueError):self.complete()

    def test_source_addition_or_helper_mutation_invalidates_plan(self):
        (self.folder/'Helper.kt').write_text('package example\nclass Helper {}')
        with self.assertRaises(ValueError):self.complete()

    def test_no_empty_remaining_and_no_duplicate_risk_classes(self):
        for risks in [['example.RiskTest','example.OtherTest'],['example.RiskTest']*2, ['example.Unknown']]:
            with self.subTest(risks=risks):
                with self.assertRaises(ValueError):partition.plan(self.root,risks,'26','a'*40)

    def test_unreviewed_sdk_filter_and_inheritance_fail_closed(self):
        p=self.folder/'OtherTest.kt';original=p.read_text()
        for changed in [original.replace('@Test fun one()', '@Test @SdkSuppress(minSdkVersion=33) fun one()'), original.replace('class OtherTest {', 'class OtherTest : Base() {')]:
            p.write_text(changed)
            with self.assertRaises(ValueError):partition.plan(self.root,['example.RiskTest'],'26','a'*40)

    def test_malformed_count_and_xml_entity_fail_closed(self):
        p=self.remaining/'TEST.xml';original=p.read_text()
        for changed in [original.replace('tests="2"','tests="0"'), '<!DOCTYPE testsuite [<!ENTITY x "a">]>'+original]:
            p.write_text(changed)
            with self.assertRaises(ValueError):self.complete()


if __name__=='__main__':unittest.main()
