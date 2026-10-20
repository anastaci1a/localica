"""Construct a switch-track backdrop from exposed geometry/color evidence.

python switch_source.py /private/details.mp4 /private/new-switch-inputs

The hidden track is an explicit symmetric-capsule estimate, not recovered
photographic pixels. A separate conservative mask marks copied direct evidence.
"""
from pathlib import Path
import argparse,hashlib,json
import cv2,numpy as np
from scipy.optimize import least_squares
from render import geometry

VIDEO_SHA='5b5ad18c8f0ff74d6fd28e5340c2dca04214c8a370744066c017e8487ce900db'
# Approximate image poses only; optical and silhouette refinement is separate.
POSES={9.4:dict(center=[1040.,536.5],half=[326.,216.5]),10.5:dict(center=[864.,536.],half=[306.,200.])}


def frame(cap,seconds):
    index=round(seconds*60);cap.set(cv2.CAP_PROP_POS_FRAMES,index);ok,BGR=cap.read()
    assert ok and BGR.shape==(1080,1920,3)
    return BGR[:,:,::-1],dict(requested_seconds=seconds,frame_index=index,decoded_seconds=index/60)


def background(im):return np.median(im[100:250,200:500],axis=(0,1))/255


def color(im,box):
    a,b,c,d=box;v=im[b:d,a:c].reshape(-1,3)/255
    return np.median(v,axis=0),np.percentile(v,[5,95],axis=0).tolist()


def fit_track(im,on):
    B=background(im)
    C,_=color(im,[675,510,735,570] if on else [1200,510,1250,570])
    rgb=im.astype(np.float32)/255
    strength=(rgb[:,:,1]-rgb[:,:,0]) if on else (B-rgb).mean(2)
    threshold=(C[1]-C[0])/2 if on else float((B-C).mean()/2)
    mask=(strength>threshold).astype(np.uint8);mask[:350]=0;mask[735:]=0;mask[:,:550]=0;mask[:,1380:]=0
    contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_NONE)
    contour=max(contours,key=cv2.contourArea).reshape(-1,2).astype(float)
    assert len(contour)>1200
    def residual(v):
        cx,cy,hx,hy=v
        return geometry(contour[:,0]-cx,contour[:,1]-cy,[hx,hy],hy)[0]
    fit=least_squares(residual,[960,542,340,152],bounds=([945,530,325,140],[975,552,355,162]),loss='soft_l1',f_scale=.5)
    return fit.x,dict(parameters=fit.x.tolist(),contour_pixels=len(contour),contour_rmse=float(np.sqrt(np.mean(residual(fit.x)**2))),method='Outer half-contrast contour of the resting track; fit symmetric capsule, four pose/size parameters.')


def track_coverage(shape,H,W):
    cx,cy,hx,hy=shape;coverage=np.zeros((H,W),np.float32)
    left,right=max(0,int(cx-hx-2)),min(W,int(cx+hx+3));top,bottom=max(0,int(cy-hy-2)),min(H,int(cy+hy+3))
    Y,X=np.mgrid[top:bottom,left:right].astype(float);small=np.zeros(X.shape,np.float32)
    for dy in (np.arange(8)+.5)/8-.5:
        for dx in (np.arange(8)+.5)/8-.5:
            small+=geometry(X+dx-cx,Y+dy-cy,[hx,hy],hy)[0]<=0
    coverage[top:bottom,left:right]=small/64
    return coverage


def active_track(im,pose,rest_shape,C,on):
    """Measure the exposed end cap; mirror it about the resting track center."""
    B=background(im);rgb=im.astype(np.float32)/255
    strength=(rgb[:,:,1]-rgb[:,:,0]) if on else (B-rgb).mean(2)
    threshold=(C[1]-C[0])/2 if on else float((B-C).mean()/2)
    mask=(strength>threshold).astype(np.uint8);mask[:350]=0;mask[735:]=0;mask[:,:550]=0;mask[:,1380:]=0
    contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_NONE)
    pts=np.concatenate([c.reshape(-1,2) for c in contours]).astype(float)
    cx,cy=pose['center'];hx,hy=pose['half']
    sd=geometry(pts[:,0]-cx,pts[:,1]-cy,[hx,hy],hy)[0]
    track_cx,track_cy,track_hx,R=rest_shape
    visible=(pts[:,0]<track_cx) if on else (pts[:,0]>track_cx)
    pts=pts[visible&(sd>15)];assert len(pts)>150
    cap_cx=track_cx-(track_hx-R) if on else track_cx+(track_hx-R)
    def residual(v):return np.hypot(pts[:,0]-v[0],pts[:,1]-v[1])-v[2]
    fit=least_squares(residual,[cap_cx,track_cy,R],bounds=([cap_cx-20,track_cy-10,R-10],[cap_cx+20,track_cy+10,R+10]),loss='soft_l1',f_scale=.5)
    cap_x,new_cy,new_R=fit.x;new_hx=abs(cap_x-track_cx)+new_R
    result=np.array([track_cx,new_cy,new_hx,new_R])
    return result,dict(circle=fit.x.tolist(),visible_contour_pixels=len(pts),contour_rmse=float(np.sqrt(np.mean(residual(fit.x)**2))),method='Active-frame exposed end-cap circle; hidden end mirrored about the measured resting track center.')


def main():
    ap=argparse.ArgumentParser();ap.add_argument('video',type=Path);ap.add_argument('out',type=Path);a=ap.parse_args()
    assert hashlib.sha256(a.video.read_bytes()).hexdigest()==VIDEO_SHA,'Changed frozen Apple source'
    assert not a.out.exists(),'Preserve existing source construction';a.out.mkdir(parents=True)
    cap=cv2.VideoCapture(str(a.video));assert abs(cap.get(cv2.CAP_PROP_FPS)-60)<1e-6
    on,_=frame(cap,9.0);off,_=frame(cap,10.0)
    va,ra=fit_track(on,True);vb,rb=fit_track(off,False);shape=(va+vb)/2
    Y,X=np.mgrid[:1080,:1920];records=[]
    for seconds,pose in POSES.items():
        target,time=frame(cap,seconds);B=background(target)
        C,range90=color(target,[650,510,690,570] if seconds==9.4 else [1200,510,1250,570])
        active_shape,active_measurement=active_track(target,pose,va if seconds==9.4 else vb,C,seconds==9.4)
        A=track_coverage(active_shape,1080,1920)
        modeled=B+(C-B)*A[...,None]
        # Copy only distant exposed pixels. A broad exclusion also avoids the
        # colored halo/shadow; all nearer source RGB stays explicitly estimated.
        cx,cy=pose['center'];hx,hy=pose['half']
        sd=geometry(X-cx,Y-cy,[hx,hy],hy)[0]
        observed=sd>max(180,.8*hy)
        source=np.where(observed[...,None],target/255,modeled)
        tag=f'{seconds:.3f}'
        images={'source':np.rint(np.clip(source,0,1)*255).astype(np.uint8)[:,:,::-1],
            'target':target[:,:,::-1],'known':np.full((1080,1920),255,np.uint8),'observed':observed.astype(np.uint8)*255}
        hashes={}
        for kind,image in images.items():
            filename=f'{kind}-{tag}.png';assert cv2.imwrite(str(a.out/filename),image)
            hashes[filename]=hashlib.sha256((a.out/filename).read_bytes()).hexdigest()
        records.append(dict(**time,pose=pose,track_pose=active_shape.tolist(),exposed_track_fit=active_measurement,track_color=C.tolist(),track_color_5_95=range90,background_color=B.tolist(),files=hashes))
    cap.release()
    report=dict(video_sha256=VIDEO_SHA,track_pose=shape.tolist(),resting_track_fits=[ra,rb],references=records,
        method='Symmetric vector capsule fitted from resting-track outer contours; active-frame color measured from exposed track. Distant unoccluded RGB is copied; hidden/halo-adjacent input is modeled.',
        limits='Track symmetry, hidden color uniformity and separation from the thumb shadow are assumptions. Conditional rendering scores are not proof of complete photographic background recovery or proprietary Apple optics.')
    (a.out/'construction.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)


if __name__=='__main__':main()
