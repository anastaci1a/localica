"""Final comparison and scrolling-backdrop GIF for a frozen element.

python extended_media.py /private/study [--frames 90] [--travel 45]
Use --finish-existing to retry GIF encoding from the hash-checked render manifest.
GIF previews retain a full palette; masters above2MP use half-size GIFs.
--gif-full-size overrides preview sizing; --gif-max-mb sets the size budget.
No reference material rectangle is composited into the computed glass.
"""
from pathlib import Path
import argparse,json,subprocess,hashlib
import cv2,numpy as np
from PIL import Image
from extended_fit import Study
from extended_shadow import shadow
from prepared import Prepared
from video_color import ENCODING_COLOR_ARGS,probe


def foreground(study,out,scale=1):
    return study.apply_foreground(out,scale)


def state_digest(folder):
    return hashlib.sha256((folder/'case.json').read_bytes()+(folder/'accepted.json').read_bytes()).hexdigest()


def finish(folder,report,budget,full_size=False):
    out=folder/'final';video=out/'motion.mp4';gif=out/'motion.gif'
    assert report['state_sha256']==state_digest(folder),'Render state changed'
    for name,digest in report['render_files'].items():
        assert hashlib.sha256((out/name).read_bytes()).hexdigest()==digest,'Rendered artifact changed'
    W,H=report['output_size'];factor=.5 if W*H>2_000_000 and not full_size else 1.
    width,height=round(W*factor),round(H*factor)
    for fps in [15,12,10,6]:
        palette=out/'palette.png'
        samples=f'fps={fps},scale={width}:{height}:flags=lanczos'
        subprocess.run(['ffmpeg','-v','error','-threads','2','-filter_threads','1','-y','-i',str(video),'-vf',samples+',palettegen=max_colors=256:stats_mode=full','-frames:v','1',str(palette)],check=True)
        subprocess.run(['ffmpeg','-v','error','-threads','2','-filter_complex_threads','1','-y','-i',str(video),'-i',str(palette),'-lavfi',samples+'[x];[x][1:v]paletteuse=dither=sierra2_4a:diff_mode=rectangle','-loop','0','-gifflags','+transdiff',str(gif)],check=True)
        if gif.stat().st_size<budget*1_000_000:break
    assert gif.stat().st_size<budget*1_000_000,'GIF exceeds its explicit size budget'
    files={}
    for file in [video,gif,out/'comparison.png']:
        if file.suffix in ['.mp4','.gif']:
            subprocess.run(['ffmpeg','-v','error','-threads','2','-i',str(file),'-fps_mode','passthrough','-enc_time_base','1/1000','-f','null','-'],check=True)
        files[file.name]=dict(bytes=file.stat().st_size,sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    report.update(files=files,gif=dict(fps=fps,output_size=[width,height],master_relative_scale=factor,palette_colors=256,palette_sampling='All frame pixels',dither='sierra2_4a',maximum_megabytes=budget,spatial_downsampling=factor!=1))
    report.setdefault('video_color',dict(metadata=probe(video),limits='Encoded RGB export metadata; no measured display/transfer-function calibration.'))
    (out/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report),flush=True)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('--frames',type=int,default=180);ap.add_argument('--travel',type=float,default=45);ap.add_argument('--scale',type=int,default=4);ap.add_argument('--fps',type=int,default=30);ap.add_argument('--gif-max-mb',type=float,default=90);ap.add_argument('--gif-full-size',action='store_true');ap.add_argument('--finish-existing',action='store_true');args=ap.parse_args()
    assert args.scale>=1 and args.frames>0 and args.fps>0 and args.gif_max_mb>0
    if args.finish_existing:
        finish(args.folder,json.loads((args.folder/'final/render.json').read_text()),args.gif_max_mb,args.gif_full_size)
        return
    study=Study(args.folder);accepted=json.loads((args.folder/'accepted.json').read_text());p=accepted['parameters'];co=np.array(accepted['material'])
    out=args.folder/'final';out.mkdir(exist_ok=True)
    still=study.render(p,co)
    # Final rendering must reproduce the accepted scoring state, not merely
    # reuse its stored score metadata or its last screenshot.
    current=study.scores(still)
    assert abs(current['loss']-accepted['scores']['loss'])<1e-6
    # Freeze foreground response at native reference resolution before export.
    study.apply_foreground(still.copy())
    scale=args.scale;x0,y0,x1,y1=study.crop;width=(x1-x0)*scale;height=(y1-y0)*scale
    native_cov=study.shape_coverage(p);cov=study.shape_coverage(p,scale)
    yy,xx=np.mgrid[:height,:width].astype(np.float32)
    xx=x0-.5+(xx+.5)/scale;yy=y0-.5+(yy+.5)/scale
    opacity=shadow(study,p,(xx-study.case['center'][0])/study.L,(yy-study.case['center'][1])/study.L)
    known=cv2.imread(str(args.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
    from motion_support import prepare
    motion_source,travel,motion_support=prepare(study,p,known,native_cov,args.travel)
    study.source=motion_source;study.cache.clear()
    # Unobserved source pixels are magenta diagnostics, not display content.
    # Extend them only in this presentation input, after freezing all scores
    # and foreground fits. The recorded benchmark still uses the original
    # file; report any filter-tail effect even within its valid mask.
    presentation_native=study.render(p,co)
    delta=(presentation_native-still)[study.valid]*255
    completion_delta=dict(max_abs_rgb=float(np.max(abs(delta))),rmse=float(np.sqrt(np.mean(delta**2))))
    motion_support['completion']='Nearest observed RGB extends unobserved input for still/video presentation only. Original input files, native benchmark and fitted coefficients are unchanged.'
    high=study.render(p,co,scale);study.comparison(high,out/'comparison.png',p,scale)
    del high
    prepared=Prepared(study,p,co,scale)
    # Check the prepared renderer on the actual motion input, independently of
    # the frozen native scoring pass; cache speed cannot change the material.
    assert np.max(abs(prepared.render()-study.render(p,co,scale)))<2e-6
    context=cv2.resize(study.target_crop,(width,height),interpolation=cv2.INTER_LANCZOS4) if scale>1 else study.target_crop
    video=out/'motion.mp4';log=(out/'encode.log').open('w')
    # yuv420 requires even dimensions; padding preserves all rendered pixels.
    W=width+width%2;H=height+height%2
    proc=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','warning','-y','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{W}x{H}','-framerate',str(args.fps),'-i','pipe:0','-an',
        *ENCODING_COLOR_ARGS,
        '-c:v','libx264','-threads','2','-preset','fast','-crf','17','-x264-params','rc-lookahead=4:sync-lookahead=1','-pix_fmt','yuv420p','-movflags','+faststart',str(video)],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=log)
    try:
        for i in range(args.frames):
            shift=np.float32(travel*np.sin(2*np.pi*i/args.frames))
            study.backdrop_shift=[0.,float(shift)]
            material=prepared.render()
            base=cv2.remap(study.source,xx,yy+shift,cv2.INTER_LINEAR)
            valid=cv2.remap(known,xx,yy+shift,cv2.INTER_NEAREST)>0
            # Unknown EXTERIOR context uses the reference context. Unknown
            # input is never turned into a scored recovery claim.
            base=np.where(valid[...,None],base,context)
            base=base*(1-opacity[...,None])
            frame=foreground(study,material*cov[...,None]+base*(1-cov[...,None]),scale)
            frame=np.uint8(np.rint(np.clip(frame,0,1)*255))
            if W!=width or H!=height:frame=cv2.copyMakeBorder(frame,0,H-height,0,W-width,cv2.BORDER_REPLICATE)
            proc.stdin.write(frame.tobytes())
            if i in [0,args.frames//4,args.frames//2,3*args.frames//4]:Image.fromarray(frame).save(out/f'frame-{i:03}.png')
    finally:
        proc.stdin.close();assert proc.wait()==0;log.close()
    # Encoding does not need the large optical maps or the last RGB frame.
    del prepared,cov,xx,yy,opacity,context,base,material,frame,valid
    report=dict(state_sha256=state_digest(args.folder),render_files={name:hashlib.sha256((out/name).read_bytes()).hexdigest() for name in ['motion.mp4','comparison.png']},native_size=[x1-x0,y1-y0],output_size=[W,H],output_scale=scale,frames=args.frames,fps=args.fps,travel_over_L=travel/study.L,scores=current,motion_support=motion_support,presentation_completion_native_delta=completion_delta,
        motion='Stationary element; recovered backdrop translates vertically. Optical/material coefficients remain frozen.',
        limits='Offline preview, not Apple motion timing, a device benchmark or proof of adaptive cross-scene material. Source footage retains its original information quality; SVG icons/font and catalogue artwork use their declared clean/high-resolution inputs. Undeclared legacy foreground coverage remains reference-derived.')
    if study.transmission_layers:report['foreground_response']=[layer['fit'][2] for layer in study.transmission_layers]
    (out/'render.json').write_text(json.dumps(report,indent=2)+'\n')
    finish(args.folder,report,args.gif_max_mb,args.gif_full_size)


if __name__=='__main__':main()
