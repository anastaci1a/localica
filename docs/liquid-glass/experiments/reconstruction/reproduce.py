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
from render import geometry,lookup,illumination,fit_material,shade

ROOT=Path(__file__).resolve().parent
CASES={
 'media':dict(video='219',time=190,source_time=180,center=[960,542],half=[245,245],radius=245,crop=[620,220,1300,875],foreground=[[870,425,1088,666]]),
 'regular':dict(video='219',time=710,source_time=720,center=[960,542],half=[392,170],radius=170,crop=[475,285,1445,845],foreground=[[695,462,872,628],[1068,470,1215,618]]),
 'slider':dict(video='284',time=1092,source_time=None,center=[763.5,548.5],half=[118.5,56.5],radius=56.5,crop=[540,440,985,690],foreground=[]),
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
        registered[contaminated]=(219/255,)*3;valid[contaminated]=False
    exterior=(mask>0)&valid
    # Registration quality over a local non-glass band; exclude missing top source.
    band=np.zeros(mask.shape,bool);band[max(220,int(cy-hy-100)):int(cy+hy+100),int(cx-hx-150):int(cx+hx+150)]=True
    exterior&=band
    return registered,{'matrix':mat.tolist(),'sift_inliers':int(inliers.sum()),'exterior_mae_255':float(np.abs(registered-target)[exterior].mean()*255),'exterior_pixels':int(exterior.sum())}


def prepare(folder,name,case):
    target,meta=frame(folder,case['video'],case['time'])
    if name=='slider':
        # Track/background inferred from their uncovered portions. Hidden value
        # endpoint is estimated from the nearly registered core, not claimed known.
        source=target.copy();source[425:680,625:910]=np.median(target[405:425,625:910],axis=(0,1))
        cv2.line(source,(580,550),(1378,550),tuple(float(x) for x in np.median(target[544:556,1000:1250],axis=(0,1))),21,cv2.LINE_AA)
        cv2.line(source,(580,550),(719,550),tuple(float(x) for x in np.median(target[544:556,580:625],axis=(0,1))),21,cv2.LINE_AA)
        registration={'method':'analytical 21px round track; gray/blue/background measured outside lens; hidden fill endpoint 719px inferred from core','exterior_mae_255':None}
    else:
        source,source_meta=frame(folder,case['video'],case['source_time']);source,registration=register(source,target,case);registration['source_frame']=source_meta
    return source,target,meta,registration


def points(case,scale=1):
    x0,y0,x1,y1=case['crop'];ys,xs=np.mgrid[y0:y1:scale,x0:x1:scale].astype(np.float32)
    return xs,ys,xs-case['center'][0],ys-case['center'][1]


def masks(case,xs,ys,x,y):
    sd,nx,ny=geometry(x,y,case['half'],case['radius']);valid=sd<-.75
    for x0,y0,x1,y1 in case['foreground']:valid&=~((xs>=x0)&(xs<=x1)&(ys>=y0)&(ys<=y1))
    # Spatial blocks, not randomly interleaved pixels; one in four held out.
    held=((xs//32+3*(ys//32))%4==0)&valid
    return sd,valid&~held,held,valid


def parameters(v,model):
    if model=='cap':return dict(height=v[0],gap=v[1],band=v[2],ior=1.33),v[3],v[4]
    if model=='bounded':
        D=v[3]*16/9+(1-v[3])*64/27;T=v[3]*256/243+(1-v[3])*1024/729
        return dict(edge=v[0]*(.98-v[2])/max(D,T),band=v[1],core=v[2],rim=v[3],kind=2),v[4],v[5]
    if model=='field':return dict(edge=v[0],band=v[1],core=v[2],rim=v[3],kind=2),v[4],v[5]
    return dict(edge=.4,band=.55,core=.012,rim=.35,kind=2),v[0],v[1]


def fit(source,target,case,model,seed):
    xs,ys,x,y=points(case,2);sd,train,held,valid=masks(case,xs,ys,x,y)
    rng=np.random.default_rng(222);indices=np.flatnonzero(train);rng.shuffle(indices);indices=indices[:6500]
    px,py=x.flat[indices],y.flat[indices];tx,ty=xs.flat[indices],ys.flat[indices]
    expected=target[ty.astype(int),tx.astype(int)]
    distance=-sd.flat[indices];weights=np.where(distance<min(case['half'])*.20,1.3,1.)
    # Small fitting raster, then a full native-resolution final render/score.
    small=cv2.resize(source,(960,540),interpolation=cv2.INTER_AREA)
    blurs={}
    def sample(v):
        p,sigma,rimw=parameters(v,model)
        key=round(sigma*2)/2
        if key not in blurs:blurs[key]=cv2.GaussianBlur(small,(0,0),key/2) if key else small
        lx,ly=lookup(px,py,case['half'],case['radius'],p,'identity' if model=='identity' else 'field' if model in ('default','bounded') else model)
        # Resize pixel centers: original index x corresponds to (x+.5)/2-.5.
        a=cv2.remap(blurs[key],((lx+case['center'][0]+.5)/2-.5).astype(np.float32),((ly+case['center'][1]+.5)/2-.5).astype(np.float32),cv2.INTER_LINEAR).reshape(-1,3)
        light=illumination(px,py,case['half'],case['radius'],rimw)
        co,invalid=fit_material(a,light,expected,weights)
        pred=shade(a,light,co)
        error=float(np.mean(((pred-expected)*weights[:,None])**2)+invalid*.02)
        return error,co
    bounds={'field':[(0,3),(.03,1),(0,.18),(0,1),(0,35),(1,12)],'bounded':[(0,1),(.03,1),(0,.18),(0,1),(0,35),(1,12)],'cap':[(.01,1.2),(0,1.5),(.03,1),(0,35),(1,12)],'default':[(0,35),(1,12)],'identity':[(0,35),(1,12)]}[model]
    result=differential_evolution(lambda v:sample(v)[0],bounds,popsize=7,maxiter=65,tol=.001,seed=seed,polish=False,workers=1)
    result2=minimize(lambda v:sample(v)[0],result.x,method='Powell',bounds=bounds,options={'maxiter':50,'xtol':.002,'ftol':1e-6})
    v=result2.x if result2.fun<result.fun else result.x;error,co=sample(v)
    p,sigma,rimw=parameters(v,model)
    return dict(model=model,parameters=p,sigma=round(sigma*2)/2,rim_width=rimw,material=co.tolist(),fit_loss=error,evaluations=int(result.nfev+result2.nfev))


def render(source,case,fit):
    xs,ys,x,y=points(case);p=fit['parameters'];model=fit['model']
    lx,ly=lookup(x,y,case['half'],case['radius'],p,'identity' if model=='identity' else 'field' if model in ('default','bounded') else model)
    sigma=fit['sigma'];blur=cv2.GaussianBlur(source,(0,0),sigma) if sigma else source
    sample=cv2.remap(blur,(lx+case['center'][0]).astype(np.float32),(ly+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR)
    light=illumination(x,y,case['half'],case['radius'],fit['rim_width'])
    output=shade(sample,light,np.array(fit['material']))
    return output


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
    return result


def main():
    ap=argparse.ArgumentParser();ap.add_argument('sources',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--fit',action='store_true');ap.add_argument('--case',choices=CASES);args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
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
        for i,model in enumerate(['identity','default','field','cap','bounded']):
            dest=args.out/f'{name}-{model}.json'
            if args.fit or (not dest.exists() and model not in saved.get(name,{}).get('models',{})):
                print('Fitting',name,model,flush=True);f=fit(source,target,case,model,222+i);dest.write_text(json.dumps(f,indent=2))
            elif dest.exists():f=json.loads(dest.read_text())
            else:f=saved[name]['models'][model];dest.write_text(json.dumps(f,indent=2))
            output=render(source,case,f).astype(np.float32);f['scores']=scores(output,crop,case);fits[model]=f
            cv2.imwrite(str(args.out/f'{name}-{model}.png'),np.uint8(np.clip(output,0,1)*255)[:,:,::-1])
            print(name,model,'RMSE',round(f['scores']['all_glass']['rmse'],3),'held',round(f['scores']['held_blocks']['rmse'],3),'SSIM',round(f['scores']['ssim'],4),f['parameters'],flush=True)
        report={'case':case,'frame':meta,'registration':registration,'models':fits}
        (args.out/f'{name}-report.json').write_text(json.dumps(report,indent=2))

if __name__=='__main__':main()
