"""Final comparison and scrolling-backdrop GIF for a frozen element.

python extended_media.py /private/study [--frames 90] [--travel 45]
No reference material rectangle is composited into the computed glass.
"""
from pathlib import Path
import argparse,json,subprocess,hashlib
import cv2,numpy as np
from PIL import Image
from extended_fit import Study
from extended_shadow import shadow


def foreground(study,out):
    return study.apply_foreground(out)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('--frames',type=int,default=90);ap.add_argument('--travel',type=float,default=45);args=ap.parse_args()
    study=Study(args.folder);accepted=json.loads((args.folder/'accepted.json').read_text());p=accepted['parameters'];co=np.array(accepted['material'])
    out=args.folder/'final';out.mkdir(exist_ok=True)
    still=study.render(p,co);study.comparison(still,out/'comparison.png',p)
    # Final rendering must reproduce the accepted scoring state, not merely
    # reuse its stored score metadata or its last screenshot.
    current=study.scores(still)
    assert abs(current['loss']-accepted['scores']['loss'])<1e-6
    x0,y0,x1,y1=study.crop;width=x1-x0;height=y1-y0
    yy,xx=np.mgrid[y0:y1:.5,x0:x1:.5]
    *_,(sd,_,_)=study.shape((xx-.25-study.case['center'][0])/study.L,(yy-.25-study.case['center'][1])/study.L,p)
    cov=(sd<=0).reshape(height,2,width,2).mean((1,3))
    yy,xx=np.mgrid[y0:y1,x0:x1].astype(np.float32)
    opacity=shadow(study,p,study.x,study.y)
    known=cv2.imread(str(args.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
    from motion_support import prepare
    motion_source,travel,motion_support=prepare(study,p,known,cov,args.travel)
    study.source=motion_source;study.cache.clear()
    video=out/'motion.mp4';log=(out/'encode.log').open('w')
    # yuv420 requires even dimensions; padding preserves all native pixels.
    W=width+width%2;H=height+height%2
    proc=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','warning','-y','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{W}x{H}','-framerate','15','-i','pipe:0','-an','-c:v','libx264','-preset','fast','-crf','17','-pix_fmt','yuv420p','-movflags','+faststart',str(video)],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=log)
    try:
        for i in range(args.frames):
            shift=np.float32(travel*np.sin(2*np.pi*i/args.frames))
            study.backdrop_shift=[0.,float(shift)]
            material=study.render(p,co)
            base=cv2.remap(study.source,xx,yy+shift,cv2.INTER_LINEAR)
            valid=cv2.remap(known,xx,yy+shift,cv2.INTER_NEAREST)>0
            # Unknown EXTERIOR context uses the reference context. Unknown
            # input is never turned into a scored recovery claim.
            base=np.where(valid[...,None],base,study.target_crop)
            base=base*(1-opacity[...,None])
            frame=foreground(study,material*cov[...,None]+base*(1-cov[...,None]))
            frame=np.uint8(np.rint(np.clip(frame,0,1)*255))
            if W!=width or H!=height:frame=cv2.copyMakeBorder(frame,0,H-height,0,W-width,cv2.BORDER_REPLICATE)
            proc.stdin.write(frame.tobytes())
            if i in [0,args.frames//4,args.frames//2,3*args.frames//4]:Image.fromarray(frame).save(out/f'frame-{i:03}.png')
    finally:
        proc.stdin.close();assert proc.wait()==0;log.close()
    gif=out/'motion.gif'
    for fps,colors in [(15,128),(12,96),(10,64),(6,48)]:
        palette=out/'palette.png'
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(video),'-vf',f'fps={fps},palettegen=max_colors={colors}:stats_mode=diff','-frames:v','1',str(palette)],check=True)
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(video),'-i',str(palette),'-lavfi',f'fps={fps}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle','-loop','0','-gifflags','+transdiff',str(gif)],check=True)
        if gif.stat().st_size<10_000_000:break
    assert gif.stat().st_size<10_000_000
    files={}
    for file in [video,gif,out/'comparison.png']:
        if file.suffix in ['.mp4','.gif']:
            subprocess.run(['ffmpeg','-v','error','-i',str(file),'-f','null','-'],check=True)
        files[file.name]=dict(bytes=file.stat().st_size,sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    report=dict(files=files,native_size=[W,H],frames=args.frames,fps=15,travel_over_L=travel/study.L,scores=current,motion_support=motion_support,
        motion='Stationary element; recovered backdrop translates vertically. Optical/material coefficients remain frozen.',
        limits='Offline preview, not Apple motion timing, a device benchmark or proof of adaptive cross-scene material. Foreground coverage is reference-derived.')
    if study.transmission_layers:report['foreground_response']=[layer['fit'][2] for layer in study.transmission_layers]
    (out/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report),flush=True)


if __name__=='__main__':main()
