"""Replay scalar neutral material using the shared renderer; no optimization.

C=tau*S+(1-tau)*white in encoded RGB, applied per sample before native averaging.
python neutral_material.py INPUTS RECORD OUTPUT [--high-quality]
"""
import argparse,hashlib,json
from pathlib import Path
import cv2,numpy as np
from extended_fit import Study
from vector_revision import composite
from preview_size import dimensions
from publish import pair

def coefficients(tau):
    if not np.isfinite(tau) or not 0<=tau<=1:raise ValueError('Require0<=tau<=1')
    co=np.zeros(23);co[:3]=co[-3:]=tau;co[3:6]=1-tau
    return co

def replay(inputs,record,output,high_quality=False):
    s=Study(inputs);assert len(record['case_sha256'])==1
    assert hashlib.sha256(json.dumps(s.case,sort_keys=True).encode()).hexdigest()==next(iter(record['case_sha256'].values())),'Frozen input changed'
    co=coefficients(record['tau']);assert np.array_equal(co,np.asarray(record['material'])),'Declared material is not the scalar neutral response'
    p=record['parameters'];assert p.get('gamma',1)==1
    assert not any(k in p for k in ['ambient_rim_response','grazing_reflection','material_chroma_sensitivity']),'Unexpected extra material response'
    for glyphs in record['vector_sha256'].values():
        for name,digest in glyphs.items():assert Path(name).name==name and hashlib.sha256((inputs/name).read_bytes()).hexdigest()==digest
    M=s.render(p,co);C=composite(s,p,M);scores=s.scores(M);scores['E']=float(np.sqrt(scores['loss']))
    np.savez_compressed(output/'native-arrays.npz',material=M,complete=C,alpha=s.shape_coverage(p))
    (output/'scores.json').write_text(json.dumps(scores,indent=2)+'\n')
    x0,y0,x1,y1=s.crop;w,h=dimensions(x1-x0,y1-y0,large=record.get('large',False),high_quality=high_quality)
    if high_quality:
        scale=2**max(0,int(np.ceil(np.log2(w/(x1-x0)))));M=s.render(p,co,scale);C=composite(s,p,M,scale)
    image=np.uint8(np.rint(np.clip(C,0,1)*255));image=cv2.resize(image,(w,h),interpolation=cv2.INTER_AREA if high_quality else cv2.INTER_LANCZOS4)
    target=cv2.resize(np.uint8(np.rint(s.target_crop*255)),(w,h),interpolation=cv2.INTER_LANCZOS4)
    pair(target,image,('Apple source','Neutral material reconstruction'),font_size=32).save(output/'comparison.png')
    return scores

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('record',type=Path);ap.add_argument('output',type=Path);ap.add_argument('--high-quality',action='store_true');a=ap.parse_args();assert not a.output.exists(),'Preserve completed replay';a.output.mkdir(parents=True);cv2.setNumThreads(1);print(replay(a.inputs,json.loads(a.record.read_text()),a.output,a.high_quality))
