"""Replay a uniform foreground style on a frozen material buffer; no fitting.
python foreground_style.py INPUTS BODY.npz RECORD.json NEW_OUTPUT
The body can first be reproduced with neutral_rim.py and the baseline record.
Original vectors supply alpha; this helper never derives it from target pixels.
"""
from pathlib import Path
import argparse,json,hashlib
import numpy as np,cv2
from extended_fit import Study
from vector_revision import composite
from publish import pair
from preview_size import dimensions


def apply(study,p,material,styles,scale=1):
    original=study.case
    try:
        study.case={**original,'foreground_styles':styles}
        return composite(study,p,material,scale)
    finally:study.case=original


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ['inputs','body','record','output']:ap.add_argument(name,type=Path)
    a=ap.parse_args();assert not a.output.exists();a.output.mkdir(parents=True);cv2.setNumThreads(1)
    s=Study(a.inputs);r=json.loads(a.record.read_text());M=np.load(a.body)['material']
    assert hashlib.sha256(json.dumps(s.case,sort_keys=True).encode()).hexdigest() in r['case_sha256'].values()
    for name,digest in s.case['source_sha256'].items():assert hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()==digest
    for glyphs in r['vector_sha256'].values():
        for name,digest in glyphs.items():assert hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()==digest
    assert hashlib.sha256(M.tobytes()).hexdigest()==r['base_material_sha256']
    p=r.get('preset',r.get('parameters'));C=apply(s,p,M,r['foreground_styles'])
    np.savez_compressed(a.output/'arrays.npz',material=M,complete=C)
    W,H=dimensions(C.shape[1],C.shape[0],large=r.get('large',False));images=[cv2.resize(np.uint8(np.rint(np.clip(x,0,1)*255)),(W,H),interpolation=cv2.INTER_LANCZOS4)for x in [s.target_crop,C]]
    pair(*images,('Apple','Render'),font_size=18).save(a.output/'comparison.png')
