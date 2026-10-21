"""Export fixed-shape slider reconstruction at1080px with fresh source sampling.
The track fill stays fixed. This is illustrative motion, not an Apple capture.
"""
import argparse,copy,hashlib,json,subprocess,time
from pathlib import Path
import cv2,numpy as np
from PIL import Image
from clear_field import ClearField
from slider_exterior import generated
from publish import pair
from motion import set_gif_timing
from video_color import ENCODING_COLOR_ARGS

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,j):p.write_text(json.dumps(j,indent=2)+'\n')
def source_bounds(field,center,size):
 lo=np.array([np.inf,np.inf]);hi=-lo
 for x,y in field.query:
  x=(x+center[0]).astype(np.float32);y=(y+center[1]).astype(np.float32);lo=np.minimum(lo,[x.min(),y.min()]);hi=np.maximum(hi,[x.max(),y.max()])
 assert np.all(lo>=1) and np.all(hi<=np.array(size)-2)
 return lo,hi

def main():
 ap=argparse.ArgumentParser(description=__doc__)
 for n in ['inputs','record','out']:ap.add_argument(n,type=Path)
 ap.add_argument('--width',type=int,default=1080);ap.add_argument('--frames',type=int,default=90);ap.add_argument('--fps',type=int,default=15);ap.add_argument('--travel',type=float,default=30);ap.add_argument('--native-material-cache',type=Path);ap.add_argument('--native-zero-expected',type=Path);a=ap.parse_args();cv2.setNumThreads(1)
 assert a.width>0 and a.width%2==0 and a.frames>0 and a.fps>0
 a.out.mkdir(parents=True,exist_ok=False);r=json.loads(a.record.read_text());case=r['case'];preset=r['body_recipe'];sp=a.inputs/'slider-source.png';tp=a.inputs/'slider-target.png';assert sha(sp)==r['inputs']['slider-source.png'] and sha(tp)==r['inputs']['slider-target.png']
 source=np.asarray(Image.open(sp).convert('RGB')).astype(float)/255;target=np.asarray(Image.open(tp).convert('RGB'));save(a.out/'started.json',dict(record_sha256=sha(a.record),width=a.width,frames=a.frames,fps=a.fps,source_sha256=sha(sp),body_model_unchanged=True))
 if a.native_material_cache:assert sha(a.native_material_cache)==r['native_material_sha256'];native=np.load(a.native_material_cache)
 else:native=ClearField(case,preset,samples=4).render(source)
 np.save(a.out/'native-material.npy',native);assert sha(a.out/'native-material.npy')==r['native_material_sha256']
 zero,_,_=generated(case,source,native,r);np.save(a.out/'native-zero-complete.npy',zero)
 if a.native_zero_expected:assert np.array_equal(zero,np.load(a.native_zero_expected))
 field=ClearField(case,preset,samples=4,width=a.width);W,H=field.W,field.H;videoH=H+H%2;material0=field.render(source);C0,_,_=generated(case,source,material0,r);np.save(a.out/'hq-material.npy',material0);np.save(a.out/'hq-complete.npy',C0);x0,y0,x1,y1=case['crop'];ref=Image.fromarray(target[y0:y1,x0:x1]);right=Image.fromarray(np.rint(np.clip(C0,0,1)*255).astype(np.uint8))
 for suffix,w in [('',720),('-full',1080)]:
  h=round((y1-y0)*w/(x1-x0));left=np.asarray(ref.resize((w,h),Image.Resampling.LANCZOS));rec=np.asarray(right.resize((w,h),Image.Resampling.LANCZOS));pair(left,rec,['Apple reference','Reconstruction'],font_size=32).save(a.out/f'match-slider{suffix}.png')
 mp4=a.out/'slider-final.mp4';log=(a.out/'encode.log').open('w');enc=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','warning','-y','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{W}x{videoH}','-framerate',str(a.fps),'-i','pipe:0','-an','-c:v','libx264','-preset','fast','-crf','17','-threads','2','-filter_threads','1','-pix_fmt','yuv420p',*ENCODING_COLOR_ARGS,'-movflags','+faststart',str(mp4)],stdin=subprocess.PIPE,stderr=log)
 frames=[];cache={};alllo=np.array([np.inf,np.inf]);allhi=-alllo;start=time.monotonic()
 try:
  for i in range(a.frames):
   offset=round(a.travel*field.sx*np.sin(2*np.pi*i/a.frames));center=[case['center'][0]+offset/field.sx,case['center'][1]]
   if offset not in cache:
    lo,hi=source_bounds(field,center,[source.shape[1],source.shape[0]]);alllo=np.minimum(alllo,lo);allhi=np.maximum(allhi,hi)
    if offset==0:complete=C0
    else:
     moved=field.render(source,center);view=np.zeros_like(moved);left=max(0,offset);rightedge=min(W,W+offset);view[:,left:rightedge]=moved[:,left-offset:rightedge-offset];c=copy.deepcopy(case);c['center']=center;complete,_,_=generated(c,source,view,r)
    rgb=np.rint(np.clip(complete,0,1)*255).astype(np.uint8)
    if videoH!=H:rgb=np.concatenate([rgb,rgb[-1:]],axis=0)
    cache[offset]=rgb;Image.fromarray(rgb).save(a.out/f'pose-{offset:+05d}.png')
   rgb=cache[offset];enc.stdin.write(rgb.tobytes());frames.append(dict(frame=i,offset_output_pixels=offset,offset_native_pixels=offset/field.sx,rgb_sha256=hashlib.sha256(rgb.tobytes()).hexdigest()))
   if i in [0,a.frames//4,a.frames//2,3*a.frames//4]:Image.fromarray(rgb).save(a.out/f'frame-{i:03d}.png')
   if i%15==0:save(a.out/'progress.json',dict(frames=i+1,total=a.frames,unique_positions=len(cache)));print('frame',i+1,flush=True)
 finally:enc.stdin.close();assert enc.wait()==0;log.close()
 save(a.out/'frames.json',frames);save(a.out/'render-complete.json',dict(frames=a.frames,unique_positions=len(cache),source_bounds=[alllo.tolist(),allhi.tolist()],contributing_subrays=len(field.active),whole_queries_defined=True));save(a.out/'progress.json',dict(frames=a.frames,total=a.frames,stage='encode-gif'));gif=a.out/'slider-final.gif';palette=a.out/'palette.png';duration=a.frames/a.fps
 for colors in [256,192,128,96]:
  subprocess.run(['ffmpeg','-v','error','-y','-threads','2','-filter_threads','1','-i',str(mp4),'-vf',f'palettegen=max_colors={colors}:stats_mode=diff','-frames:v','1',str(palette)],check=True)
  subprocess.run(['ffmpeg','-v','error','-y','-threads','2','-filter_complex_threads','1','-i',str(mp4),'-i',str(palette),'-lavfi','[0:v][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle','-loop','0','-gifflags','-transdiff',str(gif)],check=True);count,ms=set_gif_timing(gif,duration);assert count==a.frames and ms==round(duration*100)*10
  if gif.stat().st_size<10_000_000:break
 assert gif.stat().st_size<10_000_000
 files=[]
 for f in [mp4,gif]:
  md5=a.out/(f.suffix[1:]+'-decoded.framemd5');subprocess.run(['ffmpeg','-v','error','-threads','2','-i',str(f),'-f','framemd5','-y',str(md5)],check=True);decoded=sum(bool(line and not line.startswith('#')) for line in md5.read_text().splitlines());assert decoded==a.frames;meta=json.loads(subprocess.check_output(['ffprobe','-v','error','-count_frames','-show_streams','-show_format','-of','json',str(f)]));stream=next(s for s in meta['streams'] if s['codec_type']=='video');assert stream['width']==W and stream['height']==videoH;files.append(dict(file=f.name,bytes=f.stat().st_size,sha256=sha(f),decoded_frames=decoded,probe=meta))
 save(a.out/'verification.json',dict(record_sha256=sha(a.record),native_material_exact=True,native_zero_complete_exact=bool(a.native_zero_expected),hq_size=[W,H],video_size=[W,videoH],bottom_padding_rows=videoH-H,output_samples_per_axis=4,frames=a.frames,fps=a.fps,unique_positions=len(cache),reused_identical_pose_frames=a.frames-len(cache),source_queries_per_unique_pose=len(field.active)*3,packed_query_note='OpenCV pack padding reads(0,0), whose0/1bilinear taps are all defined; zero-weight padding is discarded.',contributing_source_bounds=[alllo.tolist(),allhi.tolist()],all_contributing_and_padding_queries_defined=True,observed_confidence_claimed=False,source_limits='Frozen inferred paper/track and hidden endpoint, no photographic recovery or second-background optical validation. Whole native optical scores are inherited unchanged; HQ sampling is not a scored gain.',files=files,render_encode_seconds=time.monotonic()-start,source_sha256_after=sha(sp),motion='Integer output-pixel translations of a fixed shape, frozen fill value; source samples and body/halo/contact move coherently. Illustrative, not captured Apple interaction or Android performance.'))
 print('FINAL READY',flush=True)
if __name__=='__main__':main()
