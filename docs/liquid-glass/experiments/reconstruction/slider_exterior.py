"""Replay the frozen slider body with a generated neutral contact and tinted halo.
The target is used only for scoring/comparisons, never to render the exterior.
"""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from reproduce import points,scores
from render import case_geometry
from shapes import oval_field
from settled import render

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def generated(case,source,body,record):
 _,_,x,y=points(case);L=min(case['half']);sd=case_geometry(case,x,y)[0]
 g=record['contact_geometry'];sx=g['width_ratio'];sy=g['height_ratio'];oy=g['offset_y_over_L']*L
 coverage=np.zeros(x.shape);contact=np.zeros(x.shape)
 for dy in [-.375,-.125,.125,.375]:
  for dx in [-.375,-.125,.125,.375]:
   # Membership of the same polynomial oval; no nearest-point solve needed.
   coverage+=(oval_field(np.abs(x+dx).astype(float),np.abs(y+dy).astype(float),case['half'],case['oval'])[0]<=1)/16
   contact+=(oval_field(np.abs((x+dx)/sx).astype(float),np.abs((y+dy-oy)/sy).astype(float),case['half'],case['oval'])[0]<=1)/16
 p=record['parameters'];h=p['halo_opacity']*np.exp(-.5*(np.maximum(sd,0)/(p['halo_sigma_L']*L))**2)
 x0,y0,x1,y1=case['crop'];B=source[y0:y1,x0:x1];underlay=B*(1-p['contact_opacity']*contact[...,None])
 underlay=(1-h[...,None])*underlay+h[...,None]*(p['halo_tint_scale']*np.array(p['accent_rgb']))
 return body*coverage[...,None]+underlay*(1-coverage[...,None]),coverage,sd

def pair(target,result,width):
 height=round(target.shape[0]*width/target.shape[1]);out=Image.new('RGB',(2*width,height+64),'white');draw=ImageDraw.Draw(out)
 for i,(a,label) in enumerate([(target,'Apple reference'),(result,'Reconstruction')]):
  draw.text((i*width+12,24),label,fill='black');im=Image.fromarray(np.rint(np.clip(a,0,1)*255).astype(np.uint8));out.paste(im.resize((width,height),Image.Resampling.LANCZOS),(i*width,64))
 return out

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--recipe',type=Path,required=True);ap.add_argument('--material-cache',type=Path);ap.add_argument('--expected',type=Path);a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=False)
 record=json.loads(a.recipe.read_text());case=record['case'];frozen=record['inputs'];sp=a.inputs/'slider-source.png';tp=a.inputs/'slider-target.png';assert sha(sp)==frozen['slider-source.png'] and sha(tp)==frozen['slider-target.png']
 source=np.asarray(Image.open(sp).convert('RGB')).astype(float)/255;target=np.asarray(Image.open(tp).convert('RGB')).astype(np.float32)/255;x0,y0,x1,y1=case['crop'];T=target[y0:y1,x0:x1]
 if a.material_cache:assert sha(a.material_cache)==record['native_material_sha256']
 body=np.load(a.material_cache) if a.material_cache else render(source,case,record['body_recipe'],samples=4)
 result,coverage,sd=generated(case,source,body,record);original=scores(body,T,case);assert original==record['original_scores'] and scores(result.astype(np.float32),T,case)==original
 if a.expected:assert np.array_equal(result,np.load(a.expected))
 for suffix,width in [('',720),('-full',1080)]:pair(T,result,width).save(a.out/f'match-slider{suffix}.png')
 np.save(a.out/'complete.npy',result);near=(sd>0)&(sd<.35*min(case['half']));exterior=sd>0
 report=dict(original_scores_exact=True,near_exterior_rmse=float(np.sqrt(np.mean(((result-T)[near]*255)**2))),full_visible_exterior_rmse=float(np.sqrt(np.mean(((result-T)[exterior]*255)**2))),limitations=record['limits'])
 (a.out/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
