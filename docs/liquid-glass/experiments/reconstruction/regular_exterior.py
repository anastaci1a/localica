"""Replay legacy light/dark bodies with the declared shared generated exterior.
No fitting, source construction, compose(), or contact_context().
"""
import argparse,hashlib,json
from pathlib import Path
import cv2,numpy as np
from PIL import Image,ImageDraw
from reproduce import CASES,points,scores
from render import case_geometry
from settled import RECIPES,render

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def exterior(case,source,material,record):
 """Normalized empirical distance falloffs, independent of target pixels."""
 x0,y0,x1,y1=case['crop'];L=min(case['half']);_,_,x,y=points(case)
 # Native coordinates/distances are float32; parameters and RGB compositing float64.
 assert x.dtype==y.dtype==np.float32
 coverage=np.zeros(x.shape,np.float64)
 for dy in [-.375,-.125,.125,.375]:
  for dx in [-.375,-.125,.125,.375]:coverage+=(case_geometry(case,x+dx,y+dy)[0]<=0)/16
 p=record['parameters'];offsets=record['offsets_L'];alphas=[]
 for key in ['K','H']:
  ox,oy=offsets[key];sd=case_geometry(case,x-ox*L,y-oy*L)[0]
  d=np.maximum(sd,0)/L
  alphas.append(np.float64(p['opacity'+key])*np.exp(-.5*(d/np.float64(p['sigma'+key+'_L']))**2))
 K,H=alphas;B=source[y0:y1,x0:x1];background=B*(1-K[...,None]);background+=H[...,None]*(1-background)
 complete=material*coverage[...,None]+background*(1-coverage[...,None])
 sd=case_geometry(case,x,y)[0]
 return complete,coverage,sd

def pair(target,complete,width):
 ims=[Image.fromarray(np.rint(np.clip(a,0,1)*255).astype(np.uint8)) for a in [target,complete]]
 h=round(ims[0].height*width/ims[0].width);out=Image.new('RGB',(2*width,h+64),'white');d=ImageDraw.Draw(out)
 for i,(im,label) in enumerate(zip(ims,['Apple reference','Generated exterior / unchanged body'])):
  d.text((i*width+12,24),label,fill='black');out.paste(im.resize((width,h),Image.Resampling.LANCZOS),(i*width,64))
 return out

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--recipe',type=Path,required=True);ap.add_argument('--material-cache',type=Path);ap.add_argument('--expected',type=Path);a=ap.parse_args();cv2.setNumThreads(1)
 a.out.mkdir(parents=True,exist_ok=False);record=json.loads(a.recipe.read_text());verification=[]
 for name in ['regular-light','regular-dark']:
  c=CASES[name];x0,y0,x1,y1=c['crop'];src=a.inputs/f'{name}-source.png';tgt=a.inputs/f'{name}-target.png';frozen=record['inputs'][name]
  assert sha(src)==frozen['source_sha256'] and sha(tgt)==frozen['target_sha256']
  source=np.asarray(Image.open(src).convert('RGB')).astype(np.float64)/255;target=(np.asarray(Image.open(tgt).convert('RGB')).astype(np.float32)/255)[y0:y1,x0:x1]
  if a.material_cache:assert sha(a.material_cache/f'{name}-native-material.npy')==frozen['material_sha256']
  material=np.load(a.material_cache/f'{name}-native-material.npy') if a.material_cache else render(source,c,record['body_recipes'][name],samples=4)
  complete,coverage,sd=exterior(c,source,material,record)
  native=scores(material,target,c);candidate=scores(complete.astype(np.float32),target,c);assert native==candidate and native==record['original_scores'][name]
  if a.expected:assert np.array_equal(complete,np.load(a.expected/f'{name}-complete.npy'))
  mse=lambda m:float(np.sqrt(np.mean(((complete-target)[m]*255)**2)))
  guard=(sd>0)&(sd<.35*min(c['half']));full=sd>0
  np.save(a.out/f'{name}-complete.npy',complete)
  for suffix,width in [('',720),('-full',1080)]:pair(target,complete,width).save(a.out/f'match-{name}{suffix}.png')
  verification.append(dict(theme=name,input_sha256={'source':sha(src),'target':sha(tgt)},body_scores=native,complete_scores=candidate,all_original_metrics_exact=True,supplemental_exterior_rmse=mse(guard),full_visible_exterior_rmse=mse(full),supplemental_pixels=int(guard.sum()),full_visible_pixels=int(full.sum()),complete_sha256=sha(a.out/f'{name}-complete.npy'),comparison_caption_height=64,comparison_side_width=720,full_comparison_side_width=1080,material_cache_used=bool(a.material_cache)))
 (a.out/'replay-verification.json').write_text(json.dumps(dict(scope='Exterior-only reconstruction; Eoptical tie',observations=verification,source_adoption=False,estimated_source_limits=record['limitations']),indent=2)+'\n')
if __name__=='__main__':main()
