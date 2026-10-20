"""Optional directional reflected field, separate from transmission scattering.

The incident environment is approximated by lightly filtered, unrefracted
source RGB. This is an empirical proxy, not an identified Apple reflection map.
All distances and the environment kernel are normalized by each pose's L.
"""
import cv2,numpy as np


def layers(study,p,scale=1,row=0,bottom=None):
    q=p['grazing_reflection'];x0,y0,x1,y1=study.crop
    if bottom is None:bottom=(y1-y0)*scale
    yy,xx=np.mgrid[row:bottom,:((x1-x0)*scale)]
    X=x0-.5+(xx+.5)/scale;Y=y0-.5+(yy+.5)/scale
    x=(X-study.case['center'][0])/study.L;y=(Y-study.case['center'][1])/study.L
    *_,(sd,nx,ny)=study.shape(x,y,p)
    direction=np.asarray(q.get('direction',[0.,1.]),float)
    assert np.isfinite(direction).all() and abs(np.linalg.norm(direction)-1)<1e-9
    strength,width,power=q['strength'],q['width'],q['angular_power']
    assert 0<=strength<=1 and width>0 and power>0 and q['source_sigma']>=0
    u=np.clip(np.maximum(-sd,0)/width,0,1);smooth=u**3*(u*(6*u-15)+10)
    weight=strength*(1-smooth)*np.maximum(nx*direction[0]+ny*direction[1],0)**power
    sigma=q['source_sigma']*study.L;key=('grazing_environment',sigma)
    if key not in study.cache:study.cache[key]=cv2.GaussianBlur(study.source,(0,0),sigma) if sigma>.001 else study.source
    study.cache.move_to_end(key)
    while len(study.cache)>8:study.cache.popitem(last=False)
    environment=cv2.remap(study.cache[key],(X+study.backdrop_shift[0]).astype(np.float32),
                           (Y+study.backdrop_shift[1]).astype(np.float32),cv2.INTER_LINEAR)
    return weight,environment


def apply(study,p,prediction,scale=1):
    if 'grazing_reflection' not in p:return prediction
    out=np.empty_like(prediction)
    # Avoid full-frame geometric and RGB temporaries in HQ motion exports.
    rows=max(1,131072//prediction.shape[1])
    for row in range(0,prediction.shape[0],rows):
        bottom=min(row+rows,prediction.shape[0]);weight,environment=layers(study,p,scale,row,bottom)
        f=weight[...,None];out[row:bottom]=prediction[row:bottom]*(1-f)+environment*f
    return out


def support(study,p,defined,observed,shift=0.):
    from source_support import fraction
    weight,_=layers(study,p);active=(weight>1e-8)&study.valid
    if not active.any():return {k:1. for k in ['reflection_source_defined_fraction','reflection_gaussian_3sigma_defined_fraction','reflection_source_observed_fraction','reflection_gaussian_3sigma_observed_fraction']}
    x0,y0,x1,y1=study.crop;Y,X=np.mgrid[y0:y1,x0:x1].astype(np.float32)
    radius=int(np.ceil(3*p['grazing_reflection']['source_sigma']*study.L))
    def safe(mask):return cv2.erode(mask,np.ones((2*radius+1,2*radius+1),np.uint8),borderType=cv2.BORDER_CONSTANT,borderValue=0) if radius else mask
    return dict(reflection_source_defined_fraction=fraction(defined,X,Y,active,shift),
                reflection_gaussian_3sigma_defined_fraction=fraction(safe(defined),X,Y,active,shift),
                reflection_source_observed_fraction=fraction(observed,X,Y,active,shift),
                reflection_gaussian_3sigma_observed_fraction=fraction(safe(observed),X,Y,active,shift),
                reflection_source_gaussian_radius_native_px=radius)
