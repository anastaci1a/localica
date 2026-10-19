"""Compose attributed comparisons from local fit outputs; no network/downloads.

python publish.py /private/fits-final ../../assets
The copied exterior is context, not evidence of a recreated shadow. Foreground
symbols are redrawn, not cut from the target. All score masks exclude symbols.
"""
from pathlib import Path
import argparse,json
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from reproduce import CASES,points,masks
from render import geometry

FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
TITLE={'media':'Clear playback control','regular':'Blurred toolbar','slider':'Active slider'}

def read(path):return np.array(Image.open(path).convert('RGB'))

def glyph(name,shape):
    """Approximate vector glyphs, supersampled 4x; explicitly unscored."""
    s=4;mask=Image.new('L',(shape[1]*s,shape[0]*s));d=ImageDraw.Draw(mask)
    def line(p,width):d.line([(int(x*s),int(y*s)) for x,y in p],fill=255,width=width*s,joint='curve')
    def circle(x,y,r):d.ellipse(((x-r)*s,(y-r)*s,(x+r)*s,(y+r)*s),fill=255)
    def rect(box,r,width):d.rounded_rectangle(tuple(int(v*s) for v in box),radius=r*s,outline=255,width=width*s)
    if name=='media':
        p=[(904,458),(1055,545),(904,632)]
        d.polygon([(x*s,y*s) for x,y in p],fill=255);line(p+[p[0]],40)
        for x,y in p:circle(x,y,20)
    elif name=='regular':
        rect((716,477,856,511),7,14);rect((729,505,844,605),10,14)
        line([(760,541),(812,541)],13)
        line([(1091,544),(1196,544)],18);line([(1143,492),(1143,596)],18)
        for x,y in [(1091,544),(1196,544),(1143,492),(1143,596)]:circle(x,y,9)
    return np.asarray(mask.resize((shape[1],shape[0]),Image.Resampling.LANCZOS))/255

def compose(name,case,target,material):
    xs,ys,x,y=points(case);sd,*_=geometry(x,y,case['half'],case['radius'])
    # Fixed 1px coverage, not a fitted transition that can copy the target rim.
    coverage=np.clip(.5-sd,0,1)[...,None]
    x0,y0,x1,y1=case['crop'];out=target.copy().astype(float)
    out[y0:y1,x0:x1]=material*coverage+out[y0:y1,x0:x1]*(1-coverage)
    alpha=glyph(name,target.shape)[...,None]
    color=np.array([255,250,239]) if name=='media' else np.array([57,57,58])
    out=out*(1-alpha)+color*alpha
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
    ap=argparse.ArgumentParser();ap.add_argument('fits',type=Path);ap.add_argument('assets',type=Path);args=ap.parse_args();args.assets.mkdir(parents=True,exist_ok=True)
    reports={}
    for name,case in CASES.items():
        report=json.loads((args.fits/f'{name}-report.json').read_text())
        # Training objective selects the model; never select using held blocks.
        winner=min(['field','cap','bounded'],key=lambda m:report['models'][m]['fit_loss'])
        report['displayed_model']=winner;reports[name]=report
        target=read(args.fits/f'{name}-target.png');material=read(args.fits/f'{name}-{winner}.png')
        full=compose(name,case,target,material)
        x0,y0,x1,y1=case['crop'];apple=target[y0:y1,x0:x1];ours=full[y0:y1,x0:x1]
        pair(apple,ours,('Apple · native HD crop',f'Reconstruction · {winner} model'),font_size=16).save(args.assets/f'match-{name}.png',optimize=True)
        pair(target,full,('Apple · 1920 × 1080',f'Reconstruction · {winner} material in original context'),'Exterior retained; foreground redrawn. Only the masked glass interior is scored.',24).save(args.assets/f'match-{name}-full.png',optimize=True)
        default=compose(name,case,target,read(args.fits/f'{name}-default.png'))[y0:y1,x0:x1]
        pair(apple,default,('Apple · native HD crop','Starting field optics · fitted material'),font_size=16).save(args.assets/f'match-{name}-starting.png',optimize=True)
        bounded=compose(name,case,target,read(args.fits/f'{name}-bounded.png'))[y0:y1,x0:x1]
        pair(apple,bounded,('Apple · native HD crop','Constrained field · local nonfolding'),font_size=16).save(args.assets/f'match-{name}-bounded.png',optimize=True)
        xs,ys,x,y=points(case);sd,train,held,valid=masks(case,xs,ys,x,y)
        error=np.abs(material.astype(float)-apple).mean(2)
        # One shared 0..48/255 MAE color scale for every case.
        heat=cv2.applyColorMap(np.uint8(np.clip(error/48,0,1)*255),cv2.COLORMAP_INFERNO)[:,:,::-1];heat[~valid]=[20,23,30]
        mask=np.full_like(heat,[20,23,30]);mask[train]=[97,159,217];mask[held]=[232,184,86]
        pair(heat,mask,('RGB error · 0 black → 48 yellow','Score mask · blue fit / gold withheld'),font_size=16).save(args.assets/f'match-{name}-error.png',optimize=True)
        source=read(args.fits/f'{name}-source.png')[y0:y1,x0:x1]
        pair(apple,source,('Apple · native HD crop','Reconstructed input · before glass'),font_size=16).save(args.assets/f'match-{name}-input.png',optimize=True)
    (Path(__file__).parent/'results.json').write_text(json.dumps(reports,indent=2)+'\n')
    print('Published 18 comparison images and complete parameter/score records')

if __name__=='__main__':main()
