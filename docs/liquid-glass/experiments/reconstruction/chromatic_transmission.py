"""Optional directional RGB transmission, before shared material lighting.

This is an empirical lateral-chromatic model, not recovered Apple optics.
All displacement/fade lengths use the pose's normalized coordinates. A fixed
channel separation fades in contribution; its length does not shrink at x.
"""
import cv2,numpy as np


def enabled(p):
    q=p.get('directional_transmission')
    return q is not None and q['strength']>0 and abs(q['length'])>1e-12


def weight(study,p,x):
    q=p['directional_transmission']
    assert 0<=q['strength']<=1 and q['fade_width']>0
    assert np.isfinite(list(q.values())).all()
    axis=(x-p.get('shape_dx',0))/(study.half[0]+p.get('half_x_delta',0))
    u=np.clip((axis-q['fade_center'])/q['fade_width']+.5,0,1)
    return q['strength']*(1-u**3*(u*(6*u-15)+10))


def rays(study,p,x,y):
    q=p['directional_transmission']
    for channel,sign in [(0,1),(2,-1)]:
        qx=x+sign*q['length']*np.cos(q['angle'])
        qy=y+sign*q['length']*np.sin(q['angle'])
        X,Y,d,_=study.geometry_field(qx,qy,p)
        *_,(sd,_,_)=study.shape(qx,qy,p)
        # Outside the optical footprint, sample the ordinary source plane.
        yield channel,np.where(sd<=0,X,qx),np.where(sd<=0,Y,qy),d


def apply(study,p,x,y,sample):
    if not enabled(p):return sample
    w=weight(study,p,x)
    dispersed=sample.copy()
    for c,X,Y,d in rays(study,p,x,y):
        dispersed[...,c]=study.sample_field(X,Y,d,p)[...,c]
    return sample*(1-w[...,None])+dispersed*w[...,None]


def support_rays(study,p):
    if not enabled(p):return []
    w=weight(study,p,study.x)
    return [(X*study.L+study.case['center'][0],Y*study.L+study.case['center'][1],w>1e-8)
            for _,X,Y,_ in rays(study,p,study.x,study.y)]


def support(study,p,defined,observed):
    from source_support import fraction,kernel_safe
    rays=support_rays(study,p)
    safe,radius=kernel_safe(defined,study,p);direct_safe,_=kernel_safe(observed,study,p)
    def worst(mask):
        return min((fraction(mask,X.astype(np.float32),Y.astype(np.float32),active&study.valid)
                    for X,Y,active in rays if (active&study.valid).any()),default=1.)
    return dict(chromatic_source_defined_fraction=worst(defined),
                chromatic_gaussian_3sigma_defined_fraction=worst(safe),
                chromatic_source_observed_fraction=worst(observed),
                chromatic_gaussian_3sigma_observed_fraction=worst(direct_safe),
                chromatic_source_gaussian_radius_native_px=radius)
