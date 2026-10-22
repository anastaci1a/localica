"""Render frozen unfiltered neutral material and illustrative translation, no fitting.
python neutral_motion.py INPUTS RECORD NEW_OUTPUT
1080px HQ comparison and1080x360 motion; frozen source rectangle checked.
"""
from pathlib import Path
import copy,json,hashlib,subprocess,sys,math
import numpy as np,cv2
from PIL import Image
from extended_fit import Study
from vector_revision import composite
from source_support import masks,confidence_at
from publish import pair
from motion import set_gif_timing
from video_color import ENCODING_COLOR_ARGS,rgb_digest
cv2.setNumThreads(1)
import argparse
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('record',type=Path);ap.add_argument('output',type=Path);ap.add_argument('--rim-record',type=Path);ap.add_argument('--native-reference',type=Path);a=ap.parse_args()
O=a.output;assert not O.exists(),'Preserve completed exports; use a new output directory';O.mkdir(parents=True)
r=json.loads(a.record.read_text());s=Study(a.inputs);
assert hashlib.sha256(json.dumps(s.case,sort_keys=True).encode()).hexdigest()==next(iter(r['case_sha256'].values()))
configs={'mail-three-action-single-a':([315,590,995,815],[113,520,1193,880]),'safari-tab-count-plus-single-a':([1015,600,1330,810],[635,510,1715,870])}
expected_crop,canvas=configs[s.case['id']];assert list(s.crop)==expected_crop
for name,digest in next(iter(r['vector_sha256'].values())).items():assert hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()==digest
from neutral_material import coefficients
assert np.array_equal(coefficients(r['tau']),np.asarray(r['material']))
p=r['parameters'];co=np.array(r['material']);rim=json.loads(a.rim_record.read_text()) if a.rim_record else None;rim_audit={}
def render(scale=1):
 if rim:
  from neutral_rim import render_study
  return render_study(s,p,co,rim['amplitude'],rim['width'],scale=scale,audit=rim_audit)
 return s.render(p,co,scale)
if a.native_reference:
 old=np.load(a.native_reference);M=render();C=composite(s,p,M);assert np.array_equal(M,old['material']) and np.array_equal(C,old['complete']);(O/'native-zero-control.json').write_text(json.dumps({'material_exact':True,'complete_exact':True,'native_scores_not_repeated':True})+'\n')
base=copy.deepcopy(s.case);basecrop=list(s.crop);fg=copy.deepcopy(s.foreground_layers)
assert p['sigma']==p['post_sigma']==p['blur_transition']==p['shadow']['opacity']==0
D,_=masks(s)
width,height=1080,round(1080*(basecrop[3]-basecrop[1])/(basecrop[2]-basecrop[0]))
hq_scale=2**max(0,int(np.ceil(np.log2(width/(basecrop[2]-basecrop[0])))))
# Extend only the mathematical exterior; source sampling keeps the original PNG.
yy,xx=np.mgrid[:s.source.shape[0],:s.source.shape[1]]
bg=base['background_estimate']
if base['id']=='mail-three-action-single-a':
 u,v=(xx-653.5)/300,(yy-701.5)/82.5;basis=np.stack([np.ones_like(u),u,v],-1)
else:
 u,v=(xx-1173)/150,(yy-706)/100;basis=np.stack([np.ones_like(u),u,v,u*u,u*v,v*v],-1)
exterior=np.uint8(np.rint(np.clip(basis@np.asarray(bg['coefficient_rgb']),0,1)*255)).astype('float32')/255
known=cv2.imread(str(a.inputs/'known.png'),0)>0
assert np.array_equal(exterior[known],s.source[known]),'Exterior completion must preserve frozen source pixels'
def support(scale):
 counts=bad=0;bounds=[1e9,1e9,-1e9,-1e9]
 for row,bottom,W,x,y in s.raster_tiles(scale):
  X,Y,d,J=s.geometry_field(x,y,p);rx=(X*s.L+s.case['center'][0]).astype('float32');ry=(Y*s.L+s.case['center'][1]).astype('float32');m=np.ones(x.shape,bool)
  q=confidence_at(D,rx,ry);counts+=int(m.sum());bad+=int((q[m]<.999999).sum())
  if m.any():bounds=[min(bounds[0],float(rx[m].min())),min(bounds[1],float(ry[m].min())),max(bounds[2],float(rx[m].max())),max(bounds[3],float(ry[m].max()))]
 assert bad==0
 return {'rectangular_queries':counts,'bad':bad,'bounds':bounds,'filters':0,'hidden_observed_fraction':0}
if not(O/'hq.json').exists():
 proof=support(hq_scale);M=render(hq_scale);C=composite(s,p,M,hq_scale);target=cv2.resize(np.uint8(np.rint(s.target_crop*255)),(width,height),interpolation=cv2.INTER_LANCZOS4);right=cv2.resize(np.uint8(np.rint(np.clip(C,0,1)*255)),(width,height),interpolation=cv2.INTER_AREA);pair(target,right,('Apple source','Reconstruction'),font_size=32).save(O/'comparison.png');(O/'hq.json').write_text(json.dumps({'raster_evaluated':[(basecrop[2]-basecrop[0])*hq_scale,(basecrop[3]-basecrop[1])*hq_scale],'export_per_side':[width,height],'sampling':'2x2 per evaluated output pixel; area downsample','source':proof,'native_scores':'Unchanged saved pass03; presentation only'},indent=2)+'\n')
# Viewport1080x360 at native scale. Body itself is evaluated at 2x2 per output pixel.
background=exterior[canvas[1]:canvas[3],canvas[0]:canvas[2]].copy();frames=O/'frames';frames.mkdir(exist_ok=True);cache=O/'cache';cache.mkdir(exist_ok=True)
positions=[(int(round(38*math.sin(2*math.pi*i/59))),int(round(7*math.sin(4*math.pi*i/59))))for i in range(60)]
for dx,dy in dict.fromkeys(positions):
 f=cache/f'{dx}_{dy}.npz'
 if f.exists():continue
 s.case=copy.deepcopy(base);s.case['center']=[base['center'][0]+dx,base['center'][1]+dy];s.crop=[v+(dx if i%2==0 else dy)for i,v in enumerate(basecrop)];s.vector_layers={};s.foreground_layers=[]
 for layer,old in zip(s.case['foreground_layers'],fg):
  layer['box']=[v+(dx if i%2==0 else dy)for i,v in enumerate(layer['box'])];layer['vector_box']=[v+(dx if i%2==0 else dy)for i,v in enumerate(layer['vector_box'])];s.vector_layers[tuple(layer['box'])]=layer;s.foreground_layers.append((layer['box'],old[1],old[2]))
 proof=support(1);M=render();C=composite(s,p,M,background_source=exterior);np.savez_compressed(f,complete=C);(cache/f'{dx}_{dy}.json').write_text(json.dumps(proof));print('motionpose',dx,dy,flush=True)
for i,(dx,dy)in enumerate(positions):
 C=np.load(cache/f'{dx}_{dy}.npz')['complete'];B=background.copy();x=basecrop[0]+dx-canvas[0];y=basecrop[1]+dy-canvas[1];B[y:y+C.shape[0],x:x+C.shape[1]]=C;Image.fromarray(np.uint8(np.rint(np.clip(B,0,1)*255))).save(frames/f'{i:03d}.png')
mp4=O/'motion.mp4';gif=O/'motion.gif'
subprocess.run(['ffmpeg','-v','error','-y','-threads','2','-framerate','15','-i',str(frames/'%03d.png'),'-c:v','libx264','-crf','18','-pix_fmt','yuv420p',*ENCODING_COLOR_ARGS,'-movflags','+faststart',str(mp4)],check=True)
for colors in [128,96,64,32]:
 subprocess.run(['ffmpeg','-v','error','-y','-threads','2','-framerate','15','-i',str(frames/'%03d.png'),'-filter_complex_threads','1','-filter_complex',f'[0:v]split[a][b];[a]palettegen=max_colors={colors}[p];[b][p]paletteuse=dither=bayer:bayer_scale=4','-loop','0',str(gif)],check=True);set_gif_timing(gif,4)
 if gif.stat().st_size<10_000_000:break
assert gif.stat().st_size<10_000_000
if rim:assert rim_audit['clipped_channels']==0
media={}
for f in [mp4,gif]:
 probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-count_frames','-show_entries','stream=width,height,nb_read_frames,duration:format=duration','-of','json',str(f)]));assert int(probe['streams'][0]['nb_read_frames'])==60;media[f.name]={'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'probe':probe,'decoded_rgb_digest':rgb_digest(f)}
(O/'motion.json').write_text(json.dumps({'recipe_sha256':hashlib.sha256(a.record.read_bytes()).hexdigest(),'rim_record_sha256':hashlib.sha256(a.rim_record.read_bytes()).hexdigest() if rim else None,'rim_raw_audit':rim_audit if rim else None,'files':media,'raster':[1080,360],'frames':60,'fps':15,'duration':4,'unique_poses':len(set(positions)),'sampling':'2x2 per OUTPUT pixel, fixed field evaluated at each translated pose; repeated positions cached','source_support':'Every unique pose: ALL emitted rectangular crop queries checked against original frozen defined mask; no filters. Proof files retained privately. Modeled polynomial exterior extends outside the original source rectangle, no parent boundary/reference shadow.','limits':'Illustrative translation only; source hiddenRGB modeled, no new optical fit, Android measurement or Apple motion claim'},indent=2)+'\n')
print('MEDIA COMPLETE',media,flush=True)
