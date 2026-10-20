"""HD Apple reference reconstruction. See ../../reconstruction.md for interpretation.

python reproduce.py /private/apple-matches /private/output [--fit] [--case media]
Dependencies: numpy, scipy, opencv-python-headless, Pillow.
Original source videos stay private. Published excerpts require their attribution.
"""
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS','1')
from pathlib import Path
import argparse,json,hashlib
import cv2,numpy as np
from scipy.optimize import differential_evolution,minimize
from render import case_geometry,lookup,illumination,fit_material,shade,spline_basis
from background import page_source,slider_source,paper

ROOT=Path(__file__).resolve().parent
CURVATURE_MODELS=['potential','tensor','chromatic','surface','reflection','spectral','colored']
CASES={
 'media':dict(video='219',time=190,source_time=180,center=[960,542],half=[245,245],radius=245,crop=[620,220,1300,875],foreground=[[870,425,1088,666]],sigma_max=.5),
 'regular':dict(video='219',time=710,source_time=720,center=[960,542],half=[392,170],radius=170,crop=[475,285,1445,845],foreground=[[695,462,872,628],[1068,470,1215,618]],sigma_max=8,blur_transition=30),
 'slider':dict(video='284',time=1092,source_time=None,center=[763.5,548.5],half=[118.5,56.5],radius=35.65,exponent=2.5,crop=[540,440,985,690],foreground=[],sigma_max=0),
 'regular-light':dict(video='219',time=915,source_time=None,center=[960,541],half=[245,106.5],radius=106.5,crop=[610,350,1310,730],foreground=[],sigma_max=0,models=['identity','default']),
 'regular-dark':dict(video='219',time=920,source_time=None,center=[960,541],half=[245,106.5],radius=106.5,crop=[610,350,1310,730],foreground=[],sigma_max=0,models=['identity','default']),
}


def frame(folder,video,time):
    cap=cv2.VideoCapture(str(folder/f'{video}-hd.mp4'));cap.set(cv2.CAP_PROP_POS_MSEC,time*1000);ok,image=cap.read()
    if not ok:raise RuntimeError((video,time))
    metadata={'requested_seconds':time,'presentation_seconds':cap.get(cv2.CAP_PROP_POS_MSEC)/1000,'frame_index':int(cap.get(cv2.CAP_PROP_POS_FRAMES))-1,'native_size':[image.shape[1],image.shape[0]]};cap.release()
    assert image.shape[:2]==(1080,1920),'Only original HD inputs; no upsampled replacements'
    return image[:,:,::-1].astype(np.float32)/255,metadata


def register(source,target,case):
    mask=np.full(source.shape[:2],255,np.uint8);cx,cy=case['center'];hx,hy=case['half']
    mask[int(cy-hy-70):int(cy+hy+80),int(cx-hx-70):int(cx+hx+70)]=0
    sift=cv2.SIFT_create(nfeatures=10000)
    a=cv2.cvtColor(np.uint8(source*255),cv2.COLOR_RGB2GRAY);b=cv2.cvtColor(np.uint8(target*255),cv2.COLOR_RGB2GRAY)
    ka,da=sift.detectAndCompute(a,mask);kb,db=sift.detectAndCompute(b,mask)
    pairs=[x for x,y in cv2.BFMatcher().knnMatch(da,db,k=2) if x.distance<.7*y.distance]
    p=np.array([ka[m.queryIdx].pt for m in pairs]);q=np.array([kb[m.trainIdx].pt for m in pairs])
    mat,inliers=cv2.estimateAffinePartial2D(p,q,method=cv2.RANSAC,ransacReprojThreshold=1,maxIters=5000)
    assert inliers.sum()>20
    registered=cv2.warpAffine(source,mat,(1920,1080),flags=cv2.INTER_LINEAR,borderValue=(219/255,)*3)
    valid=cv2.warpAffine(np.ones(source.shape[:2],np.uint8),mat,(1920,1080))>0
    if case['source_time']==720:
        covered=np.zeros(source.shape[:2],np.uint8);covered[350:790,490:1420]=1
        contaminated=cv2.warpAffine(covered,mat,(1920,1080))>0
        registered,field_evidence=page_source(registered,target,contaminated)
        valid[contaminated]=False
    exterior=(mask>0)&valid
    # Registration quality over a local non-glass band; exclude missing top source.
    band=np.zeros(mask.shape,bool);band[max(220,int(cy-hy-100)):int(cy+hy+100),int(cx-hx-150):int(cx+hx+150)]=True
    exterior&=band
    evidence={'matrix':mat.tolist(),'sift_inliers':int(inliers.sum()),'exterior_mae_255':float(np.abs(registered-target)[exterior].mean()*255),'exterior_pixels':int(exterior.sum())}
    if case['source_time']==720:evidence['page_field']=field_evidence
    return registered,evidence


def prepare(folder,name,case):
    target,meta=frame(folder,case['video'],case['time'])
    if name=='regular-light':
        excluded=np.zeros(target.shape[:2],bool);excluded[340:730,600:1320]=True;excluded[700:]=True
        source,evidence=paper(target,excluded);source[739:]=0
        registration={'method':'smooth page field from uncovered light gradient; no optical-detail calibration','page_field':evidence,'exterior_mae_255':None}
    elif name=='regular-dark':
        source=np.zeros_like(target);source[:349]=target[:349]
        registration={'method':'black field observed outside control; no optical-detail calibration','exterior_mae_255':0}
    elif name=='slider':
        # Track/background inferred from their uncovered portions. Hidden value
        # endpoint is estimated from the nearly registered core, not claimed known.
        uncovered,source_meta=frame(folder,'284',1090.5)
        source,evidence=slider_source(target,uncovered)
        registration={'method':'page field from resting control frame before active lens appears; analytical antialiased 21px round track; hidden fill endpoint 719px inferred from core','source_frame':source_meta,'page_field':evidence,'exterior_mae_255':None}
    else:
        source,source_meta=frame(folder,case['video'],case['source_time']);source,registration=register(source,target,case);registration['source_frame']=source_meta
    return source,target,meta,registration


def points(case,scale=1):
    x0,y0,x1,y1=case['crop'];ys,xs=np.mgrid[y0:y1:scale,x0:x1:scale].astype(np.float32)
    return xs,ys,xs-case['center'][0],ys-case['center'][1]


def masks(case,xs,ys,x,y):
    sd,nx,ny=case_geometry(case,x,y);valid=sd<-.75
    for x0,y0,x1,y1 in case['foreground']:valid&=~((xs>=x0)&(xs<=x1)&(ys>=y0)&(ys<=y1))
    # Spatial blocks, not randomly interleaved pixels; one in four held out.
    held=((xs//32+3*(ys//32))%4==0)&valid
    return sd,valid&~held,held,valid


def training_samples(case):
    """Native-pixel subset; a fixed every-second-pixel lattice misses thin rims."""
    xs,ys,x,y=points(case);sd,train,_,_=masks(case,xs,ys,x,y)
    indices=np.flatnonzero(train);np.random.default_rng(222).shuffle(indices);indices=indices[:6500]
    distance=-sd.flat[indices]
    weights=np.where(distance<4,2.5,np.where(distance<min(case['half'])*.20,1.3,1.))
    return xs,ys,x,y,indices,weights


def parameters(v,model):
    if model=='spline':return dict(coefficients=list(v[:7]),core_x=v[7],core_y=v[8]),v[9],v[10]
    if model=='cap':return dict(height=v[0],gap=v[1],band=v[2],ior=1.33),v[3],v[4]
    if model=='bounded':
        D=v[3]*16/9+(1-v[3])*64/27;T=v[3]*256/243+(1-v[3])*1024/729
        return dict(edge=v[0]*(.98-v[2])/max(D,T),band=v[1],core=v[2],rim=v[3],kind=2),v[4],v[5]
    if model=='field':return dict(edge=v[0],band=v[1],core=v[2],rim=v[3],kind=2),v[4],v[5]
    return dict(edge=.4,band=.55,core=.012,rim=.35,kind=2),v[0],v[1]


def fit(source,target,case,model,seed,initial=None):
    xs,ys,x,y,indices,weights=training_samples(case)
    px,py=x.flat[indices],y.flat[indices];tx,ty=xs.flat[indices],ys.flat[indices]
    boundary=case_geometry(case,px,py)
    basis=spline_basis(-boundary[0],case['radius']) if model=='spline' else None
    expected=target[ty.astype(int),tx.astype(int)]
    # Native sampling during fitting too: downsampling itself softened the clear
    # media texture and made a low-blur fit impossible to inspect correctly.
    blurs={}
    def sample(v):
        transition=v[-1] if case.get('blur_transition') else None
        if transition is not None:v=v[:-1]
        p,sigma,rimw=parameters(v,model)
        if model=='spline':p['_basis']=basis
        key=round(sigma*4)/4
        if key not in blurs:blurs[key]=cv2.GaussianBlur(source,(0,0),key) if key else source
        lx,ly=lookup(px,py,case['half'],case['radius'],p,'identity' if model=='identity' else 'field' if model in ('default','bounded') else model,boundary,case.get('exponent',0))
        a=cv2.remap(blurs[key],(lx+case['center'][0]).astype(np.float32),(ly+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR).reshape(-1,3)
        if case.get('blur_transition'):
            clear=cv2.remap(source,(lx+case['center'][0]).astype(np.float32),(ly+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR).reshape(-1,3)
            blend=np.clip(-boundary[0]/transition,0,1);blend=blend**3*(blend*(6*blend-15)+10)
            a=clear+(a-clear)*blend[:,None]
        light=illumination(px,py,case['half'],case['radius'],rimw,boundary)
        co,invalid=fit_material(a,light,expected,weights)
        pred=shade(a,light,co)
        error=float(np.mean(((pred-expected)*weights[:,None])**2)+invalid*.02)
        return error,co
    bounds={'spline':[(0,2.5)]*7+[(0,.3),(0,.65),(0,35),(1,12)],'field':[(0,3),(.03,1),(0,.18),(0,1),(0,35),(1,12)],'bounded':[(0,1),(.03,1),(0,.18),(0,1),(0,35),(1,12)],'cap':[(.01,1.2),(0,1.5),(.03,1),(0,35),(1,12)],'default':[(0,35),(1,12)],'identity':[(0,35),(1,12)]}[model]
    bounds[-2]=(0,max(case.get('sigma_max',35),1e-6))
    if case.get('blur_transition'):bounds.append((2,30))
    start=None
    if initial and model!='spline':
        p=initial['parameters'];sigma=initial['sigma'];rimw=initial['rim_width']
        if model=='cap':start=[p['height'],p['gap'],p['band'],sigma,rimw]
        elif model in ('field','bounded'):
            edge=p['edge']
            if model=='bounded':edge/=((.98-p['core'])/(p['rim']*16/9+(1-p['rim'])*64/27))
            start=[edge,p['band'],p['core'],p['rim'],sigma,rimw]
        else:start=[sigma,rimw]
        if case.get('blur_transition'):start.append(initial.get('blur_transition',2))
        start=np.clip(start,np.array(bounds)[:,0],np.array(bounds)[:,1])
    result=differential_evolution(lambda v:sample(v)[0],bounds,popsize=8 if model=='spline' else 7,maxiter=120 if model=='spline' else 65,tol=.001,seed=seed,polish=False,workers=1,x0=start)
    result2=minimize(lambda v:sample(v)[0],result.x,method='Powell',bounds=bounds,options={'maxiter':50,'xtol':.002,'ftol':1e-6})
    v=result2.x if result2.fun<result.fun else result.x;error,co=sample(v)
    transition=v[-1] if case.get('blur_transition') else None
    p,sigma,rimw=parameters(v[:-1] if transition is not None else v,model)
    return dict(model=model,parameters=p,sigma=round(sigma*4)/4,rim_width=rimw,blur_transition=transition,material=co.tolist(),fit_loss=error,evaluations=int(result.nfev+result2.nfev))


def render_grid(source,blur,case,fit,x,y):
    p=fit['parameters'];model=fit['model']
    boundary=case_geometry(case,x,y)
    lx,ly=lookup(x,y,case['half'],case['radius'],p,'identity' if model=='identity' else 'field' if model in ('default','bounded') else model,boundary,case.get('exponent',0))
    sample=sample_backdrop(source,blur,case,fit,x,y,boundary,lx,ly)
    light=model_light(x,y,case,fit,boundary)
    output=shade(sample,light,np.array(fit['material']))
    return output,boundary[0]<=0


def sample_backdrop(source,blur,case,fit,x,y,boundary,lx=None,ly=None):
    p=fit['parameters'];model=fit['model']
    if lx is None:
        lx,ly=lookup(x,y,case['half'],case['radius'],p,model,boundary,case.get('exponent',0))
    def at(image,X,Y):
        return cv2.remap(image,(X+case['center'][0]).astype(np.float32),(Y+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR)
    if model=='chromatic':
        samples=[]
        for channel,wavelength in enumerate([.650,.550,.450]):
            factor=1+p['dispersion']*(wavelength**-2-.550**-2)
            samples.append(at(blur,x+(lx-x)*factor,y+(ly-y)*factor)[...,channel])
        sample=np.stack(samples,axis=-1)
    elif model=='spectral':
        from curvature import surface,snell_map
        z,Nx,Ny,Nz=surface(x,y,case['half'],case['radius'],p,case.get('exponent',0))
        # Three representative wavelengths, first-order Cauchy-law RGB proxy.
        # Not a spectral/colorimetric renderer. B=0 recovers monochromatic rays.
        samples=[]
        for channel,wavelength in enumerate([.650,.550,.450]):
            ior=p.get('ior',1.33)+p['dispersion']*(wavelength**-2-.550**-2)
            X,Y=snell_map(x,y,z,Nx,Ny,Nz,ior,min(case['half'])*p['gap'])
            X=np.where(boundary[0]<=0,X,x);Y=np.where(boundary[0]<=0,Y,y)
            samples.append(at(blur,X,Y)[...,channel])
        sample=np.stack(samples,axis=-1)
    else:sample=at(blur,lx,ly)
    if case.get('blur_transition'):
        clear=at(source,lx,ly)
        blend=np.clip(-boundary[0]/fit.get('blur_transition',case['blur_transition']),0,1);blend=blend**3*(blend*(6*blend-15)+10)
        sample=clear+(sample-clear)*blend[...,None]
    return sample


def model_light(x,y,case,fit,boundary):
    if fit['model'] in ('reflection','spectral','colored'):
        from curvature import surface,reflection_basis,colored_reflection_basis
        _,nx,ny,nz=surface(x,y,case['half'],case['radius'],fit['parameters'],case.get('exponent',0))
        basis=colored_reflection_basis if fit['model']=='colored' else reflection_basis
        return basis(x,y,case['half'],fit['parameters'],(nx,ny,nz),boundary)
    return illumination(x,y,case['half'],case['radius'],fit['rim_width'],boundary)


def render(source,case,fit,samples=4):
    """Integrate 4×4 subpixels in bounded-memory strips, including coverage.

    Fitting uses native pixel centers; final scores use this reconstructed image.
    This is raster antialiasing, independent of the material Gaussian blur.
    """
    x0,y0,x1,y1=case['crop'];sigma=fit['sigma']
    blur=cv2.GaussianBlur(source,(0,0),sigma) if sigma else source
    result=np.zeros((y1-y0,x1-x0,3),np.float32)
    for top in range(y0,y1,24):
        bottom=min(top+24,y1)
        yy,xx=np.mgrid[top:bottom:1/samples,x0:x1:1/samples]
        xx=xx-.5+.5/samples-case['center'][0];yy=yy-.5+.5/samples-case['center'][1]
        color,inside=render_grid(source,blur,case,fit,xx,yy)
        shape=(bottom-top,samples,x1-x0,samples)
        weight=inside.reshape(shape).sum(axis=(1,3))
        premul=(color*inside[...,None]).reshape((*shape,3)).sum(axis=(1,3))
        result[top-y0:bottom-y0]=premul/np.maximum(weight,1)[...,None]
    return result


def scores(pred,target,case):
    xs,ys,x,y=points(case);sd,train,held,valid=masks(case,xs,ys,x,y);result={}
    edge=-sd<min(case['half'])*.2
    for label,mask in [('train',train),('held_blocks',held),('all_glass',valid),('rim',valid&edge),('interior',valid&~edge)]:
        diff=(pred-target)[mask]*255
        result[label]={'pixels':int(mask.sum()),'rmse':float(np.sqrt(np.mean(diff**2))),'mae':float(np.mean(np.abs(diff)))}
    # Structural comparison of RGB luminance-like mean; erode masks to exclude
    # foreground/boundary neighborhoods from the 11px SSIM window.
    A=pred.mean(2)*255;B=target.mean(2)*255
    mean=lambda a:cv2.GaussianBlur(a,(11,11),1.5)
    ma,mb=mean(A),mean(B);va=mean(A*A)-ma*ma;vb=mean(B*B)-mb*mb;cov=mean(A*B)-ma*mb
    ssim=((2*ma*mb+6.5025)*(2*cov+58.5225))/((ma*ma+mb*mb+6.5025)*(va+vb+58.5225))
    safe=cv2.erode(valid.astype(np.uint8),np.ones((11,11),np.uint8))>0
    result['ssim']=float(ssim[safe].mean())
    ga=np.hypot(cv2.Sobel(A,cv2.CV_32F,1,0),cv2.Sobel(A,cv2.CV_32F,0,1));gb=np.hypot(cv2.Sobel(B,cv2.CV_32F,1,0),cv2.Sobel(B,cv2.CV_32F,0,1))
    result['gradient_mae']=float(np.abs(ga-gb)[safe].mean())
    # One final-render training objective for all models, including searches
    # with different subpixel counts. Never compare their search losses directly.
    _,_,_,_,indices,weights=training_samples(case)
    diff=(pred-target).reshape(-1,3)[indices]
    result['selection_loss']=float(np.mean((diff*weights[:,None])**2))
    return result


def main():
    ap=argparse.ArgumentParser();ap.add_argument('sources',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--fit',action='store_true');ap.add_argument('--case',choices=CASES);ap.add_argument('--model',choices=['identity','default','field','cap','bounded','spline']+CURVATURE_MODELS);args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((ROOT/'sources.json').read_text())
    videos={case['video'] for name,case in CASES.items() if not args.case or name==args.case}
    for video in videos:
        with (args.sources/f'{video}-hd.mp4').open('rb') as handle:digest=hashlib.file_digest(handle,'sha256').hexdigest()
        assert digest==manifest[video]['sha256'],f'Unexpected source bytes: {video}'
    saved=json.loads((ROOT/'results.json').read_text()) if (ROOT/'results.json').exists() else {}
    for name,case in CASES.items():
        if args.case and name!=args.case:continue
        source,target,meta,registration=prepare(args.sources,name,case)
        x0,y0,x1,y1=case['crop'];crop=target[y0:y1,x0:x1]
        cv2.imwrite(str(args.out/f'{name}-source.png'),np.uint8(np.clip(source,0,1)*255)[:,:,::-1]);cv2.imwrite(str(args.out/f'{name}-target.png'),np.uint8(target*255)[:,:,::-1])
        fits={}
        local=json.loads((args.out/f'{name}-report.json').read_text()) if (args.out/f'{name}-report.json').exists() else {}
        models=list(dict.fromkeys(case.get('models',['identity','default','field','cap','bounded','spline'])+list(saved.get(name,{}).get('models',{}))+list(local.get('models',{}))))
        for i,model in enumerate(models):
            dest=args.out/f'{name}-{model}.json'
            if (args.fit and (not args.model or args.model==model)) or (not dest.exists() and model not in saved.get(name,{}).get('models',{})):
                print('Fitting',name,model,flush=True)
                fitter=fit
                if model in CURVATURE_MODELS:
                    from refine import fit as fitter
                f=fitter(source,target,case,model,222+i,saved.get(name,{}).get('models',{}).get(model));dest.write_text(json.dumps(f,indent=2))
            elif dest.exists():f=json.loads(dest.read_text())
            else:f=saved[name]['models'][model];dest.write_text(json.dumps(f,indent=2))
            output=render(source,case,f).astype(np.float32);f['scores']=scores(output,crop,case);fits[model]=f
            cv2.imwrite(str(args.out/f'{name}-{model}.png'),np.uint8(np.clip(output,0,1)*255)[:,:,::-1])
            print(name,model,'RMSE',round(f['scores']['all_glass']['rmse'],3),'held',round(f['scores']['held_blocks']['rmse'],3),'SSIM',round(f['scores']['ssim'],4),f['parameters'],flush=True)
        report={'case':case,'frame':meta,'registration':registration,'models':fits}
        (args.out/f'{name}-report.json').write_text(json.dumps(report,indent=2))

if __name__=='__main__':main()
