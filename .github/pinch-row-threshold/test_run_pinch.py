import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('pinch',Path(__file__).with_name('run_pinch.py'))
pinch=importlib.util.module_from_spec(spec);spec.loader.exec_module(pinch)

class PinchContracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def config(self):
        return {'baseline_sha':pinch.BASELINE_SHA,'candidate_sha':'1'*40,'equivalence_sha':'2'*40,
                'test_sha256':{'baseline':'3'*64,'candidate':'4'*64,'equivalence':'5'*64}}
    def record(self,tag,message,pid='101',ms=1):
        return f'10-29 08:00:00.{ms:03d}   {pid}   102 I {tag}: {message}'
    def logs(self,equivalence=False):
        for method in pinch.METHODS:
            ident=pinch.CLASS+'#'+method
            lines=[self.record('TestRunner',f'started: {method}({pinch.CLASS})')]
            if equivalence:
                full='true' if 'Gallery' in method or method.startswith('gallery') else 'false'
                lines += [self.record('PinchRowEquivalence',f'rows=4 top=0 bottom=20 width=720 fullWidth={full}',ms=i+2) for i in range(62)]
            lines += [self.record('PinchReleaseHandoff',f'outward=true cancelled=false frame={i} t={i*16} center=5.0 expected=5.0') for i in range(60)]
            lines += [self.record('TestRunner',f'finished: {method}({pinch.CLASS})')]
            (self.root/('logcat-'+ident.replace('#','-')+'.txt')).write_text('\n'.join(lines)+'\n')
    def mutate_log(self,change):
        p=next(self.root.glob('logcat-*.txt'));p.write_text(change(p.read_text()))
    def test_fixed_inventory_and_order(self):
        self.assertEqual(8,len(pinch.EXPECTED))
        self.assertEqual([('E0','equivalence'),('A1','baseline'),('B1','candidate'),('B2','candidate'),('A2','baseline')],pinch.trial_plan())
        good={'selectors':[pinch.CLASS],'method_count':8,'runtime_methods':sorted(pinch.EXPECTED)}
        pinch.check_inventory(good)
        for key,value in [('selectors',['Other']),('method_count',7),('runtime_methods',good['runtime_methods'][:-1]),('runtime_methods',good['runtime_methods'][:-1]+[good['runtime_methods'][0]])]:
            bad=copy.deepcopy(good);bad[key]=value
            with self.assertRaises(ValueError):pinch.check_inventory(bad)
    def test_valid_config_and_published_pins(self):
        pinch.validate_config(self.config())
        actual=json.loads(Path(__file__).with_name('config.json').read_text())
        pinch.validate_config(actual)
        self.assertEqual('39f5b6a4a27fc46c907b2d4c184c2ef5e3a0bb56',pinch.BASELINE_SHA)
        self.assertEqual('e3ac4f31ed506b55f39e9fa806010106ea0965bd',pinch.SUPPORT_SHA)
    def test_config_wrong_baseline_alias_duplicate_and_hash_rejected(self):
        for key,value in [('baseline_sha','3'*40),('candidate_sha','main'),('candidate_sha',pinch.BASELINE_SHA),('equivalence_sha','PENDING')]:
            bad=self.config();bad[key]=value
            with self.assertRaises(ValueError):pinch.validate_config(bad)
        bad=self.config();bad['test_sha256']['candidate']='wrong'
        with self.assertRaises(ValueError):pinch.validate_config(bad)
        bad=self.config();bad['test_sha256']['candidate']=bad['test_sha256']['baseline']
        with self.assertRaises(ValueError):pinch.validate_config(bad)
        bad=self.config();bad['class']='Other'
        with self.assertRaises(ValueError):pinch.validate_config(bad)
    def test_clean_exact_frame_logs(self):
        self.logs();rows=pinch.check_logs(self.root,False)
        self.assertEqual(8,len(rows));self.assertEqual(480,sum(r['release_frames'] for r in rows))
    def test_precheck_exact_snapshot_count(self):
        self.logs(True);rows=pinch.check_logs(self.root,True)
        self.assertEqual(496,sum(len(r['equivalence_samples']) for r in rows))
    def test_missing_extra_duplicate_method_logs(self):
        self.logs();p=next(self.root.glob('*.txt'));p.unlink()
        with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
        self.logs();p=next(self.root.glob('*.txt'));q=self.root/'duplicate'/p.name;q.parent.mkdir();q.write_bytes(p.read_bytes())
        with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
        q.unlink();(self.root/'logcat-extra.txt').write_text('')
        with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
    def test_missing_duplicate_reordered_frame(self):
        for change in [lambda x:x.replace('frame=10 ','frame=9 ',1),lambda x:'\n'.join(l for l in x.splitlines() if 'frame=10 ' not in l),lambda x:x.replace('frame=10 ','frame=99 ',1)]:
            self.logs();self.mutate_log(change)
            with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
    def test_wrong_method_process_and_boundary(self):
        for change in [lambda x:x.replace('started: ','finished: ',1),lambda x:x.replace('(io.github.anastaci1a.localica.PinchReleaseHandoffTest)','(Other)',1),lambda x:x.replace('101   102 I PinchReleaseHandoff','201   102 I PinchReleaseHandoff',1)]:
            self.logs();self.mutate_log(change)
            with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
    def test_missing_extra_precheck_samples(self):
        for change in [lambda x:x.replace('I PinchRowEquivalence:','I Ignored:',1),lambda x:x.replace('I TestRunner: finished:', 'I PinchRowEquivalence: rows=4 top=0 bottom=20 width=720 fullWidth=false\n10-29 08:00:00.001 101 102 I TestRunner: finished:',1)]:
            self.logs(True);self.mutate_log(change)
            with self.assertRaises(ValueError):pinch.check_logs(self.root,True)
    def test_precheck_wrong_geometry_sampling_and_process(self):
        for change in [lambda x:x.replace('width=720','width=0',1),lambda x:x.replace('rows=4','rows=99',1),lambda x:x.replace('fullWidth=false','fullWidth=true',1) if 'fullWidth=false' in x else x.replace('fullWidth=true','fullWidth=false',1),lambda x:x.replace('101   102 I PinchRowEquivalence','201   102 I PinchRowEquivalence',1)]:
            self.logs(True);self.mutate_log(change)
            with self.assertRaises(ValueError):pinch.check_logs(self.root,True)
    def test_probe_outside_boundary_or_malformed(self):
        for change in [lambda x:self.record('PinchRowEquivalence','rows=4 top=0 bottom=20 width=720 fullWidth=false')+'\n'+x,lambda x:x.replace('rows=4 top=0','rows=? top=0',1),lambda x:x.replace('10-29 08:00:00.002   101   102 I PinchRowEquivalence:','broken PinchRowEquivalence:',1)]:
            self.logs(True);self.mutate_log(change)
            with self.assertRaises(ValueError):pinch.check_logs(self.root,True)
    def test_unexpected_probes_in_timed_logs(self):
        self.logs(True)
        with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
        self.logs();self.mutate_log(lambda x:x+'NativeSuiteCost\n')
        with self.assertRaises(ValueError):pinch.check_logs(self.root,False)
    def test_duplicate_raw_precheck_record_at_same_total_count_rejected(self):
        self.logs(True)
        def duplicate(text):
            lines=text.splitlines();sample=[i for i,l in enumerate(lines) if 'PinchRowEquivalence:' in l]
            lines[sample[1]]=lines[sample[0]]
            return '\n'.join(lines)+'\n'
        self.mutate_log(duplicate)
        with self.assertRaisesRegex(ValueError,'Duplicate raw equivalence'):pinch.check_logs(self.root,True)
    def test_instrumentation_apks_distinct_across_source_variants(self):
        first={pinch.TEST_APK:'a'*64};second={pinch.TEST_APK:'b'*64}
        pinch.check_distinct_test_apks('first',first,{'first':first})
        pinch.check_distinct_test_apks('second',second,{'first':first,'second':second})
        with self.assertRaisesRegex(ValueError,'identical instrumentation'):
            pinch.check_distinct_test_apks('second',first,{'first':first,'second':first})
    def test_source_probe_scope(self):
        p=self.root/pinch.TEST_SOURCE;p.parent.mkdir(parents=True);p.write_text('class Pinch {}')
        pinch.check_source_probes(self.root)
        p.write_text('PinchRowEquivalence')
        with self.assertRaises(ValueError):pinch.check_source_probes(self.root)
        pinch.check_source_probes(self.root,True)
        q=p.with_name('Other.kt');q.write_text('PinchRowEquivalence')
        with self.assertRaises(ValueError):pinch.check_source_probes(self.root,True)
        q.unlink();p.write_text('NativeSuiteCost')
        with self.assertRaises(ValueError):pinch.check_source_probes(self.root,True)
    def test_wrong_support_revision_fails_before_import(self):
        with patch.object(pinch,'git',return_value='wrong'):
            with self.assertRaisesRegex(ValueError,'support SHA'):pinch.load_support(self.root)

if __name__ == '__main__':unittest.main()
