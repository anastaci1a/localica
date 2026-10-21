"""Replay playback refinements over hashed private inputs, without fitting.

python playback_replay.py INPUTS RECORD.json OUTPUT --scale 4
INPUTS contains media-source.png and media-target.png. The exterior uses the
registered clean source, with no reference shadow or reference icon colors.
"""
from pathlib import Path
import argparse,hashlib,json
import cv2,numpy as np
from PIL import Image
from settled import mapping,lighting,render,icon_color,ROOT
from reproduce import CASES,scores
from publish import pair


def high_resolution(source,case,p,scale):
    if scale==1:return render(source,case,p)
    L=min(case['half']);a,b,c,d=case['crop'];W=(c-a)*scale;H=(d-b)*scale
    texture=cv2.GaussianBlur(source,(0,0),p['source_sigma']*L) if p['source_sigma'] else source
    result=np.empty((H,W,3),np.float32)
    from render import shade
    for row in range(0,H,24*scale):
        bottom=min(row+24*scale,H)
        yy,xx=np.mgrid[b+row/scale:b+bottom/scale:1/(4*scale),a:c:1/(4*scale)]
        x=(xx-.5+.5/(4*scale)-case['center'][0])/L;y=(yy-.5+.5/(4*scale)-case['center'][1])/L
        X,Y=mapping(x,y,p);C=cv2.remap(texture,(X*L+case['center'][0]).astype(np.float32),(Y*L+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR)
        C=shade(C,lighting(x,y,p),np.asarray(p['material']))
        result[row:bottom]=C.reshape(bottom-row,4,W,4,3).mean((1,3))
    return cv2.GaussianBlur(result,(0,0),p['post_sigma']*L*scale) if p.get('post_sigma') else result


def composite(source,case,p,material,scale):
    L=min(case['half']);a,b,c,d=case['crop'];H,W=material.shape[:2]
    yy,xx=np.mgrid[:H,:W].astype(np.float32);X=a-.5+(xx+.5)/scale;Y=b-.5+(yy+.5)/scale
    background=cv2.remap(source,X,Y,cv2.INTER_LINEAR)
    alpha=np.zeros((H,W),float)
    for iy in range(4):
        for ix in range(4):alpha+=((X+(ix-1.5)/(4*scale)-case['center'][0])**2+(Y+(iy-1.5)/(4*scale)-case['center'][1])**2<=L*L)/16
    whole=material*alpha[...,None]+background*(1-alpha[...,None])
    mask=np.asarray(Image.open(ROOT/p['icon']['coverage']),float)/255
    ia,ib,ic,id=[int(round(v*L+case['center'][i%2])) for i,v in enumerate(p['icon']['box_over_L'])]
    color=icon_color(material,p['icon'],L*scale)[(ib-b)*scale:(id-b)*scale,(ia-a)*scale:(ic-a)*scale]
    if scale!=1:mask=cv2.resize(mask,((ic-ia)*scale,(id-ib)*scale),interpolation=cv2.INTER_LINEAR)
    region=whole[(ib-b)*scale:(id-b)*scale,(ia-a)*scale:(ic-a)*scale]
    region[:]=region*(1-mask[...,None])+color*mask[...,None]
    return whole


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ['inputs','record','out']:ap.add_argument(name,type=Path)
    ap.add_argument('--scale',type=int,choices=[1,2,4],default=4);args=ap.parse_args();cv2.setNumThreads(1)
    record=json.loads(args.record.read_text());p=record['recipe'];case=CASES['media']
    for name,digest in record['input_sha256'].items():assert hashlib.sha256((args.inputs/name).read_bytes()).hexdigest()==digest
    source=np.asarray(Image.open(args.inputs/'media-source.png'),float)/255;target=np.asarray(Image.open(args.inputs/'media-target.png'),np.float32)/255
    args.out.mkdir(parents=True,exist_ok=True);material=high_resolution(source,case,p,args.scale);full=composite(source,case,p,material,args.scale)
    rgb=np.uint8(np.rint(np.clip(full,0,1)*255));Image.fromarray(rgb).save(args.out/'render.png');a,b,c,d=case['crop']
    reference=cv2.resize(np.uint8(np.rint(target[b:d,a:c]*255)),(rgb.shape[1],rgb.shape[0]),interpolation=cv2.INTER_LANCZOS4)
    pair(reference,rgb,('Apple source',f'Reconstruction ({args.scale}x)'),font_size=16*args.scale).save(args.out/'comparison.png')
    np.save(args.out/'material.npy',material)


if __name__=='__main__':main()
