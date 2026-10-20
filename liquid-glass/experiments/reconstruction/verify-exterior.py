"""Check separate incident/exterior layers without changing native optics.

python verify-exterior.py /private/frozen-study /private/new-verification
"""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
import cv2,numpy as np
from extended_fit import Study
from extended_shadow import shadow
from PIL import Image

ap=argparse.ArgumentParser();ap.add_argument('study',type=Path);ap.add_argument('out',type=Path);a=ap.parse_args()
assert not a.out.exists(),'Preserve earlier verification'
shutil.copytree(a.study,a.out,ignore=shutil.ignore_patterns('final','first-capture-fixed-exterior'))
state=json.loads((a.out/'accepted.json').read_text());p=state['parameters'];co=np.array(state['material'])
s=Study(a.out);native=s.render(p,co);s.comparison(native,a.out/'default.png',p)
case=s.case.copy();name='exterior.png';shutil.copyfile(a.out/'source.png',a.out/name)
case['exterior_source']=name;case['source_sha256']={**case['source_sha256'],name:hashlib.sha256((a.out/name).read_bytes()).hexdigest()}
(a.out/'case.json').write_text(json.dumps(case,indent=2)+'\n')
same=Study(a.out);same.comparison(same.render(p,co),a.out/'same.png',p)
assert (a.out/'default.png').read_bytes()==(a.out/'same.png').read_bytes(),'Identical exterior changes the default comparison'
C=np.array([.2,.4,.6],np.float32);rgb=np.broadcast_to(C,s.source.shape)
assert cv2.imwrite(str(a.out/name),np.rint(rgb[:,:,::-1]*255).astype(np.uint8))
case['source_sha256'][name]=hashlib.sha256((a.out/name).read_bytes()).hexdigest();(a.out/'case.json').write_text(json.dumps(case,indent=2)+'\n')
other=Study(a.out)
for scale in [1,2]:assert np.array_equal(other.render(p,co,scale),s.render(p,co,scale)),'Exterior affects refracted/native glass'
other.comparison(other.render(p,co),a.out/'other.png',p)
assert (a.out/'other.png').read_bytes()!=(a.out/'same.png').read_bytes()
subprocess.run([sys.executable,str(Path(__file__).with_name('extended_media.py')),str(a.out),'--scale','1','--frames','4','--axis','x','--gif-max-mb','10'],check=True,stdout=subprocess.DEVNULL)
H,W=other.target_crop.shape[:2];yy,xx=np.mgrid[:H,:W].astype(np.float32);x0,y0,_,_=other.crop
opacity=shadow(other,p,(xx+x0-other.case['center'][0])/other.L,(yy+y0-other.case['center'][1])/other.L)
mask=(other.shape_coverage(p)<1e-8)&(opacity<.005)
assert mask.any(),'No unoccluded exterior check pixels'
frame=np.array(Image.open(a.out/'final/frame-000.png'))[:H,:W]
expected=np.rint(np.clip(C*(1-opacity[...,None]),0,1)*255).astype(np.uint8)
assert np.max(abs(frame[mask].astype(int)-expected[mask].astype(int)))<=1,'Motion does not use the declared exterior'
assert s.scores(native)==other.scores(other.render(p,co))
from extended_shadow import refit_fixed_region
q1,e1=refit_fixed_region(other,p,p)
other.source[:]=[1.,0.,1.];other.cache.clear()
q2,e2=refit_fixed_region(other,p,p)
assert q1==q2 and e1==e2,'Exterior shadow fit depends on the incident content'
print('PASS default/identical PNG identity, independent exterior composition, native/HQ glass identity, encoded-motion exterior and incident-independent shadow fitting')
