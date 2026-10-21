"""Replay native frozen Safari inputs, without fitting or source adoption.

python safari_transfer.py --search-input /private/proposed-a --plus-input /private/b-proposed --output /private/new-output
Input PNGs/cases stay private; public records fix fingerprints and vector paths.
"""
import argparse,hashlib,json
from pathlib import Path
import cv2,numpy as np
from PIL import Image
from extended_fit import Study
from vector_revision import composite
from publish import pair

def replay(folder,record,output):
    study=Study(folder)
    assert len(record['case_sha256'])==1
    fingerprint=hashlib.sha256(json.dumps(study.case,sort_keys=True).encode()).hexdigest()
    assert fingerprint==next(iter(record['case_sha256'].values())),'Frozen case mismatch'
    for glyphs in record['vector_sha256'].values():
        for name,digest in glyphs.items():
            assert Path(name).name==name
            assert hashlib.sha256((folder/name).read_bytes()).hexdigest()==digest
    p=record['parameters'];material=study.render(p,np.asarray(record['material']))
    visible=composite(study,p,material)
    np.savez_compressed(output/'native-arrays.npz',material=material,complete=visible,alpha=study.shape_coverage(p))
    scores=study.scores(material);scores['E']=float(np.sqrt(scores['loss']))
    (output/'scores.json').write_text(json.dumps(scores,indent=2)+'\n')
    a=np.uint8(np.rint(np.clip(study.target_crop,0,1)*255));b=np.uint8(np.rint(np.clip(visible,0,1)*255))
    pair(a,b,('Apple source','Frozen unfitted transfer'),font_size=24).save(output/'native-comparison.png')
    return scores

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--search-input',type=Path,required=True);ap.add_argument('--plus-input',type=Path,required=True);ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--record',type=Path,default=Path(__file__).parent/'studies/safari-circles/baseline-transfer.json')
    args=ap.parse_args();assert not args.output.exists(),'Preserve previous replay';args.output.mkdir(parents=True)
    cv2.setNumThreads(1);record=json.loads(args.record.read_text())
    for key,folder in [('search',args.search_input),('plus',args.plus_input)]:
        output=args.output/key;output.mkdir();print(key,replay(folder,record[key],output))
