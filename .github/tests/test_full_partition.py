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


def varint(value):
    out = bytearray()
    while True:
        out.append((value & 0x7f) | (0x80 if value > 0x7f else 0))
        value >>= 7
        if not value:
            return bytes(out)


def field(number, payload):
    payload = payload.encode() if isinstance(payload, str) else payload
    return varint(number << 3 | 2)+varint(len(payload))+payload


def utp(results):
    """A UTP TestSuiteResult holding assumption errors: {(package.Class, method): reason}."""
    body = b''
    for (cls, method), reason in results.items():
        package, simple = cls.rsplit('.', 1)
        trace = 'org.junit.AssumptionViolatedException: '+reason+'\n\tat org.junit.Assume.assumeTrue(Assume.java:68)'
        # Field 6 is a nested start-time message whose bytes are not text, as in real records.
        case = field(1, simple)+field(2, package)+field(3, method)+field(6, b'\x08\xd2\x99\xff\xff\x06')
        error = field(1, trace)+field(2, 'org.junit.AssumptionViolatedException')+field(3, trace)
        body += field(3, field(1, case)+varint(2 << 3)+varint(4)+field(3, error))
    return body


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
        if outcome=='skipped':
            (folder/'test-result.pb').write_bytes(utp({tuple(i.split('#')): 'Existing environment assumption' for i in ids}))
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

    def test_assumption_reason_survives_dropped_log_entries(self):
        # #669: logd dropped 'begin exception' and the reason line; UTP still recorded the reason.
        self.xml(self.remaining,self.manifest['remaining'],'skipped')
        for identity in self.manifest['remaining']:
            cls, method = identity.split('#')
            (self.remaining/('logcat-'+cls+'-'+method+'.txt')).write_text(
                'TestRunner: started: '+method+'('+cls+')\nTestRunner: assumption failed: '+method+'('+cls+')\n'
                'TestRunner: \tat org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)\n')
        receipt=self.complete()
        self.assertTrue(all(row['reason']=='Existing environment assumption' for row in receipt['remaining'].values()))
        self.assertTrue(all(row['reason_records'][0]['file'].endswith('test-result.pb') for row in receipt['remaining'].values()))

    def test_assumption_reason_needs_one_uncontradicted_utp_record(self):
        ids=self.manifest['remaining']
        record=self.remaining/'test-result.pb'
        def disagree():
            (self.remaining/'device').mkdir(exist_ok=True)
            (self.remaining/'device/test-result.pb').write_bytes(utp({tuple(i.split('#')): 'Another assumption' for i in ids}))
        def contradict():
            for log in self.remaining.glob('logcat-*'):
                log.write_text(log.read_text().replace('Existing environment','Another'))
        cases={'missing record': record.unlink,
               'identity absent': lambda: record.write_bytes(utp({tuple(ids[0].split('#')): 'Existing environment assumption'})),
               'records disagree': disagree, 'log contradicts': contradict,
               'malformed record': lambda: record.write_bytes(b'\x1a\xff')}
        for name,mutate in cases.items():
            with self.subTest(case=name):
                self.xml(self.remaining,ids,'skipped')
                (self.remaining/'device/test-result.pb').unlink(missing_ok=True)
                mutate()
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

    def test_flat_none_popup_omits_only_two_reviewed_methods_below_33(self):
        relative = Path('app/src/androidTest/java/io/github/anastaci1a/localica/FlatNoneRendererPopupTest.kt')
        path = self.root/relative
        path.parent.mkdir(parents=True)
        original = (ROOT/relative).read_text()
        path.write_text(original)
        older = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        newer = partition.plan(self.root, ['example.RiskTest'], '36', 'a'*40)
        prefix = 'io.github.anastaci1a.localica.FlatNoneRendererPopupTest#'
        gated = {prefix+'rendererPopupCanLeaveFlatNoneForSingleToneAndNormal',
                 prefix+'rendererPopupAccessibilityCanLeaveFlatNoneForSingleToneAndNormal'}
        self.assertEqual(gated, {row['id'] for row in older['sdk_omissions']})
        self.assertTrue(all(row['reason'] == 'Reviewed minSdkVersion=33 annotation'
                            for row in older['sdk_omissions']))
        self.assertTrue(gated.isdisjoint(older['expected']))
        self.assertTrue(gated.issubset(newer['expected']))
        self.assertEqual([], newer['sdk_omissions'])
        self.assertIn(prefix+'layerlessParentContributesNoSampledSceneBounds', older['expected'])
        for changed in (original.replace('minSdkVersion = 33', 'minSdkVersion = 32'),
                        original.replace('fun rendererPopupCanLeave', 'fun renamedRendererPopupCanLeave'),
                        original.replace('@Test fun layerlessParent',
                                         '@SdkSuppress(minSdkVersion = 33) @Test fun layerlessParent'),
                        original.replace('import androidx.test.filters.SdkSuppress',
                                         'import example.SdkSuppress')):
            path.write_text(changed)
            with self.assertRaises(ValueError):
                partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)

    def test_reviewed_legacy_capture_is_omitted_only_above_32(self):
        folder = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica'
        folder.mkdir(parents=True)
        method = 'queuedFinalRecordingSurvivesASuspendedThumbnailCaptureAndThenSettles'
        identity = 'io.github.anastaci1a.localica.BackdropInvalidationTest#' + method
        path = folder/'BackdropInvalidationTest.kt'
        original = ('package io.github.anastaci1a.localica\nimport org.junit.Test\n'
                    'class BackdropInvalidationTest { @Test '
                    '@androidx.test.filters.SdkSuppress(maxSdkVersion = 32) fun ' + method + '() {} '
                    '@Test @androidx.test.filters.SdkSuppress(maxSdkVersion = 32) '
                    'fun rasterlessBackingRecordInvalidatesItsDependentRecording() {} }')
        path.write_text(original)
        old = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        new = partition.plan(self.root, ['example.RiskTest'], '36', 'a'*40)
        self.assertIn(identity, old['expected'])
        self.assertNotIn(identity, new['expected'])
        self.assertEqual('Reviewed maxSdkVersion=32 annotation', new['sdk_omissions'][0]['reason'])
        for changed in (original.replace('maxSdkVersion = 32', 'maxSdkVersion = 31'),
                        original.replace(method, 'differentMethod')):
            path.write_text(changed)
            with self.assertRaises(ValueError):
                partition.plan(self.root, ['example.RiskTest'], '36', 'a'*40)

    def test_reviewed_sdk_contracts_excluded_only_below_their_minimum(self):
        folder = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica'
        folder.mkdir(parents=True)
        methods = [('ScopedSearchOpticsTest', 'searchLensRedrawsWhenItsListBackdropArrivesLater', 33),
                   ('FloatingImeLifecycleTest', 'actualFloatingWindowPreservesEditingAndEndsOnceAcrossFocusAndReopen', 30),
                   ('GlassReadabilityTest', 'entireGlassUsesTheBoundedCurveWithoutTintingItsForeground', 33),
                   ('GlassToneShaderParityTest', 'shaderMatchesKotlinAtSamplePoints', 33)]
        for cls, method, minimum in methods:
            (folder/(cls+'.kt')).write_text('package io.github.anastaci1a.localica\nimport org.junit.Test\nclass '+cls+
                ' { @Test @androidx.test.filters.SdkSuppress(minSdkVersion = '+str(minimum)+') fun '+method+'() {} }')
        legacy = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        modern = partition.plan(self.root, ['example.RiskTest'], '36', 'a'*40)
        self.assertEqual(4, len(legacy['sdk_omissions']))
        self.assertEqual(4, len(legacy['expected']))
        self.assertEqual([], modern['sdk_omissions'])
        self.assertEqual(8, len(modern['expected']))
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


class DeferredMethodTest(unittest.TestCase):
    """#815: exactly one device method (sharedNeighbor) is deferred to v0.34.1; nothing else is."""
    CLS = 'io.github.anastaci1a.localica.PopupShadowContinuityTest'
    METHOD = CLS + '#sharedNeighborShadowHasNoOwnershipHandoffThroughRepeatedCycles'

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        relative = Path('app/src/androidTest/java/io/github/anastaci1a/localica/PopupShadowContinuityTest.kt')
        (self.root/relative).parent.mkdir(parents=True)
        (self.root/relative).write_text((ROOT/relative).read_text())
        (self.root/'app/src/androidTest/java/example').mkdir()
        (self.root/'app/src/androidTest/java/example/RiskTest.kt').write_text(
            'package example\nimport org.junit.Test\nclass RiskTest { @Test fun one() {} }')

    def test_exactly_one_method_is_deferred_and_the_rest_of_the_class_still_gates(self):
        self.assertEqual({self.METHOD: 'DEFERRED v0.34.2 #815'}, partition.DEFERRED)
        for api in ('26', '36'):
            plan = partition.plan(self.root, ['example.RiskTest'], api, 'a'*40)
            self.assertEqual([{'id': self.METHOD, 'reason': 'DEFERRED v0.34.2 #815'}], plan['deferred'])
            self.assertNotIn(self.METHOD, plan['expected'])
            self.assertNotIn(self.METHOD, plan['remaining'])
            inv = partition.discover(self.root)
            siblings = {m for m in inv['runtime_methods'] if m.startswith(self.CLS + '#')}
            self.assertIn(self.METHOD, siblings)
            self.assertGreater(len(siblings), 1)
            self.assertEqual(siblings - {self.METHOD}, {m for m in plan['expected'] if m.startswith(self.CLS + '#')})
            self.assertTrue({m for m in inv['runtime_methods'] if m not in plan['expected']} <= {self.METHOD}
                            | {x['id'] for x in plan['sdk_omissions']})

    def test_shard_plan_records_the_deferral_and_never_assigns_the_method(self):
        weights = self.root/'durations.csv'
        weights.write_text('api,class,tests,total_seconds,skipped\n26,'+self.CLS+',4,100,0\n')
        result = partition.shards(self.root, '26', 1, weights, 'a'*40, risks=['example.RiskTest'])
        self.assertEqual([self.METHOD], [x['id'] for x in result['deferred']])
        self.assertEqual([self.METHOD], result['shards'][0]['excluded_methods'])
        self.assertNotIn(self.METHOD, result['shards'][0]['methods'])
        self.assertEqual(self.CLS, result['shards'][0]['classes'][0])

    def test_union_reports_deferred_and_rejects_a_run_of_it(self):
        plan = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        def xml(folder, ids):
            folder.mkdir()
            suite = ET.Element('testsuite', tests=str(len(ids)), failures='0', errors='0', skipped='0')
            for identity in ids:
                cls, method = identity.split('#')
                ET.SubElement(suite, 'testcase', classname=cls, name=method, time='1')
            (folder/'r.xml').write_text(ET.tostring(suite, encoding='unicode'))
        xml(self.root/'ok', plan['expected'])
        result = partition.union(self.root, '26', [self.root/'ok'], 1)
        self.assertEqual([self.METHOD], [x['id'] for x in result['deferred']])
        self.assertEqual(len(plan['expected']), result['executed_once'])
        xml(self.root/'ran', plan['expected'] + [self.METHOD])
        with self.assertRaises(ValueError):
            partition.union(self.root, '26', [self.root/'ran'], 1)

    def test_a_removed_source_method_fails_closed(self):
        path = self.root/'app/src/androidTest/java/io/github/anastaci1a/localica/PopupShadowContinuityTest.kt'
        path.write_text(path.read_text().replace('sharedNeighborShadowHasNoOwnershipHandoffThroughRepeatedCycles', 'renamed'))
        with self.assertRaises(ValueError):
            partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)

    def test_runner_and_summary_announce_and_filter_the_deferral(self):
        script = (ROOT/'.github/run-device-tests.sh').read_text()
        self.assertIn('full_partition.py deferred', script)
        self.assertIn('notClass=$deferred_methods', script)
        self.assertIn('full_excluded_classes=${full_excluded_classes:+$full_excluded_classes,}$deferred_methods', script)
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('full_partition.py announce', workflow)
        self.assertIn("print('%s API %s: %s' % (row['reason'], api, row['id']))", (ROOT/'.github/test-evidence/full_partition.py').read_text())
        self.assertIn('GITHUB_STEP_SUMMARY', workflow)



class SharedTestInventoryTest(unittest.TestCase):
    """#870: src/sharedTest compiles into androidTest, so it is inventoried and sharded exactly once."""
    SHARED = 'io.github.anastaci1a.localica.ui.engine.PopupWindowMotionTest'

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for name, kind in (('RiskTest', 'androidTest'), ('OtherTest', 'androidTest'), ('SharedMotionTest', 'sharedTest')):
            folder = self.root/'app/src'/kind/'java/example'
            folder.mkdir(parents=True, exist_ok=True)
            (folder/(name+'.kt')).write_text('package example\nimport org.junit.Test\nclass '+name+' { @Test fun one() {} @Test fun two() {} }')
        # A helper without tests is hashed but is not a selector.
        (self.root/'app/src/sharedTest/java/example/Helper.kt').write_text('package example\nclass Helper')
        self.weights = self.root/'durations.csv'
        self.weights.write_text('api,class,tests,total_seconds,skipped\n26,example.OtherTest,2,10,0\n')

    def xml(self, folder, ids):
        folder.mkdir()
        suite = ET.Element('testsuite', tests=str(len(ids)), failures='0', errors='0', skipped='0')
        for identity in ids:
            cls, method = identity.split('#')
            ET.SubElement(suite, 'testcase', classname=cls, name=method, time='1')
        (folder/'r.xml').write_text(ET.tostring(suite, encoding='unicode'))

    def test_shared_class_is_in_the_inventory_and_hashed(self):
        inv = partition.discover(self.root)
        self.assertIn('example.SharedMotionTest', inv['selectors'])
        self.assertIn('example.SharedMotionTest#one', inv['runtime_methods'])
        self.assertIn('app/src/sharedTest/java/example/Helper.kt', inv['all_source_sha256'])

    def test_shared_class_lands_in_exactly_one_shard_and_the_others_exclude_it(self):
        result = partition.shards(self.root, '26', 2, self.weights, 'a'*40, risks=['example.RiskTest'])
        owners = [s['index'] for s in result['shards'] if 'example.SharedMotionTest' in s['classes']]
        self.assertEqual(1, len(owners))
        for shard in result['shards']:
            self.assertEqual(owners == [shard['index']], 'example.SharedMotionTest' in shard['classes'])
            self.assertEqual(owners != [shard['index']], 'example.SharedMotionTest' in shard['excluded_classes'])
        ids = [m for s in result['shards'] for m in s['methods']]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertIn('example.SharedMotionTest#one', ids)

    def test_union_accepts_one_run_and_rejects_every_shard_running_it(self):
        plan = partition.plan(self.root, ['example.RiskTest'], '26', 'a'*40)
        shared = ['example.SharedMotionTest#one', 'example.SharedMotionTest#two']
        self.assertTrue(set(shared) <= set(plan['expected']))
        rest = [x for x in plan['expected'] if x not in shared]
        self.xml(self.root/'a', rest + shared)
        self.assertEqual(len(plan['expected']), partition.union(self.root, '26', [self.root/'a'], 1)['executed_once'])
        self.xml(self.root/'s1', rest[:2] + shared)
        self.xml(self.root/'s2', rest[2:] + shared)
        with self.assertRaisesRegex(ValueError, 'Duplicate identity across shards'):
            partition.union(self.root, '26', [self.root/'s1', self.root/'s2'], 2)

    def test_shared_class_declared_in_both_trees_is_refused(self):
        (self.root/'app/src/sharedTest/java/example/RiskTest.kt').write_text('package example\nimport org.junit.Test\nclass RiskTest { @Test fun one() {} }')
        with self.assertRaises(ValueError):
            partition.plan(self.root, ['example.OtherTest'], '26', 'a'*40)

    def test_real_repository_shared_tests_are_inventoried(self):
        if not (ROOT/'app/src/sharedTest/java').is_dir():
            self.skipTest('no sharedTest tree')
        inv = partition.discover(ROOT)
        self.assertIn(self.SHARED, inv['selectors'])
        self.assertTrue(any(m.startswith(self.SHARED + '#') for m in inv['runtime_methods']))

    def test_empty_or_invalid_union_result_fails_clearly_without_a_traceback(self):
        import contextlib, io, subprocess
        empty = self.root/'union-empty.json'
        empty.write_text('')
        with self.assertRaisesRegex(ValueError, 'empty'):
            partition.announce(empty, '26')
        bad = self.root/'union-bad.json'
        bad.write_text('not json')
        with self.assertRaisesRegex(ValueError, 'not valid JSON'):
            partition.announce(bad, '26')
        with self.assertRaisesRegex(ValueError, 'unreadable'):
            partition.announce(self.root/'missing.json', '26')
        script = str(ROOT/'.github/test-evidence/full_partition.py')
        run = subprocess.run([sys.executable, script, 'announce', '--api', '26', '--union-result', str(empty)],
                             capture_output=True, text=True)
        self.assertEqual(2, run.returncode)
        self.assertIn('Full-suite partition rejected: Union result is empty for API 26', run.stderr)
        self.assertNotIn('Traceback', run.stderr)

    def test_announce_prints_each_deferral_and_the_workflow_uses_it_failing_closed(self):
        import contextlib, io
        good = self.root/'union.json'
        good.write_text(json.dumps({'deferred': [{'id': 'x.Y#z', 'reason': 'DEFERRED v0.34.1 #815'}]}))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            partition.announce(good, '36')
        self.assertEqual('DEFERRED v0.34.1 #815 API 36: x.Y#z\n', out.getvalue())
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        self.assertIn('full_partition.py announce --api "$api" --union-result "union-api-$api.json"', workflow)
        self.assertNotIn('|| true\n          done', workflow)


if __name__=='__main__':unittest.main()
