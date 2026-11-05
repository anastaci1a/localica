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

    def test_reviewed_sdk_contracts_excluded_only_below_their_minimum(self):
        folder = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica'
        folder.mkdir(parents=True)
        methods = [('ScopedSearchOpticsTest', 'searchLensRedrawsWhenItsListBackdropArrivesLater', 33),
                   ('FloatingImeLifecycleTest', 'actualFloatingWindowPreservesEditingAndEndsOnceAcrossFocusAndReopen', 30),
                   ('GlassReadabilityTest', 'entireGlassUsesTheBoundedCurveWithoutTintingItsForeground', 33)]
        for cls, method, minimum in methods:
            (folder/(cls+'.kt')).write_text('package io.github.anastaci1a.localica\nimport org.junit.Test\nclass '+cls+
                ' { @Test @androidx.test.filters.SdkSuppress(minSdkVersion = '+str(minimum)+') fun '+method+'() {} }')
        legacy = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        modern = partition.plan(self.root, ['example.RiskTest'], '36', 'a'*40)
        self.assertEqual(3, len(legacy['sdk_omissions']))
        self.assertEqual(4, len(legacy['expected']))
        self.assertEqual([], modern['sdk_omissions'])
        self.assertEqual(7, len(modern['expected']))
        self.assertEqual({row['id'] for row in legacy['sdk_omissions']}, set(modern['expected'])-set(legacy['expected']))
        path = folder/'FloatingImeLifecycleTest.kt'
        original = path.read_text()
        for changed in [original.replace('minSdkVersion = 30', 'minSdkVersion = 29'),
                        original.replace('fun actualFloatingWindow', 'fun renamedFloatingWindow')]:
            path.write_text(changed)
            with self.assertRaises(ValueError):
                partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)

    def test_multiple_optical_contracts_preserve_average_coverage(self):
        folder = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica'
        folder.mkdir(parents=True)
        path = folder/'GlassAppearanceTest.kt'
        path.write_text('package io.github.anastaci1a.localica\nimport org.junit.Test\n'
                        'class GlassAppearanceTest { '
                        '@androidx.test.filters.SdkSuppress(minSdkVersion = 33) @Test '
                        'fun frostedPanelsStillScatterTheParentScene() {} '
                        '@androidx.test.filters.SdkSuppress(minSdkVersion = 33) @Test '
                        'fun blurTintAndShadowChangeIndependentlyWhileForegroundStaysSharp() {} '
                        '@Test fun averageStillRuns() {} }')
        older = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        newer = partition.plan(self.root, ['example.RiskTest'], '36', 'a'*40)
        self.assertEqual(2, len(older['sdk_omissions']))
        self.assertEqual([], newer['sdk_omissions'])
        self.assertIn('io.github.anastaci1a.localica.GlassAppearanceTest#averageStillRuns', older['expected'])
        path.write_text(path.read_text().replace('@Test fun averageStillRuns',
                        '@androidx.test.filters.SdkSuppress(minSdkVersion = 33) @Test fun averageStillRuns'))
        with self.assertRaises(ValueError):
            partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)

    def test_renderer_sdk_exception_cannot_hide_another_method_or_changed_requirement(self):
        folder = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica'
        folder.mkdir(parents=True)
        path = folder/'GlassReadabilityTest.kt'
        original = ('package io.github.anastaci1a.localica\nimport org.junit.Test\n'
                    'class GlassReadabilityTest { '
                    '@Test @androidx.test.filters.SdkSuppress(minSdkVersion = 33) '
                    'fun entireGlassUsesTheBoundedCurveWithoutTintingItsForeground() {} '
                    '@Test fun averageStillRuns() {} }')
        path.write_text(original)
        for api in ('26', '36'):
            manifest = partition.plan(self.root, ['example.RiskTest'], api, 'a'*40)
            self.assertIn('io.github.anastaci1a.localica.GlassReadabilityTest#averageStillRuns', manifest['expected'])
        for changed in [original.replace('minSdkVersion = 33', 'minSdkVersion = 34'),
                        original.replace('fun entireGlassUsesTheBoundedCurve', 'fun renamedCurve'),
                        original.replace('@Test fun averageStillRuns',
                            '@Test @androidx.test.filters.SdkSuppress(minSdkVersion = 33) fun averageStillRuns')]:
            path.write_text(changed)
            for api in ('26', '36'):
                with self.subTest(api=api, changed=changed), self.assertRaises(ValueError):
                    partition.plan(self.root, ['example.RiskTest'], api, 'a'*40)

    def test_malformed_count_and_xml_entity_fail_closed(self):
        p=self.remaining/'TEST.xml';original=p.read_text()
        for changed in [original.replace('tests="2"','tests="0"'), '<!DOCTYPE testsuite [<!ENTITY x "a">]>'+original]:
            p.write_text(changed)
            with self.assertRaises(ValueError):self.complete()




class WeightedClassShardsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        folder = self.root/'app/src/androidTest/java/example'
        folder.mkdir(parents=True)
        self.classes = ['example.Class'+str(i)+'Test' for i in range(8)]
        for cls in self.classes:
            (folder/(cls.split('.')[-1]+'.kt')).write_text(
                'package example\nimport org.junit.Test\nclass '+cls.split('.')[-1]+' { @Test fun first() {} @Test fun second() {} }')
        self.weights = self.root/'durations.csv'
        self.weights.write_text('api,class,tests,total_seconds,skipped\n'+''.join(
            f'{api},{cls},2,{seconds},0\n' for api in ('26','36')
            for cls,seconds in zip(self.classes, (90,80,70,60,40,30,20,10))))

    def plan(self, **kwargs):
        return partition.shards(self.root, '26', 4, self.weights, 'a'*40, **kwargs)

    def test_measured_heavy_classes_balance_four_shards_without_losing_methods(self):
        result = self.plan()
        self.assertEqual([100.0]*4, [s['estimated_seconds'] for s in result['shards']])
        classes = [c for shard in result['shards'] for c in shard['classes']]
        methods = [m for shard in result['shards'] for m in shard['methods']]
        self.assertEqual(sorted(self.classes), sorted(classes))
        self.assertEqual(16, len(set(methods)))
        self.assertEqual(16, len(methods))
        self.assertEqual(result, self.plan())
        for shard in result['shards']:
            self.assertEqual(set(self.classes) - set(shard['classes']), set(shard['excluded_classes']))

    def test_unknown_classes_keep_every_method_with_explicit_estimated_cost(self):
        folder=self.root/'app/src/androidTest/java/example'
        (folder/'NewTest.kt').write_text('package example\nimport org.junit.Test\nclass NewTest { @Test fun newCase() {} }')
        result=self.plan()
        self.assertEqual(['example.NewTest'], result['estimated_classes'])
        self.assertIn('example.NewTest#newCase', [m for s in result['shards'] for m in s['methods']])
        self.assertTrue(all(s['estimated_seconds']>0 for s in result['shards']))

    def test_risk_preflight_is_separate_and_source_profile_are_bound(self):
        result=self.plan(risks=['example.Class0Test'])
        self.assertEqual(['example.Class0Test'], result['risk_classes'])
        self.assertEqual(14, sum(len(s['methods']) for s in result['shards']))
        self.assertFalse(any('example.Class0Test' in s['classes'] for s in result['shards']))
        self.assertEqual(64, len(result['duration_sha256']))
        old=result['inventory_sha256']
        (self.root/'app/src/androidTest/java/example/Helper.kt').write_text('package example\nclass Helper {}')
        self.assertNotEqual(old, self.plan()['inventory_sha256'])

    def test_corrupt_costs_and_invalid_shard_inputs_fail_closed(self):
        original=self.weights.read_text()
        for text in (original+original.splitlines()[1]+'\n', original.replace(',90,0',',nan,0'),
                     original.replace(',90,0',',-1,0'), original.replace(',2,90,0',',0,90,0')):
            with self.subTest(profile=text[:100]):
                self.weights.write_text(text)
                with self.assertRaises(ValueError):self.plan()
        self.weights.write_text(original)
        for count in (0,9):
            with self.assertRaises(ValueError):partition.shards(self.root,'26',count,self.weights,'a'*40)
        with self.assertRaises(ValueError):self.plan(risks=['example.UnknownTest'])

    def test_api_costs_and_existing_scroll_optin_are_accounted_separately(self):
        self.weights.write_text(self.weights.read_text().replace('36,example.Class0Test,2,90,0',
                                                                '36,example.Class0Test,2,900,0'))
        modern=partition.shards(self.root,'36',4,self.weights,'a'*40)
        self.assertEqual(900,modern['class_seconds']['example.Class0Test'])
        self.assertEqual(90,self.plan()['class_seconds']['example.Class0Test'])
        cls='io.github.anastaci1a.localica.ScrollPerformanceTest'
        (self.root/'app/src/androidTest/java/example/ScrollPerformanceTest.kt').write_text(
            'package io.github.anastaci1a.localica\nimport org.junit.Test\nclass ScrollPerformanceTest { @Test fun one() {} @Test fun two() {} }')
        self.weights.write_text(self.weights.read_text()+f'26,{cls},2,500,0\n')
        ordinary=self.plan()
        self.assertEqual(0.02,ordinary['class_seconds'][cls])
        self.assertEqual([cls],ordinary['opt_in_disabled_classes'])
        self.assertEqual(500,self.plan(scroll_performance=True)['class_seconds'][cls])
        self.assertEqual(18,sum(len(s['methods']) for s in ordinary['shards']))


if __name__=='__main__':unittest.main()
