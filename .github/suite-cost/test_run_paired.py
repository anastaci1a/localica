import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('paired',Path(__file__).with_name('run_paired.py'))
paired=importlib.util.module_from_spec(spec);spec.loader.exec_module(paired)
EXPECTED={'io.github.anastaci1a.localica.CostTest#a','io.github.anastaci1a.localica.CostTest#b','io.github.anastaci1a.localica.CostTest#c'}

class ReconcileTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
    def xml(self,ids=None,outcome=None):
        folder=self.root/'xml';folder.mkdir(exist_ok=True)
        ids=sorted(EXPECTED) if ids is None else ids
        root=ET.Element('testsuite',tests=str(len(ids)),failures=str(int(outcome=='failure')),errors='0',skipped=str(int(outcome=='skipped')),time='6')
        for i,identity in enumerate(ids):
            name,method=identity.split('#');case=ET.SubElement(root,'testcase',classname=name,name=method,time=str(i+1))
            if i==0 and outcome:ET.SubElement(case,outcome)
        ET.ElementTree(root).write(folder/'TEST.xml')
        return folder
    def test_exact_three_counts_and_seconds(self):
        r=paired.parse_xml(self.xml(),EXPECTED)
        self.assertEqual(3,r['passed']);self.assertEqual(6,r['method_seconds']);self.assertEqual(6,r['suite_seconds'])
    def test_single_unmeasured_precheck_is_supported(self):
        ids={'io.github.anastaci1a.localica.NativeSuiteCostEquivalenceTest#opaqueArtworkBulkWriteMatchesEveryOriginalPixel'}
        self.assertEqual(1,paired.parse_xml(self.xml(sorted(ids)),ids)['passed'])
    def test_missing_extra_duplicate_and_skip_rejected(self):
        for ids,outcome in [(sorted(EXPECTED)[:2],None),(sorted(EXPECTED)+['other.Test#extra'],None),(sorted(EXPECTED)*2,None),(sorted(EXPECTED),'skipped'),(sorted(EXPECTED),'failure')]:
            with self.subTest(ids=ids,outcome=outcome):
                with self.assertRaises(ValueError):paired.parse_xml(self.xml(ids,outcome),EXPECTED)
    def test_no_xml_and_symlink_root_rejected(self):
        empty=self.root/'empty';empty.mkdir()
        with self.assertRaises(ValueError):paired.parse_xml(empty,EXPECTED)
        link=self.root/'link';link.symlink_to(self.xml(),target_is_directory=True)
        with self.assertRaises(ValueError):paired.parse_xml(link,EXPECTED)
    def test_count_mismatch_rejected(self):
        folder=self.xml();p=folder/'TEST.xml';p.write_text(p.read_text().replace('tests="3"','tests="4"'))
        with self.assertRaises(ValueError):paired.parse_xml(folder,EXPECTED)
    def test_entities_rejected(self):
        folder=self.xml();p=folder/'TEST.xml';p.write_text('<!DOCTYPE testsuite []>'+p.read_text())
        with self.assertRaises(ValueError):paired.parse_xml(folder,EXPECTED)
    def monitor(self):
        root=self.root/'live';(root/'observed').mkdir(parents=True);(root/'publishing').mkdir()
        identity={'sha':'a'*40,'api':'26','lane':'bulk-A1','command_id':'id'}
        observed=dict(identity,status='completed',child_returncode=0,observer_exit_status=0,observer_error=None,cancel_signal=None,observed_failure_lines=0)
        supervisor=dict(sha='a'*40,api='26',monitoring_complete=True,observer_exit=0,publisher_exit=0,supervisor_error=None)
        publisher=dict(identity,status='monitoring_complete',observed_failures=0,observed_failure_lines=0,uploaded_packets=0,read_offset=0,observer_status='completed')
        (root/'observed/manifest.json').write_text(json.dumps(observed));(root/'observed/events.jsonl').write_text('')
        (root/'supervision.json').write_text(json.dumps(supervisor));(root/'publishing/complete.json').write_text(json.dumps(publisher))
        return root
    def test_matching_monitor_receipts_required(self):
        root=self.monitor()
        with patch.dict(os.environ,{'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1'}):
            self.assertTrue(paired.check_monitor(root,'a'*40,'26','bulk-A1')['monitoring_complete'])
            with self.assertRaises(ValueError):paired.check_monitor(root,'b'*40,'26','bulk-A1')
            with self.assertRaises(ValueError):paired.check_monitor(root,'a'*40,'36','bulk-A1')
            with self.assertRaises(ValueError):paired.check_monitor(root,'a'*40,'26','bulk-B1')
    def test_failure_event_and_degraded_monitor_rejected(self):
        root=self.monitor()
        with patch.dict(os.environ,{'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1'}):
            (root/'observed/events.jsonl').write_text('{}\n')
            with self.assertRaises(ValueError):paired.check_monitor(root,'a'*40,'26','bulk-A1')
            (root/'observed/events.jsonl').write_text('')
            (root/'publishing/monitoring-degraded.json').write_text('{}')
            with self.assertRaises(ValueError):paired.check_monitor(root,'a'*40,'26','bulk-A1')
    def test_actual_successful_native_receipt_schema(self):
        root=Path(__file__).parent/'fixtures/real-success'
        observed=json.loads((root/'observed/manifest.json').read_text())
        result=paired.check_monitor(root,observed['sha'],observed['api'],observed['lane'],observed['argv'])
        self.assertTrue(result['monitoring_complete'])
        with self.assertRaises(ValueError):
            paired.check_monitor(root,observed['sha'],observed['api'],observed['lane'],['wrong','command'])
    def test_successful_monitor_refuses_failure_packet(self):
        root=self.monitor();(root/'publishing/packet-1.json').write_text('{}')
        with self.assertRaises(ValueError):paired.check_monitor(root,'a'*40,'26','bulk-A1')
    def probe_log(self, identity, counts=None):
        cls, method = identity.split('#')
        messages = ['TestRunner: started: '+method+'('+cls+')']
        for stage, count in (paired.PROBE_CONTRACTS[identity] if counts is None else counts).items():
            messages += ['NativeSuiteCost: stage='+stage+' elapsed_ns='+str(1024+i) for i in range(count)]
        messages += ['TestRunner: finished: '+method+'('+cls+')']
        path = self.root/('logcat-'+cls+'-'+method+'.txt')
        path.write_text(''.join('10-29 06:00:00.%03d  6936  6953 I %s\n' % (i, text) for i,text in enumerate(messages)))
        return path
    def test_exact_artwork_probe_counts(self):
        ids={k for k in paired.PROBE_CONTRACTS if 'ArtworkEditRestorationTest#' in k}
        for identity in ids:self.probe_log(identity)
        rows=paired.read_probes(self.root,ids)
        self.assertEqual(3,sum(len(r['samples']) for r in rows))
        self.assertEqual(ids,{r['method'] for r in rows})
        self.assertTrue(all(r['sha256'] for r in rows))
    def test_exact_popup_probe_counts_and_zero_capsule(self):
        ids={k for k in paired.PROBE_CONTRACTS if 'PopupShadowContinuityTest#' in k}
        for identity in ids:self.probe_log(identity)
        rows=paired.read_probes(self.root,ids)
        self.assertEqual([0,32,34],sorted(len(r['samples']) for r in rows))
    def test_precheck_requires_boundaries_but_zero_probes(self):
        identity=next(k for k in paired.PROBE_CONTRACTS if 'EquivalenceTest#' in k)
        self.probe_log(identity)
        self.assertEqual([],paired.read_probes(self.root,{identity})[0]['samples'])
        self.probe_log(identity,{'artwork-pixel-initialization':1})
        with self.assertRaises(ValueError):paired.read_probes(self.root,{identity})
    def test_missing_extra_unknown_probe_counts_rejected(self):
        identity=next(iter(paired.PROBE_CONTRACTS))
        for counts in ({},{'artwork-pixel-initialization':2},{'wrong':1}):
            with self.subTest(counts=counts):
                self.probe_log(identity,counts)
                with self.assertRaises(ValueError):paired.read_probes(self.root,{identity})
    def test_duplicate_probe_record_rejected(self):
        identity=next(k for k in paired.PROBE_CONTRACTS if '#nestedShadow' in k)
        path=self.probe_log(identity);lines=path.read_text().splitlines(True)
        lines[2]=lines[1];path.write_text(''.join(lines))
        with self.assertRaisesRegex(ValueError,'Duplicate timing'):paired.read_probes(self.root,{identity})
    def test_probe_boundary_identity_process_and_format_enforced(self):
        identity=next(iter(paired.PROBE_CONTRACTS))
        path=self.probe_log(identity);lines=path.read_text().splitlines(True)
        variants=[lines[1:],lines[:-1],lines+[lines[-1]], [lines[1],lines[0],lines[2]],
                  [lines[0],lines[2],lines[1]], [lines[0].replace('started:', 'finished:'),*lines[1:]],
                  [lines[0].replace('RestorationTest','WrongTest'),*lines[1:]],
                  [lines[0],lines[1].replace('6936','9999'),lines[2]],
                  [lines[0],lines[1].replace('elapsed_ns=1024','elapsed_ns=-1'),lines[2]],
                  [lines[0],'NativeSuiteCost: stage=unknown elapsed_ns=1\n',lines[2]]]
        for i,variant in enumerate(variants):
            with self.subTest(i=i):
                path.write_text(''.join(variant))
                with self.assertRaises(ValueError):paired.read_probes(self.root,{identity})
    def test_missing_extra_duplicate_log_files_rejected(self):
        identity=next(iter(paired.PROBE_CONTRACTS))
        with self.assertRaises(ValueError):paired.read_probes(self.root,{identity})
        path=self.probe_log(identity)
        extra=self.root/'nested';extra.mkdir();(extra/path.name).write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):paired.read_probes(self.root,{identity})
        (extra/path.name).unlink();path.rename(self.root/'logcat-wrong.txt')
        with self.assertRaises(ValueError):paired.read_probes(self.root,{identity})
    def test_unknown_method_contract_rejected(self):
        with self.assertRaises(ValueError):paired.read_probes(self.root,EXPECTED)
    def test_archive_moves_and_refuses_overwrite(self):
        source=self.root/'source';source.mkdir();trial=self.root/'trial';trial.mkdir()
        path=source/paired.OUTPUTS[0];path.mkdir(parents=True);(path/'original.xml').write_text('raw')
        runner=object.__new__(paired.Run);runner.source=source
        runner.archive(trial)
        self.assertFalse(path.exists());self.assertEqual('raw',(trial/'raw'/paired.OUTPUTS[0]/'original.xml').read_text())
        path.mkdir(parents=True)
        with self.assertRaises(ValueError):runner.archive(trial)
    def test_stale_evidence_rejected_before_source_commands(self):
        from types import SimpleNamespace
        source=self.root/'source';source.mkdir();output=self.root/'evidence';output.mkdir()
        with self.assertRaisesRegex(ValueError,'stale evidence'):
            paired.run(SimpleNamespace(source=source,output=output,config=self.root/'missing',api='26'))

if __name__=='__main__':unittest.main()
