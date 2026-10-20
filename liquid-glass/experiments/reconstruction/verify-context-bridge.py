"""Replay a private Home correction against protected historical measurements.

python verify-context-bridge.py /private/music-home-vector /private/bridge.json
"""
from pathlib import Path
import argparse,copy,json
import cv2,numpy as np
from extended_fit import Study
from paired_fit import Cached,permitted,foreground_errors
from prepared import Prepared
from source_support import coverage_report,supported
from material_context import affine
from material_local import statistics

ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('bridge',type=Path);args=ap.parse_args()
bridge=json.loads(args.bridge.read_text())['best'];assert bridge is not None
single=json.loads((args.root/'accepted.json').read_text());old=json.loads((args.root/'paired/accepted.json').read_text())
targets=[single['scores'],old['scores'][1]];p={**bridge['parameters'],'ambient_rim_response':bridge['ambient_rim_response']};co=np.array(bridge['material']);records=[];studies=[]
for i,name in enumerate(['a','b']):
 s=Study(args.root/'paired'/name);studies.append(s)
 previous=s.scores(s.render(old['parameters'],np.array(old['material'])))
 assert abs(previous['loss']-old['scores'][i]['loss'])<1e-6,'Old context-free path changed'
 base_parameters={k:v for k,v in bridge['parameters'].items() if k!='ambient_rim_response'}
 zero={**base_parameters,'ambient_rim_response':[0.,0.,0.]}
 assert np.array_equal(s.render(zero,co),s.render(base_parameters,co)),'Zero ambient response must preserve identity'
 T,b=affine(s,p);expected=np.zeros(23);expected[[6,9,12]]=(bridge['source_luma'][i]-.5)*np.array(bridge['ambient_rim_response'])
 assert np.array_equal(T,np.eye(23)) and np.max(abs(b-expected))<1e-12,'Independent frozen context measurement'
 predicted=s.render(p,co);score=s.scores(predicted)
 assert abs(score['loss']-bridge['scores'][i]['loss'])<1e-6,'Independent native bridge replay'
 cache=Cached(s,p);assert np.max(abs(cache.render(co)-predicted))<2e-6
 from profile_cache import ProfileCache
 profile=ProfileCache(s,p,co);coefficients=np.array([p[f'c{k}'] for k in range(7)])
 coefficients[2]+=.002;changed={**p,'c2':float(coefficients[2])}
 assert np.max(abs(profile.render(coefficients)-s.render(changed,co)))<2e-6,'Profile proposal must preserve the context offset'
 del profile
 for scale in [1,2]:
  prepared=Prepared(s,p,co,scale)
  for shift in [[0.,0.],[3.125,7.25]]:
   s.backdrop_shift=shift
   assert np.max(abs(prepared.render()-s.render(p,co,scale)))<2e-6,'Moving prepared context mismatch'
  del prepared
 s.backdrop_shift=[0.,0.]
 source=coverage_report(s,p);assert supported(s,source)
 if i==0:
  full={**p,'material_context_inset':0.}
  assert not supported(s,coverage_report(s,full)),'The unrecovered boundary must not become accepted context'
  original=s.source.copy();s.source[:]=np.where(cv2.imread(str(s.folder/'known.png'),0)[...,None]>254,s.source,[0.,1.,0.])
  _,poisoned=affine(s,p);assert np.array_equal(poisoned,b),'Unknown diagnostic colors must not affect the accepted source mean'
  s.source=original
 from motion_support import prepare
 known=cv2.imread(str(s.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
 _,_,motion=prepare(s,p,known,s.shape_coverage(p),45.)
 assert motion['context_mean_source_defined_min']>=.999
 if i==0:
  forms=statistics(cache,co);step=np.zeros(23);step[6]=1e-5;actual=s.scores(cache.render(co+step))
  H,g=forms['glass'];estimate=score['glass']['rmse']**2+2*g@step+step@H@step
  assert abs(actual['glass']['rmse']**2-estimate)<.001,'Affine-context clipping/Jacobian mismatch'
 records.append(dict(sample=name,scores=score,source=source,motion=motion))
assert permitted([r['scores'] for r in records],targets)
fg=foreground_errors(studies,p,co,old['foreground_response']);assert all(x<=y+1e-8 for x,y in zip(fg,old['foreground_scores']['after_mse'])),'Retained shared foreground regressed'
print(json.dumps(dict(status='PASS historical/zero identity, frozen mean, native bridge/cache, HQ/prepared, moving context, affine material derivative, source/motion confidence and retained shared foreground',records=records,foreground_mse=fg,protected_targets=targets)))
