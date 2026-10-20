"""Native/cache/HQ/motion checks using private frozen paired inputs.

python verify-chromatic.py /private/active-switch/paired /private/probe/verified.json
"""
from pathlib import Path
import argparse,copy,json
import cv2,numpy as np
from extended_fit import Study
from paired_fit import Cached,permitted
from prepared import Prepared
from source_support import coverage_report,supported
from chromatic_transmission import weight

ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('candidate',type=Path);args=ap.parse_args()
old=json.loads((args.folder/'pass-23.json').read_text())['current']
trial=json.loads(args.candidate.read_text());q=dict(zip(['strength','length','angle','fade_center','fade_width'],trial['parameters']))
p={**old['parameters'],'directional_transmission':q};co=np.array(old['material']);records=[]
for index,name in enumerate(['a','b']):
 s=Study(args.folder/name);base=s.render(old['parameters'],co)
 assert abs(s.scores(base)['loss']-old['scores'][index]['loss'])<1e-6,'Pre-chromatic path changed'
 for key in ['strength','length']:
  zero=copy.deepcopy(p);zero['directional_transmission'][key]=0
  assert np.array_equal(s.render(zero,co),base),'Disabled channel separation must be exact identity'
 C,J=s.field(s.x,s.y,p);_,oldJ=s.field(s.x,s.y,old['parameters']);assert np.array_equal(J,oldJ),'Material lighting must not shift'
 predicted=s.render(p,co);scores=s.scores(predicted)
 assert abs(scores['loss']-trial['scores'][index]['loss'])<1e-6,'Analytic proposal replay'
 assert np.max(abs(Cached(s,p).render(co)-predicted))<2e-6
 for scale in [1,2]:
  motion=Prepared(s,p,co,scale)
  for shift in [[0.,0.],[3.125,7.25]]:
   s.backdrop_shift=shift
   assert np.max(abs(motion.render()-s.render(p,co,scale)))<2e-6,'Prepared/source translation mismatch'
  del motion
 s.backdrop_shift=[0.,0.]
 w=weight(s,p,s.x);assert 0<=w.min()<=w.max()<=1
 assert np.all(np.diff(w,axis=1)<=1e-12),'Contribution must fade toward right'
 assert np.all(w==w[0:1]),'Horizontal fade must not bend with the silhouette'
 footprint=coverage_report(s,p);assert supported(s,footprint)
 from motion_support import prepare
 known=cv2.imread(str(s.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
 _,_,moving=prepare(s,p,known,s.shape_coverage(p),45.)
 assert moving['chromatic_source_3sigma_defined_min']>=.999
 assert moving['chromatic_source_3sigma_observed_min']==0.,'Estimated source remains explicitly unobserved'
 records.append(dict(sample=name,scores=scores,source=footprint,motion=moving))
assert permitted([r['scores'] for r in records],old['scores'])
print(json.dumps(dict(status='PASS default/zero identity, analytic replay, unchanged lighting, native/cache, HQ/prepared, two-axis source translation, fade, source and motion gates',parameters=q,records=records)))
