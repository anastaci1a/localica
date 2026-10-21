"""Reproduce a frozen crowned-toolbar recipe from private inputs, without fitting.

python tensor_replay.py INPUTS RECIPE.json OUTPUT --scale 4
Inputs are source.png, target.png and the declared solid SVG glyphs. Original
fonts/screenshots stay private; source and vector hashes are verified here.
"""
from pathlib import Path
import argparse, hashlib, json
import cv2
import numpy as np
from PIL import Image
from crowned_tensor import render, gaussian_coverage_shadow
from reproduce import scores
from vector_foreground import _coverage
from publish import pair


def composite(source,case,p,folder,scale=1):
    material,alpha=render(source,case,p,scale=scale)
    x0,y0,x1,y1=case['crop'];H,W=alpha.shape
    yy,xx=np.mgrid[:H,:W].astype(np.float32)
    X=x0-.5+(xx+.5)/scale;Y=y0-.5+(yy+.5)/scale
    background=cv2.remap(source,X,Y,cv2.INTER_LINEAR)
    shadow=gaussian_coverage_shadow(case,p,scale)
    out=material*alpha[...,None]+background*(1-shadow[...,None])*(1-alpha[...,None])
    for layer in p.get('foreground_layers',[]):
        name=layer['asset'];assert Path(name).name==name
        raw=(folder/name).read_bytes()
        assert hashlib.sha256(raw).hexdigest()==layer['sha256'],'Changed vector glyph'
        coverage=_coverage(raw,tuple(case['crop']),tuple(layer['placement']),scale)
        color=np.asarray(layer['color']);assert color.shape==(3,) and np.all((color>=0)&(color<=1))
        out=out*(1-coverage[...,None])+color*coverage[...,None]
    return out,material,alpha


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ['inputs','recipe','out']:ap.add_argument(name,type=Path)
    ap.add_argument('--scale',type=int,default=4);args=ap.parse_args()
    assert 1<=args.scale<=4
    cv2.setNumThreads(1);p=json.loads(args.recipe.read_text());case=p['case']
    for name,digest in p['source_sha256'].items():
        assert Path(name).name==name and hashlib.sha256((args.inputs/name).read_bytes()).hexdigest()==digest,'Changed frozen input'
    source=np.asarray(Image.open(args.inputs/'source.png'),float)/255
    target=np.asarray(Image.open(args.inputs/'target.png'),np.float32)/255
    args.out.mkdir(parents=True,exist_ok=True);x0,y0,x1,y1=case['crop']
    out,material,alpha=composite(source,case,p,args.inputs)
    report=dict(glass=scores(material,target[y0:y1,x0:x1],case),foreground={},scale=args.scale,
                limits='Conditional glass masks remain unchanged; complete composition is separate. Clean estimated source and generated shadow only. No reference glyph or exterior copied.')
    for layer in p.get('foreground_layers',[]):
        a,b,c,d=layer['box'];error=out[b-y0:d-y0,a-x0:c-x0]-target[b:d,a:c]
        report['foreground'][layer['name']]=dict(rmse=float(np.sqrt(np.mean(error**2))*255),pixels=int(error.shape[0]*error.shape[1]))
    if args.scale!=1:out,_,_=composite(source,case,p,args.inputs,args.scale)
    rgb=np.uint8(np.rint(np.clip(out,0,1)*255));Image.fromarray(rgb).save(args.out/'render.png')
    original=cv2.resize(np.uint8(np.rint(target[y0:y1,x0:x1]*255)),(rgb.shape[1],rgb.shape[0]),interpolation=cv2.INTER_LANCZOS4)
    pair(original,rgb,('Apple source',f'Reconstruction ({args.scale}×)'),font_size=16*args.scale).save(args.out/'comparison.png')
    (args.out/'scores.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)


if __name__=='__main__':main()
