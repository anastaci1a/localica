"""Optional source-derived affine responses of one shared material.

This is an empirical context response, not an Apple formula or dispersion.
It changes material coefficients, never the sampled RGB ray colors. A neutral
source therefore does not inherit a color cast fitted on a saturated source.
An independent sparse offset adjusts only three neutral rim-light terms.
"""
import cv2,numpy as np

LUMA=np.array([.2126,.7152,.0722])  # Encoded RGB weights, not radiometry.


def contraction(mean,sensitivity,floor=0.):
    assert sensitivity>=0 and 0<=floor<=1
    chroma=float(np.ptp(mean))
    t=floor+(1-floor)*np.clip(sensitivity*chroma,0,1)
    return t*np.eye(3)+(1-t)*np.outer(np.ones(3),LUMA),float(t)


def mean_source(study,p):
    xx,yy,inside=footprint(study,p)
    # Native-center quadrature of mean unrefracted source over the actual
    # footprint. This is independent of target, foreground and scoring masks.
    source=cv2.remap(study.source,(xx.astype(float)+study.backdrop_shift[0]).astype(np.float32),
                     (yy.astype(float)+study.backdrop_shift[1]).astype(np.float32),cv2.INTER_LINEAR)
    return source[inside].mean(0,dtype=np.float64)


def affine(study,p):
    T=np.eye(23);offset=np.zeros(23)
    chroma='material_chroma_sensitivity' in p
    ambient='ambient_rim_response' in p
    if not chroma and not ambient:return T,offset
    mean=mean_source(study,p)
    if chroma:
        A,_=contraction(mean,p['material_chroma_sensitivity'],p.get('material_chroma_floor',0.))
        for begin in [0,3,20]:T[begin:begin+3,begin:begin+3]=A
    if ambient:
        response=np.asarray(p['ambient_rim_response'],float)
        assert response.shape==(3,) and np.isfinite(response).all()
        # Only neutral thin/e/f terms respond to source brightness. Three
        # shared coefficients, not a separate RGB material for each scene.
        brightness=float(mean@LUMA)
        if 'ambient_rim_luma_range' in p:
            low,high=p['ambient_rim_luma_range'];assert 0<=low<high<=1
            u=np.clip((brightness-low)/(high-low),0,1)
            brightness=low+(high-low)*u**3*(u*(6*u-15)+10)
        offset[[6,9,12]]=(brightness-.5)*response
    return T,offset


def matrix(study,p):
    return affine(study,p)[0]


def effective(study,p,co):
    T,offset=affine(study,p)
    return T@co+offset


def footprint(study,p):
    x0,y0,x1,y1=study.crop
    Y,X=np.mgrid[y0:y1,x0:x1]
    *_,(sd,_,_)=study.shape((X-study.case['center'][0])/study.L,(Y-study.case['center'][1])/study.L,p)
    inset=p.get('material_context_inset',0.)
    assert inset>=0
    inside=sd < -inset
    assert inside.any(),'Empty source-context footprint'
    return X.astype(np.float32),Y.astype(np.float32),inside


def support(study,p,defined,observed):
    from source_support import fraction,minimum_confidence
    X,Y,inside=footprint(study,p)
    return dict(context_mean_source_defined_fraction=fraction(defined,X,Y,inside),
                context_mean_source_observed_fraction=fraction(observed,X,Y,inside),
                context_mean_minimum_defined_confidence=minimum_confidence(defined,X,Y,inside),
                context_mean_minimum_observed_confidence=minimum_confidence(observed,X,Y,inside))
