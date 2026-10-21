"""Replay a neutral affine material with the frozen settled map; no fitting.
python neutral_affine.py INPUTS RECORD NEW_OUTPUT
Native scores and iteration preview only; no HQ or physical-optics claim.
"""
from pathlib import Path
import argparse,json,hashlib
import numpy as np,cv2
from extended_fit import Study
from settled import render
from vector_revision import composite
from neutral_material import affine_coefficients
from preview_size import dimensions
from publish import pair

def replay(inputs,record,output):
 s=Study(inputs);assert hashlib.sha256(json.dumps(s.case,sort_keys=True).encode()).hexdigest()==next(iter(record['case_sha256'].values()))
 for glyphs in record['vector_sha256'].values():
  for name,digest in glyphs.items():assert Path(name).name==name and hashlib.sha256((inputs/name).read_bytes()).hexdigest()==digest
 p=record['preset'];assert np.array_equal(affine_coefficients(record['g'],record['b']),p['material']);assert 'icon' not in p
 M=render(s.source,s.case,p,samples=record['samples']);C=composite(s,p,M);q=s.scores(M);q['E']=float(np.sqrt(q['loss']));np.savez_compressed(output/'native-arrays.npz',material=M,complete=C);(output/'scores.json').write_text(json.dumps(q,indent=2)+'\n')
 x,y,z,w=s.crop;W,H=dimensions(z-x,w-y,large=record.get('large',False));images=[cv2.resize(np.uint8(np.rint(np.clip(a,0,1)*255)),(W,H),interpolation=cv2.INTER_LANCZOS4)for a in [s.target_crop,C]];pair(*images,('Apple source','Neutral material reconstruction'),font_size=32).save(output/'comparison.png')
 return q

if __name__=='__main__':
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('record',type=Path);ap.add_argument('output',type=Path);a=ap.parse_args();assert not a.output.exists();a.output.mkdir(parents=True);cv2.setNumThreads(1);print(replay(a.inputs,json.loads(a.record.read_text()),a.output))
