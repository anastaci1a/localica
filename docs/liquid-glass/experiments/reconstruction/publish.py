"""Compose attributed comparisons from local fit outputs; no network/downloads.

python publish.py /private/fits-final ../../assets
The copied exterior is context, not evidence of a recreated shadow. Identical
reference symbols are composited on both sides; all scores exclude their boxes.
"""
from pathlib import Path
import argparse,json
from functools import lru_cache
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from reproduce import CASES,points,masks,frame
from render import case_geometry

FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
TITLE={'media':'Playback','regular':'Archive / plus','slider':'Active slider','regular-light':'Regular light','regular-dark':'Regular dark'}

def read(path):return np.array(Image.open(path).convert('RGB'))


@lru_cache(maxsize=8)
def coverage(name):
    case=CASES[name];x0,y0,x1,y1=case['crop'];result=np.zeros((y1-y0,x1-x0),float)
    for top in range(y0,y1,24):
        bottom=min(top+24,y1);yy,xx=np.mgrid[top:bottom:.25,x0:x1:.25]
        xx=xx-.375-case['center'][0];yy=yy-.375-case['center'][1]
        sd,*_=case_geometry(case,xx,yy)
        result[top-y0:bottom-y0]=(sd<=0).reshape(bottom-top,4,x1-x0,4).mean(axis=(1,3))
    return result[...,None]


@lru_cache(maxsize=8)
def contact_context(name,folder):
    """Remove the reference's original rim from the reconstruction side.

    A 10px contact band uses the recovered backdrop and a low-frequency exterior
    residual estimate. Farther context remains shared. This band is unscored.
    """
    case=CASES[name];x0,y0,x1,y1=case['crop']
    target=read(Path(folder)/f'{name}-target.png')[y0:y1,x0:x1].astype(float)
    source=read(Path(folder)/f'{name}-source.png')[y0:y1,x0:x1].astype(float)
    _,_,x,y=points(case);sd,*_=case_geometry(case,x,y)
    outside=(sd>10).astype(float)
    weight=cv2.GaussianBlur(outside,(0,0),10)
    residual=cv2.GaussianBlur((target-source)*outside[...,None],(0,0),10)/np.maximum(weight,.00001)[...,None]
    clean=np.clip(source+residual,0,255)
    t=np.clip(sd/10,0,1);t=t**3*(t*(6*t-15)+10)
    return clean*(1-t[...,None])+target*t[...,None]

def glyph(name,target):
    """Tight symbol masks. No font/symbol substitution; no score contribution."""
    mask=np.zeros(target.shape[:2],np.uint8)
    for x0,y0,x1,y1 in CASES[name]['foreground']:
        region=target[y0:y1+1,x0:x1+1]
        selected=((region[:,:,0]>220)&(region[:,:,1]>200)&(region[:,:,2]>160)) if name=='media' else (region.max(2)<90)
        if name=='media':
            count,labels,stats,centers=cv2.connectedComponentsWithStats(selected.astype(np.uint8))
            index=1+np.argmax(stats[1:,cv2.CC_STAT_AREA]);component=np.uint8(labels==index)
        else:component=selected.astype(np.uint8)
        # Retain the reference symbol's native antialias fringe. Its containing
        # rectangle, including this one-pixel margin, is unscored.
        component=cv2.dilate(component,np.ones((3,3),np.uint8))
        mask[y0:y1+1,x0:x1+1]=component
    return mask.astype(float)

def compose(name,case,target,material,folder=None):
    alpha_shape=coverage(name)
    x0,y0,x1,y1=case['crop'];out=target.copy().astype(float)
    if folder is not None:out[y0:y1,x0:x1]=contact_context(name,str(folder))
    out[y0:y1,x0:x1]=material*alpha_shape+out[y0:y1,x0:x1]*(1-alpha_shape)
    alpha=glyph(name,target)[...,None]
    out=out*(1-alpha)+target*alpha
    return np.uint8(np.clip(out,0,255))

def pair(a,b,labels,footer='',font_size=20):
    a,b=Image.fromarray(a),Image.fromarray(b);w=a.width;h=max(a.height,b.height)
    font_size=max(font_size,round(w*.03)) if not footer else font_size
    bar=max(36,font_size*2);foot=bar if footer else 0
    out=Image.new('RGB',(w+b.width+2,h+bar+foot),'#14171e');d=ImageDraw.Draw(out)
    font=ImageFont.truetype(FONT,font_size)
    d.text((14,8),labels[0],font=font,fill='#f0f2f7');d.text((w+16,8),labels[1],font=font,fill='#f0f2f7')
    out.paste(a,(0,bar));out.paste(b,(w+2,bar))
    if footer:d.text((14,h+bar+8),footer,font=font,fill='#c2c9d7')
    return out

def main():
    ap=argparse.ArgumentParser();ap.add_argument('fits',type=Path);ap.add_argument('assets',type=Path);ap.add_argument('--sources',type=Path);args=ap.parse_args();args.assets.mkdir(parents=True,exist_ok=True)
    reports={}
    for name,case in CASES.items():
        report=json.loads((args.fits/f'{name}-report.json').read_text())
        report['rendering']={'source_sampling':'native bilinear','final_subpixels_per_axis':4,'material_coefficients':23,'color_space':'encoded RGB','estimated_context_band_px':10,'shared_foreground':'tight reference-symbol masks, excluded from scores'}
        # Training objective selects the model; never select using held blocks.
        candidates=[m for m in ['field','cap','bounded','spline'] if m in report['models']]
        winner=min(candidates,key=lambda m:report['models'][m]['fit_loss']) if candidates else 'default'
        report['displayed_model']=winner;reports[name]=report
        target=read(args.fits/f'{name}-target.png');material=read(args.fits/f'{name}-{winner}.png')
        full=compose(name,case,target,material,args.fits)
        x0,y0,x1,y1=case['crop'];apple=target[y0:y1,x0:x1];ours=full[y0:y1,x0:x1]
        pair(apple,ours,('Apple · native HD crop',f'Reconstruction · {winner} model'),font_size=16).save(args.assets/f'match-{name}.png',optimize=True)
        if name=='regular':
            pair(target[369:435,650:1270],full[369:435,650:1270],('Apple · upper rim','Reconstruction · upper rim'),font_size=16).save(args.assets/'match-regular-rim.png',optimize=True)
        pair(target,full,('Apple · 1920 × 1080',f'Reconstruction · {winner} material in original context'),'Symbols / distant context retained; contact band estimated. Only glass material scored.',24).save(args.assets/f'match-{name}-full.png',optimize=True)
        if 'bounded' in report['models']:
            default=compose(name,case,target,read(args.fits/f'{name}-default.png'),args.fits)[y0:y1,x0:x1]
            pair(apple,default,('Apple · native HD crop','Starting field optics · fitted material'),font_size=16).save(args.assets/f'match-{name}-starting.png',optimize=True)
            bounded=compose(name,case,target,read(args.fits/f'{name}-bounded.png'),args.fits)[y0:y1,x0:x1]
            pair(apple,bounded,('Apple · native HD crop','Constrained field · local bounds'),font_size=16).save(args.assets/f'match-{name}-bounded.png',optimize=True)
        xs,ys,x,y=points(case);sd,train,held,valid=masks(case,xs,ys,x,y)
        error=np.abs(material.astype(float)-apple).mean(2)
        # One shared 0..48/255 MAE color scale for every case.
        heat=cv2.applyColorMap(np.uint8(np.clip(error/48,0,1)*255),cv2.COLORMAP_INFERNO)[:,:,::-1];heat[~valid]=[20,23,30]
        mask=np.full_like(heat,[20,23,30]);mask[train]=[97,159,217];mask[held]=[232,184,86]
        pair(heat,mask,('RGB error · 0 black → 48 yellow','Score mask · blue fit / gold withheld'),font_size=16).save(args.assets/f'match-{name}-error.png',optimize=True)
        source=read(args.fits/f'{name}-source.png')[y0:y1,x0:x1]
        pair(apple,source,('Apple · native HD crop','Reconstructed input · before glass'),font_size=16).save(args.assets/f'match-{name}-input.png',optimize=True)
    (Path(__file__).parent/'results.json').write_text(json.dumps(reports,indent=2)+'\n')
    doc=Path(__file__).parent.parent.parent/'reconstruction.md'
    import re
    rows=['| Scene | No refraction RMSE | Starting optics RMSE | Fitted RMSE | Rim RMSE | Withheld RMSE | SSIM | Source σ |','| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for name,r in reports.items():
        f=r['models'][r['displayed_model']];s=f['scores']
        rows.append(f"| {TITLE[name]} | {r['models']['identity']['scores']['all_glass']['rmse']:.2f} | {r['models']['default']['scores']['all_glass']['rmse']:.2f} | {s['all_glass']['rmse']:.2f} | {s['rim']['rmse']:.2f} | {s['held_blocks']['rmse']:.2f} | {s['ssim']:.4f} | {f['sigma']:g} |")
    text=doc.read_text();block='<!-- SCORE_TABLE -->\n\n'+'\n'.join(rows)+'\n\n<!-- /SCORE_TABLE -->'
    text=re.sub(r'<!-- SCORE_TABLE -->.*?(?:<!-- /SCORE_TABLE -->|(?=\nErrors use))',lambda _:block,text,flags=re.S)
    doc.write_text(text)
    if args.sources:
        for seconds,name in [(865,'reference-clear-media.png'),(850,'reference-regular-contexts.png')]:
            image,_=frame(args.sources,'219',seconds)
            Image.fromarray(np.uint8(np.rint(image*255))).save(args.assets/name,optimize=True)
    print('Published comparison images and complete parameter/score records')

if __name__=='__main__':main()
