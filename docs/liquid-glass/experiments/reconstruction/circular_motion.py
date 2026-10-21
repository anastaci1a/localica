"""Render an illustrative Safari plus translation; no Apple motion match or fit.

python circular_motion.py INPUT_FOLDER RECORD OUTPUT --prepare-only
python circular_motion.py INPUT_FOLDER FINAL_RECORD OUTPUT
Fixed1080x720, native source-coordinate scale3, two samples/output axis.
"""
import argparse,copy,hashlib,json,math,os,subprocess
from pathlib import Path
import cv2,numpy as np
from PIL import Image
from extended_fit import Study
from vector_revision import composite
from video_color import ENCODING_COLOR_ARGS,rgb_digest
from motion import set_gif_timing

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def configure(s,case,crop,dx,dy,foreground):
    s.case=copy.deepcopy(case);s.case['center']=[case['center'][0]+dx,case['center'][1]+dy];s.crop=crop
    layer=s.case['foreground_layers'][0];layer['box']=[v+(dx if i%2==0 else dy) for i,v in enumerate(layer['box'])];layer['vector_box']=[v+(dx if i%2==0 else dy)for i,v in enumerate(layer['vector_box'])]
    s.vector_layers={tuple(layer['box']):layer};s.foreground_layers=[(layer['box'],foreground[1],foreground[2])]

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('inputs',type=Path);ap.add_argument('record',type=Path);ap.add_argument('output',type=Path);ap.add_argument('--prepare-only',action='store_true');a=ap.parse_args();os.umask(0o002);cv2.setNumThreads(1)
    s=Study(a.inputs);case=copy.deepcopy(s.case);r=json.loads(a.record.read_text());p=r['parameters'];co=np.asarray(r['material']);assert hashlib.sha256(json.dumps(case,sort_keys=True).encode()).hexdigest()==next(iter(r['case_sha256'].values()))
    assert case['radial'] and p['tangent']==p['coupling']==0 and p['spline'];assert all(p.get(k,0)==0 for k in ['shape_dx','shape_dy','half_x_delta','half_y_delta','radius_delta','cap_normal_gain']);assert 0<=p['core_x']<=1
    assert len(s.foreground_layers)==1 and len(s.vector_layers)==1 and not s.transmission_layers
    a.output.mkdir(parents=True,exist_ok=True);cache=a.output/'cache';cache.mkdir(exist_ok=True);frames=a.output/'frames';frames.mkdir(exist_ok=True)
    crop=[1228,585,1588,825];scale=3;foreground=copy.deepcopy(s.foreground_layers[0]);positions=[(int(round(18*math.sin(2*math.pi*i/89))),int(round(6*math.sin(4*math.pi*i/89))))for i in range(90)];unique=list(dict.fromkeys(positions))
    core=copy.deepcopy(p);core.pop('shadow',None);identity={'case':case,'parameters_except_shadow':core,'material':co.tolist(),'crop':crop,'scale':scale,'positions':positions};fingerprint=hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest();manifest=cache/'identity.json'
    if manifest.exists():assert json.loads(manifest.read_text())['sha256']==fingerprint,'Changed material/source/geometry cannot reuse cache'
    else:write(manifest,{'sha256':fingerprint,'identity':identity})
    # Conservative actual automatic finite Gaussian kernel enclosure: float OpenCV uses ~4sigma; ceil(4sigma)+1 bounds rounding.
    src_sigma=round(s.kernel_sigma(p)*4)/4;source_radius=math.ceil(4*src_sigma)+1;post_radius=(math.ceil(4*s.kernel_sigma(p,'post_sigma')*scale)+1)/scale
    coeff=[p[f'c{i}']for i in range(7)];rmax=max(1+post_radius/s.L,1-p['core_x']-min(0,min(coeff)),max(0,max(coeff)))
    cx,cy=case['center'];travel=[max(abs(t[k])for t in positions)for k in [0,1]];reach=s.L*rmax+source_radius+1
    bounds=[cx-travel[0]-reach,cy-travel[1]-reach,cx+travel[0]+reach,cy+travel[1]+reach];domain=case['background_estimate']['source_defined_domain'];assert bounds[0]>=domain[0]and bounds[1]>=domain[1]and bounds[2]<domain[2]and bounds[3]<domain[3],(bounds,domain)
    proof={'method':'Continuous circle/spline convex-hull bound, all positions, whole lens plus output-filter footprint; source4sigma+1 plus bilinear1','source_radius':source_radius,'post_footprint_native':post_radius,'normalized_query_radius_bound':rmax,'source_enclosure':bounds,'source_defined_domain':domain,'hidden_observed_fraction':0,'limits':'Finite automatic Gaussian-kernel enclosure and modeled RGB only; no physical/Apple motion validation'};write(a.output/'source-support.json',proof)
    # Extend only illustrative exterior beyond the original rectangle. All glass kernels stay in original hashed source by the proof above.
    assert case['background_estimate']['basis']==['1','(x-1408)/100','(y-705)/100'];yy,xx=np.mgrid[:s.source.shape[0],:s.source.shape[1]];X=np.stack([np.ones_like(xx),(xx-1408)/100,(yy-705)/100],-1);plane=(X@np.asarray(case['background_estimate']['coefficient_rgb'])).astype('float32');background=np.uint8(np.rint(np.clip(plane,0,1)*255)).astype('float32')/255;known=cv2.imread(str(a.inputs/'known.png'),0)>0;background[known]=s.source[known]
    for n,(dx,dy)in enumerate(unique):
        f=cache/f'{dx}_{dy}.npz'
        if f.exists():continue
        configure(s,case,crop,dx,dy,foreground);M=s.render(p,co,scale);alpha=s.shape_coverage(p,scale);np.savez_compressed(f,material=M,alpha=alpha);print('prepared',n+1,len(unique),dx,dy,flush=True)
    write(a.output/'prepared.json',{'fingerprint':fingerprint,'unique':len(unique),'frames':90,'width':1080,'height':720,'scale':3,'sampling':'2x2 per OUTPUT pixel','recipe_except_shadow':core,'render_cache_excludes_only_shadow':True})
    if a.prepare_only:return
    # Verify and preserve each encoded input frame; changes in shadow do not invalidate material caches.
    for i,(dx,dy)in enumerate(positions):
        configure(s,case,crop,dx,dy,foreground);data=np.load(cache/f'{dx}_{dy}.npz');alpha=data['alpha'];s.shape_coverage=lambda params,requested=1:alpha if requested==scale else (_ for _ in ()).throw(ValueError('Unexpected cached coverage scale'))
        C=composite(s,p,data['material'],scale,background_source=background);Image.fromarray(np.uint8(np.rint(np.clip(C,0,1)*255))).save(frames/f'{i:03d}.png')
    mp4=a.output/'motion.mp4';gif=a.output/'motion.gif'
    subprocess.run(['ffmpeg','-v','error','-y','-threads','2','-framerate','15','-i',str(frames/'%03d.png'),'-c:v','libx264','-crf','18','-pix_fmt','yuv420p',*ENCODING_COLOR_ARGS,'-movflags','+faststart',str(mp4)],check=True)
    for colors in [128,96,64,48,32]:
        subprocess.run(['ffmpeg','-v','error','-y','-threads','2','-framerate','15','-i',str(frames/'%03d.png'),'-filter_complex_threads','1','-filter_complex',f'[0:v]split[a][b];[a]palettegen=max_colors={colors}[p];[b][p]paletteuse=dither=bayer:bayer_scale=4','-loop','0',str(gif)],check=True)
        set_gif_timing(gif,6)
        if gif.stat().st_size<10_000_000:break
    assert gif.stat().st_size<10_000_000
    media={}
    for file in [mp4,gif]:
        probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-count_frames','-show_entries','stream=width,height,nb_read_frames,duration:format=duration','-of','json',str(file)]));assert int(probe['streams'][0]['nb_read_frames'])==90;media[file.name]={'bytes':file.stat().st_size,'sha256':sha(file),'probe':probe,'decoded_rgb_digest':rgb_digest(file)}
    write(a.output/'motion.json',{'recipe_sha256':sha(a.record),'cache_fingerprint':fingerprint,'frames':90,'fps':15,'duration':6,'raster':[1080,720],'sampling':'2x2 per OUTPUT pixel, recomputed field and ordinary source/post blur; unique repeated poses cached','source_support':proof,'files':media,'gif_palette_colors':colors,'limits':'Illustrative constant-shape translation over estimated affine panel; parent outline omitted, major body material mismatch retained. Not Apple captured motion or Android timing.'})

if __name__=='__main__':main()
