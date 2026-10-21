"""Target visibility stays frozen when an incident-source domain is extended."""
import hashlib,json,tempfile,unittest
from pathlib import Path
import cv2,numpy as np
from extended_fit import Study

class TargetVisibility(unittest.TestCase):
 def fixture(self,folder,separate=False):
  source=np.full((128,128,3),128,np.uint8);known=np.full((128,128),255,np.uint8);known[:,:64]=0
  for name,value in [('source.png',source),('target.png',source),('target-visible.png',known),('known.png',np.full_like(known,255) if separate else known)]:cv2.imwrite(str(folder/name),value)
  case=dict(id='visibility-fixture',center=[64,64],half=[24,24],radius=24,crop=[32,32,96,96],foreground=[],source_sha256={n:hashlib.sha256((folder/n).read_bytes()).hexdigest() for n in ['source.png','target.png','known.png','target-visible.png']})
  if separate:case['target_observation_mask']='target-visible.png'
  (folder/'case.json').write_text(json.dumps(case));return case
 def test_source_extension_preserves_measurement_masks(self):
  with tempfile.TemporaryDirectory() as t:
   a,b=Path(t)/'a',Path(t)/'b';a.mkdir();b.mkdir();self.fixture(a);self.fixture(b,True);old,new=Study(a),Study(b)
   for name in ['valid','rim','held','indices','weights']:np.testing.assert_array_equal(getattr(old,name),getattr(new,name))
   self.assertTrue(new.valid.any());self.assertLess(new.valid.sum(),(new.sd<-1/new.L).sum())
   image=np.random.default_rng(222).random((64,64,3),dtype=np.float32)
   self.assertEqual(old.scores(image,False),new.scores(image,False))
 def test_unfrozen_or_external_target_mask_rejected(self):
  with tempfile.TemporaryDirectory() as t:
   folder=Path(t);case=self.fixture(folder,True)
   for invalid in ['missing.png','../target-visible.png']:
    case['target_observation_mask']=invalid;(folder/'case.json').write_text(json.dumps(case))
    with self.assertRaises(AssertionError):Study(folder)

if __name__=='__main__':unittest.main()
