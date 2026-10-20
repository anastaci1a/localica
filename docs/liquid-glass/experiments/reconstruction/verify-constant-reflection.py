"""Verify a declared constant incident field on private paired inputs.

python verify-constant-reflection.py /private/corrected/paired /private/probe/report.json
"""
from pathlib import Path
import argparse,copy,json
import cv2,numpy as np
from extended_fit import Study
from paired_fit import Cached,permitted,foreground_errors
from prepared import Prepared
from material_local import statistics
from source_support import coverage_report,supported
from grazing_reflection import layers
from motion_support import prepare

ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('probe',type=Path);args=ap.parse_args()
old=json.loads((args.folder/'accepted.json').read_text());probe=json.loads(args.probe.read_text());trial=next(r for r in probe['verified'] if r['allowed'])
assert old==probe['baseline'],'Recheck any later incumbent first'
p={**old['parameters'],'grazing_reflection':dict(strength=trial['strength'],width=trial['width'],angular_power=0.,source_sigma=0.,direction=[0.,1.],environment_rgb=[1.,1.,1.])};co=np.array(old['material']);records=[];studies=[]
for i,n in enumerate(['a','b']):
 s=Study(args.folder/n);studies.append(s);base=s.render(old['parameters'],co);prediction=s.render(p,co)
 assert abs(s.scores(prediction)['loss']-trial['scores'][i]['loss'])<1e-6,'Native prototype differs'
 zero=copy.deepcopy(p);zero['grazing_reflection']['strength']=0.;assert np.array_equal(s.render(zero,co),base)
 weight,environment=layers(s,p);assert np.all(environment==1.) and weight.min()>=0 and weight.max()<=trial['strength']+1e-12
 cache=Cached(s,p);assert np.max(abs(cache.render(co)-prediction))<2e-6
 for scale in [1,2]:
  fast=Prepared(s,p,co,scale)
  for shift in [[0.,0.],[3.125,-2.25]]:
   s.backdrop_shift=shift;assert np.max(abs(fast.render()-s.render(p,co,scale)))<2e-6
  del fast
 s.backdrop_shift=[0.,0.]
 source=coverage_report(s,p);assert supported(s,source)
 assert source['reflection_environment_kind']=='declared_constant_rgb' and source['reflection_background_sampling'] is False
 assert 'reflection_source_observed_fraction' not in source,'A specified light must not be counted as photographic observation'
 known=cv2.imread(str(s.folder/'known.png'),0).astype(np.float32)/255;_,travel,moving=prepare(s,p,known,s.shape_coverage(p),45.,axis='x');assert travel>0
 if i==0:
  forms=statistics(cache,co);step=np.zeros(23);step[6]=1e-5;H,g=forms['glass'];expected=s.scores(prediction)['glass']['rmse']**2+2*g@step+step@H@step
  assert abs(s.scores(cache.render(co+step))['glass']['rmse']**2-expected)<.001
 records.append(dict(sample=n,scores=s.scores(prediction),source=source,motion=moving))
assert permitted([r['scores'] for r in records],old['scores'])
fg=foreground_errors(studies,p,co,old['foreground_response']);assert all(a<=b+1e-8 for a,b in zip(fg,old['foreground_scores']['after_mse']))
print(json.dumps(dict(status='PASS constant field, native/cache/HQ/prepared/source motion, zero identity, material derivative, source distinction and shared foreground',parameters=p['grazing_reflection'],records=records,foreground_mse=fg)))
