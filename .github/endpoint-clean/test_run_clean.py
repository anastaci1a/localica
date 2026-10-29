import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('clean',Path(__file__).with_name('run_clean.py'))
clean=importlib.util.module_from_spec(spec);spec.loader.exec_module(clean)

class CleanContracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def pngs(self,api='26'):
        for key in clean.expected_pngs(api):
            p=self.root/'popup-shadow-continuity'/key;p.parent.mkdir(parents=True,exist_ok=True)
            p.write_bytes(b'\x89PNG\r\n\x1a\n'+b'\0\0\0\rIHDR'+(1).to_bytes(4,'big')*2+b'\x08\x02'+b'\0'*7)
        return self.root
    def check(self,api='26'):
        return clean.check_pngs(self.root,api,lambda p:hashlib.sha256(p.read_bytes()).hexdigest())
    def logs(self):
        for method in clean.EXPECTED:(self.root/('logcat-'+method.replace('#','-')+'.txt')).write_text('original native output\n')
    def test_exact_png_contract_for_both_apis(self):
        for api in ('26','36'):
            names=clean.expected_pngs(api)
            self.assertEqual(62,len(names));self.assertEqual(32,sum(n.startswith('utility-') for n in names))
            self.assertEqual(30,sum(n.startswith('nested-') for n in names))
        self.pngs();self.assertEqual(62,len(self.check()))
    def test_missing_extra_and_duplicate_png_rejected(self):
        self.pngs();p=next(self.root.rglob('*.png'));raw=p.read_bytes();p.unlink()
        with self.assertRaises(ValueError):self.check()
        p.write_bytes(raw);extra=p.with_name('unknown.png');extra.write_bytes(raw)
        with self.assertRaises(ValueError):self.check()
        extra.unlink();copy=self.root/'duplicate'/p.parent.name/p.name;copy.parent.mkdir(parents=True);copy.write_bytes(raw)
        with self.assertRaises(ValueError):self.check()
    def test_wrong_api_or_invalid_png_rejected(self):
        self.pngs()
        with self.assertRaises(ValueError):self.check('36')
        next(self.root.rglob('*.png')).write_bytes(b'not png')
        with self.assertRaises(ValueError):self.check()
    def test_exact_logs_and_diagnostic_tag_absence(self):
        self.logs();clean.check_logs(self.root)
        next(self.root.glob('*.txt')).write_text('I NativeSuiteCost: stage=popup-endpoint elapsed_ns=3')
        with self.assertRaises(ValueError):clean.check_logs(self.root)
    def test_missing_extra_log_rejected(self):
        self.logs();next(self.root.glob('*.txt')).unlink()
        with self.assertRaises(ValueError):clean.check_logs(self.root)
        self.logs();(self.root/'logcat-extra.txt').write_text('')
        with self.assertRaises(ValueError):clean.check_logs(self.root)
    def test_source_diagnostics_absent(self):
        p=self.root/'app/src/androidTest/Popup.kt';p.parent.mkdir(parents=True);p.write_text('class Popup')
        clean.no_source_probes(self.root)
        p.write_text('NativeSuiteCost.measure {}')
        with self.assertRaises(ValueError):clean.no_source_probes(self.root)
        p.write_text('class Popup');(p.parent/'NativeSuiteCostProbe.kt').write_text('')
        with self.assertRaises(ValueError):clean.no_source_probes(self.root)
    def test_wrong_support_commit_rejected_before_import(self):
        with patch.object(clean,'git',return_value='wrong'):
            with self.assertRaisesRegex(ValueError,'support SHA'):clean.load_support(self.root)
    def test_source_and_support_immutable_pins(self):
        self.assertEqual('afebeba6d459e97743e6e2dd0bb14f91510db2ca',clean.SOURCE_SHA)
        self.assertEqual('e3ac4f31ed506b55f39e9fa806010106ea0965bd',clean.SUPPORT_SHA)
        self.assertEqual(3,len(clean.EXPECTED))

if __name__=='__main__':unittest.main()
