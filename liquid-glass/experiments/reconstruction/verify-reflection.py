"""Verify optional reflection against real paired inputs, without refitting.

python verify-reflection.py /private/active-switch/paired
"""
from pathlib import Path
import argparse,copy,json
import numpy as np
from extended_fit import Study
from paired_fit import Cached,permitted
from prepared import Prepared
from source_support import coverage_report,supported
from grazing_reflection import apply,layers
from material_local import statistics

ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);args=ap.parse_args()
old=json.loads((args.folder/'pass-22.json').read_text())['current'];p=copy.deepcopy(old['parameters'])
p['grazing_reflection']=dict(strength=1.,width=.03895748531996116,angular_power=1.5519447192573868,source_sigma=.007,direction=[0,1]);co=np.array(old['material']);records=[]
for name in ['a','b']:
 s=Study(args.folder/name);base=s.render(old['parameters'],co)
 assert apply(s,old['parameters'],base) is base,'Absent reflection must be identity'
 zero=copy.deepcopy(p);zero['grazing_reflection']['strength']=0.;assert np.array_equal(s.render(zero,co),base)
 predicted=s.render(p,co);cache=Cached(s,p);assert np.max(abs(cache.render(co)-predicted))<2e-6
 weight,_=layers(s,p);assert weight.min()>=0 and weight.max()<=1+1e-12
 motion=Prepared(s,p,co,1);assert np.max(abs(motion.render()-predicted))<2e-6
 s.backdrop_shift=[0.,7.25];assert np.max(abs(motion.render()-s.render(p,co)))<2e-6;s.backdrop_shift=[0.,0.];del motion
 high=Prepared(s,p,co,2);assert np.max(abs(high.render()-s.render(p,co,2)))<2e-6;del high
 footprint=coverage_report(s,p);assert supported(s,footprint)
 from motion_support import prepare
 import cv2
 known=cv2.imread(str(s.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
 _,_,moving=prepare(s,p,known,s.shape_coverage(p),45.)
 assert moving['reflection_environment_3sigma_defined_min']>=.999
 assert moving['reflection_environment_3sigma_observed_min']==0.,'Estimated track is not observed evidence'
 # The reflected field follows transmission filtering, so the material
 # derivative must also be multiplied by (1-F) after that Gaussian filter.
 if name=='a':
  forms=statistics(cache,co);step=np.zeros(23);step[3]=1e-5;actual=s.scores(cache.render(co+step),include_ssim=False)
  H,g=forms['glass'];estimate=s.scores(predicted,include_ssim=False)['glass']['rmse']**2+2*g@step+step@H@step
  assert abs(actual['glass']['rmse']**2-estimate)<.001,'Reflection material Jacobian order'
 records.append(dict(sample=name,scores=s.scores(predicted),source=footprint))
assert permitted([r['scores'] for r in records],old['scores'])
print(json.dumps(dict(status='PASS zero/default identity, native/cache, scale2/prepared, moving-source, bounded reflection, material derivative, both source/native gates',records=records)))
