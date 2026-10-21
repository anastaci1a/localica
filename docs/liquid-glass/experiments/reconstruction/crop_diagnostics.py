"""Advisory, registered crop diagnostics; never an acceptance-score replacement.

No crop alignment, color matching or input interpolation is performed. RGB is
encoded 0..1. Edge summaries describe image gradients, not recovered optics.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np


def edge_summary(rgb):
    """Channel gradient centroids and effective widths, normalized by crop size.

    Participation width (sum(g)^2/sum(g^2)) increases when a single edge spreads.
    With several edges it also increases with their number: report profiles for
    inspection rather than treating this as a general physical blur estimator.
    """
    h,w,_=rgb.shape
    axes={}
    for axis,name,size in [(1,'x',w),(0,'y',h)]:
        gradient=np.abs(np.diff(rgb,axis=axis))
        profile=gradient.mean(axis=1-axis)
        coordinates=(np.arange(size-1)+1)/size
        mass=profile.sum(axis=0)
        center=np.divide((profile*coordinates[:,None]).sum(axis=0),mass,
                         out=np.full(3,np.nan),where=mass>1e-12)
        width=np.divide(mass**2,(profile**2).sum(axis=0)*size,
                        out=np.zeros(3),where=(profile**2).sum(axis=0)>1e-12)
        axes[name]={'centroid_rgb':[float(v) if np.isfinite(v) else None for v in center],
                    'effective_width_rgb':width.tolist(),
                    'gradient_mass_rgb':mass.tolist(),
                    'profile_rgb':profile.tolist()}
    return axes


def diagnostics(target,candidate,rectangles):
    if target.shape!=candidate.shape or target.ndim!=3 or target.shape[-1]!=3:
        raise ValueError('Use registered equal-size RGB images; no resizing or alignment')
    if not np.isfinite(target).all() or not np.isfinite(candidate).all():
        raise ValueError('Non-finite input')
    h,w,_=target.shape;result={}
    for name,box in rectangles.items():
        if len(box)!=4 or not all(np.isfinite(v) and 0<=v<=1 for v in box):
            raise ValueError('Crop bounds must be normalized [x0,y0,x1,y1]')
        x0,y0,x1,y1=box
        if x0>=x1 or y0>=y1:raise ValueError('Empty/reversed crop')
        a,b,c,d=round(x0*w),round(y0*h),round(x1*w),round(y1*h)
        T=target[b:d,a:c];C=candidate[b:d,a:c]
        if min(T.shape[:2])<2:raise ValueError('Crop needs at least two pixels per axis')
        delta=C-T;luma=np.array([.2126,.7152,.0722]);TL=T@luma;CL=C@luma
        chroma=(C-CL[...,None])-(T-TL[...,None])
        te,ce=edge_summary(T),edge_summary(C)
        result[name]={'bounds_normalized':box,'bounds_pixels':[a,b,c,d],
            'rgb_rmse_255':float(np.sqrt(np.mean(delta**2))*255),
            'channel_rmse_255':(np.sqrt(np.mean(delta**2,axis=(0,1)))*255).tolist(),
            'luma_rmse_255':float(np.sqrt(np.mean((CL-TL)**2))*255),
            'chroma_rmse_255':float(np.sqrt(np.mean(chroma**2))*255),
            'mean_signed_rgb_255':(delta.mean(axis=(0,1))*255).tolist(),
            'pixel_error_p95_255':float(np.quantile(np.sqrt(np.mean(delta**2,axis=2)),.95)*255),
            'target_edges':te,'candidate_edges':ce}
    return result


def main():
    from PIL import Image
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('target',type=Path);ap.add_argument('candidate',type=Path)
    ap.add_argument('crops',type=Path);ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args()
    arrays=[np.asarray(Image.open(p).convert('RGB'),dtype=float)/255 for p in [args.target,args.candidate]]
    report={'advisory_only':True,'color_space':'encoded RGB; normalized crop coordinates',
            'sha256':{key:hashlib.sha256(getattr(args,key).read_bytes()).hexdigest()
                      for key in ('target','candidate','crops')},
            'limitations':'Gradient centroids aggregate all edges. Effective width includes edge count. These metrics cannot identify dispersion versus lighting or source error.',
            'crops':diagnostics(*arrays,json.loads(args.crops.read_text()))}
    args.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
    print(json.dumps({name:row['rgb_rmse_255'] for name,row in report['crops'].items()}))


if __name__=='__main__':main()
