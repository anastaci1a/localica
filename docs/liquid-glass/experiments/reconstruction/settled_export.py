"""Export a frozen radial-tail neutral-affine circle, with source-support checks.
python settled_export.py INPUTS RECORD NEW_OUTPUT --native-reference NATIVE.npz
Native reference must match the record; this export performs no fitting.
"""
import os,sys,json,hashlib,subprocess,time
from pathlib import Path
import numpy as np,cv2
from PIL import Image,ImageDraw,ImageSequence
os.umask(0o002);cv2.setNumThreads(1)
import argparse
from publish import pair
from neutral_material import affine_coefficients
from video_color import ENCODING_COLOR_ARGS,rgb_digest
from motion import set_gif_timing
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('record',type=Path);ap.add_argument('output',type=Path);ap.add_argument('--native-reference',type=Path,required=True);ap.add_argument('--check-native-only',action='store_true');ap.add_argument('--rim-record',type=Path);ap.add_argument('--travel',type=float,default=20);ap.add_argument('--trim',type=int,default=0);a=ap.parse_args();assert np.isfinite(a.travel) and a.travel>=0 and a.trim>=0
D=a.output;assert not D.exists(),'Use a new export directory';D.mkdir(parents=True);(D/'frames').mkdir()
import settled
from extended_fit import Study
from vector_revision import composite
s=Study(a.inputs);rec=json.loads(a.record.read_text());p=rec['preset'];old=np.load(a.native_reference);
assert hashlib.sha256(json.dumps(s.case,sort_keys=True).encode()).hexdigest()==next(iter(rec['case_sha256'].values()))
for glyphs in rec['vector_sha256'].values():
 for name,d in glyphs.items():assert hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()==d
assert np.array_equal(affine_coefficients(rec['g'],rec['b']),p['material']) and p['model']=='radial-tail'
assert not p.get('blur_transition') and 'icon' not in p
rim=json.loads(a.rim_record.read_text()) if a.rim_record else None
if rim:
 assert rim['amplitude']>=0 and rim['width']>0
 from neutral_rim import field as rim_field
clip_audit={'minimum':1.,'maximum':0.,'clipped_channels':0}
L=min(s.case['half']);x0,y0,x1,y1=s.crop
sigma=p['source_sigma']*L;blur=cv2.GaussianBlur(s.source,(0,0),sigma) if sigma else s.source

def save(n,v):(D/n).write_text(json.dumps(v,indent=2)+'\n')
def digest(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def kernel_radius(sigma):
 z=np.zeros((501,501),np.float32);z[250,250]=1;o=cv2.GaussianBlur(z,(0,0),sigma);rows,cols=np.where(o!=0);return int(max(abs(rows-250).max(),abs(cols-250).max()))

def tiles(scale):
 samples=2;step=1/(samples*scale)
 for top in range(y0*scale,y1*scale,24*scale):
  bottom=min(top+24*scale,y1*scale);yy,xx=np.mgrid[top/scale:bottom/scale:step,x0:x1:step]
  x=(xx-.5+.5/(samples*scale)-s.case['center'][0])/L;y=(yy-.5+.5/(samples*scale)-s.case['center'][1])/L
  X,Y=settled.mapping(x,y,p)
  light=settled.lighting(x,y,p)
  if rim:light=np.concatenate([light,rim_field(x,y,p,rim['width'])[...,None]],axis=-1)
  yield top-y0*scale,bottom-y0*scale,(X*L+s.case['center'][0]),(Y*L+s.case['center'][1]),light

def render(scale,shift=(0,0),cached=None):
 H,W=(y1-y0)*scale,(x1-x0)*scale;out=np.zeros((H,W,3),np.float32)
 for top,bottom,X,Y,light in (cached if cached is not None else tiles(scale)):
  sampled=cv2.remap(blur,(X+shift[0]).astype(np.float32),(Y+shift[1]).astype(np.float32),cv2.INTER_LINEAR)
  if rim:
   raw=settled.shade(sampled,light[...,:-1],np.asarray(p['material']))+rim['amplitude']*light[...,-1,None]
   clip_audit['minimum']=min(clip_audit['minimum'],float(raw.min()));clip_audit['maximum']=max(clip_audit['maximum'],float(raw.max()));clip_audit['clipped_channels']+=int(np.count_nonzero((raw<0)|(raw>1)))
   q=np.clip(raw,0,1)
  else:q=settled.shade(sampled,light,np.asarray(p['material']))
  out[top:bottom]=q.reshape(bottom-top,2,W,2,3).mean((1,3))
 return cv2.GaussianBlur(out,(0,0),p['post_sigma']*L*scale) if p.get('post_sigma') else out

def combine(M,scale,shift=(0,0),alpha=None):
 H,W=M.shape[:2];yy,xx=np.mgrid[:H,:W].astype(np.float32);X=x0-.5+(xx+.5)/scale;Y=y0-.5+(yy+.5)/scale
 bg=cv2.remap(s.source,X+shift[0],Y+shift[1],cv2.INTER_LINEAR);A=s.shape_coverage(p,scale) if alpha is None else alpha
 assert 'shadow' not in p and not s.transmission_layers
 return s.apply_foreground(M*A[...,None]+bg*(1-A[...,None]),scale)

# This is an authorized export-equation control against saved candidate arrays,
# not a repeated fit/baseline/score. Native settled operation order is preserved.
if (D/'native-zero-control.json').exists():
 checks=json.loads((D/'native-zero-control.json').read_text())['checks'];assert all(checks.values());print('Reusing saved exact native zero control, no repeated render',flush=True)
else:
 M=render(1);Q=combine(M,1);checks=dict(material_exact=bool(np.array_equal(M,old['material'])),complete_exact=bool(np.array_equal(Q,old['complete'])),clean_public_compositor_exact=bool(np.array_equal(Q,composite(s,p,M))))
 save('native-zero-control.json',dict(checks=checks,material_max_difference=float(np.max(abs(M-old['material']))),complete_max_difference=float(np.max(abs(Q-old['complete']))),native_scores_not_rerun=True));assert all(checks.values());print('native-zero EXACT',flush=True)
if a.check_native_only:sys.exit(0)
assert 2*a.trim<min(x1-x0,y1-y0)
scale=1
while (x1-x0-2*a.trim)*scale<1080:scale*=2
W=(x1-x0)*scale;H=(y1-y0)*scale;postradius=kernel_radius(p['post_sigma']*L*scale);sourceradius=kernel_radius(sigma);alpha=s.shape_coverage(p,scale);scope=cv2.dilate((alpha>0).astype(np.uint8),np.ones((2*postradius+1,2*postradius+1),np.uint8))>0
# Retain only reusable geometry/light, NOT shaded material. Every pose samples
# the original fixed source and executes actual shade/post blur anew.
cache=[];bounds=np.array([np.inf,np.inf,-np.inf,-np.inf]);subrays=0
for top,bottom,X,Y,J in tiles(scale):
 m=np.repeat(np.repeat(scope[top:bottom],2,0),2,1);xx=X[m].astype(np.float32);yy=Y[m].astype(np.float32);bounds=np.array([min(bounds[0],float(xx.min()) if xx.size else np.inf),min(bounds[1],float(yy.min()) if yy.size else np.inf),max(bounds[2],float(xx.max()) if xx.size else -np.inf),max(bounds[3],float(yy.max()) if yy.size else -np.inf)]);subrays+=len(xx);cache.append((top,bottom,X,Y,J))
travel=a.travel;frames=90;fps=15;poses=[(0.,float(travel*np.sin(2*np.pi*i/frames))) for i in range(frames)]
known=cv2.imread(str(a.inputs/'known.png'),cv2.IMREAD_GRAYSCALE)>254
# Enclose every actual query after float32 rounding, bilinear footprint and
# ALL finite nonzero source-Gaussian neighbors. Rectangle conservative.
def check_rect(b,margin=0):
 l,t,r,z=b;l=int(np.floor(l))-margin;t=int(np.floor(t))-margin;r=int(np.ceil(r))+margin;z=int(np.ceil(z))+margin
 assert 0<=l<=r<known.shape[1] and 0<=t<=z<known.shape[0]
 assert known[t:z+1,l:r+1].all(),(l,t,r,z)
 return [l,t,r,z]
union=[bounds[0],bounds[1]-travel,bounds[2],bounds[3]+travel];rayrect=check_rect(union,sourceradius+1)
backgroundrect=check_rect([x0-.5+a.trim,y0-.5+a.trim-travel,x1-.5-a.trim,y1-.5-a.trim+travel],1)
# Independent conservative radial enclosure, not an extremum search.
# For active output/post neighbor subrays r<=1+sqrt(2)*(postradius+.5)/scale/L.
# Tail <= A*.5**power because both exponent coefficients are nonpositive.
rmax=1+np.sqrt(2)*(postradius+.5)/scale/L;par=p['parameters'];assert par['inner_power']-par['power']*par['width']<=0 and par['inner_power']-par['power']*par['width']**2<=0
amax=par['amplitude']*(1+max(rmax-1,0)/par['band'])**par['power'];Rbound=max(rmax,par['amplitude'],max(amax-1,0))*L
independent=check_rect([s.case['center'][0]-Rbound,s.case['center'][1]-Rbound-travel,s.case['center'][0]+Rbound,s.case['center'][1]+Rbound+travel],sourceradius+1)
save('source-support.json',dict(passed=True,internal_scale=scale,post_radius_output_pixels=postradius,source_radius_native_pixels=sourceradius,contributing_subrays=subrays,actual_zero_query_bounds=bounds.tolist(),all_90_pose_source_enclosure_including_bilinear_and_source_kernel=rayrect,all_90_pose_exterior_enclosure=backgroundrect,independent_radial_rmax=rmax,independent_radial_amax=amax,independent_all_pose_enclosure=independent,minimum_modeled_confidence=1,observed_hidden_fraction=0,declared_source_domain_respected=True,travel_native_pixels=travel,axis='y',poses=poses,limits='Finite discrete kernels + all visible-alpha/post-neighbor source subrays with bilinear footprint; conservative independent bound includes every pose. Modeled background definition, not observed recovery or physical optical validation. Full native scored proof inherited separately; export does not rescore.'))
print('all-pose/source support PASS',rayrect,independent,flush=True)
finalW=1080;finalH=round((y1-y0-2*a.trim)*finalW/(x1-x0-2*a.trim));zero=None;framehashes=[];t0=time.time()
for i,pose in enumerate(poses):
 M=render(scale,pose,cache);rgb=combine(M,scale,pose,alpha);rgb8=np.uint8(np.rint(np.clip(rgb,0,1)*255));trim=a.trim*scale;rgb8=rgb8[trim:rgb8.shape[0]-trim,trim:rgb8.shape[1]-trim];out=cv2.resize(rgb8,(finalW,finalH),interpolation=cv2.INTER_AREA)
 file=D/'frames'/f'{i:03}.png';Image.fromarray(out).save(file);framehashes.append(dict(frame=i,shift=pose,sha256=digest(file)))
 if i==0:
  zero=out;Image.fromarray(out).save(D/'reconstruction.png');target=cv2.resize(np.uint8(np.rint(s.target_crop[a.trim:s.target_crop.shape[0]-a.trim,a.trim:s.target_crop.shape[1]-a.trim]*255)),(finalW,finalH),interpolation=cv2.INTER_LANCZOS4);Image.fromarray(target).save(D/'original.png');pair(target,out,('Apple source','Reconstruction'),font_size=32).save(D/'comparison.png')
 print(f'frame {i+1}/{frames}',flush=True)
cmd=['ffmpeg','-v','error','-threads','2','-filter_threads','1','-y','-framerate',str(fps),'-i',str(D/'frames/%03d.png')]
subprocess.run(cmd+['-vf','pad=ceil(iw/2)*2:ceil(ih/2)*2,'+ENCODING_COLOR_ARGS[1],'-c:v','libx264','-threads','2','-crf','18','-pix_fmt','yuv420p',*ENCODING_COLOR_ARGS[2:],'-movflags','+faststart',str(D/'motion.mp4')],check=True)
for colors in [128,96,64]:
 subprocess.run(cmd+['-vf',f'palettegen=max_colors={colors}:stats_mode=full','-frames:v','1',str(D/'palette.png')],check=True)
 subprocess.run(cmd+['-i',str(D/'palette.png'),'-filter_complex_threads','1','-lavfi','[0:v][1:v]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle','-loop','0',str(D/'motion.gif')],check=True)
 if (D/'motion.gif').stat().st_size<10_000_000:break
assert (D/'motion.gif').stat().st_size<10_000_000
set_gif_timing(D/'motion.gif',6)
files={}
for name in ['motion.mp4','motion.gif']:
 f=D/name;probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-count_frames','-show_streams','-show_format','-of','json',str(f)]));assert int(probe['streams'][0]['nb_read_frames'])==90
 files[name]={'bytes':f.stat().st_size,'sha256':digest(f),'probe':probe,'decoded_rgb_digest':rgb_digest(f)}
save('verification.json',{'recipe_sha256':digest(a.record),'native_zero_exact':checks,'source_support':'source-support.json','internal_raster':[W,H],'export':[finalW,finalH],'frames':90,'seconds':6,'rim_record_sha256':digest(a.rim_record) if a.rim_record else None,'raw_rim_audit_all_export_pixels':clip_audit if rim else None,'presentation_trim_native_pixels':a.trim,'travel_native_pixels':a.travel,'files':files,'limits':'Illustrative source translation over estimated background, observed hidden fraction0; no new scoring, physical-optics or Apple-motion validation.'})
