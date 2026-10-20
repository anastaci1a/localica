"""Optional source-derived contraction of a shared material's RGB bias.

This is an empirical context response, not an Apple formula or dispersion.
It changes material coefficients, never the sampled RGB ray colors. A neutral
source therefore does not inherit a color cast fitted on a saturated source.
"""
import cv2,numpy as np

LUMA=np.array([.2126,.7152,.0722])  # Encoded RGB weights, not radiometry.


def contraction(mean,sensitivity,floor=0.):
    assert sensitivity>=0 and 0<=floor<=1
    chroma=float(np.ptp(mean))
    t=floor+(1-floor)*np.clip(sensitivity*chroma,0,1)
    return t*np.eye(3)+(1-t)*np.outer(np.ones(3),LUMA),float(t)


def matrix(study,p):
    T=np.eye(23)
    if 'material_chroma_sensitivity' not in p:return T
    x0,y0,x1,y1=study.crop
    yy,xx=np.mgrid[y0:y1,x0:x1]
    x=(xx-study.case['center'][0])/study.L;y=(yy-study.case['center'][1])/study.L
    *_,(sd,_,_)=study.shape(x,y,p)
    # Native-center quadrature of mean unrefracted source over the actual
    # footprint. This is independent of target, foreground and scoring masks.
    source=cv2.remap(study.source,(xx+study.backdrop_shift[0]).astype(np.float32),
                     (yy+study.backdrop_shift[1]).astype(np.float32),cv2.INTER_LINEAR)
    mean=source[sd<0].mean(0,dtype=np.float64)
    A,_=contraction(mean,p['material_chroma_sensitivity'],p.get('material_chroma_floor',0.))
    for begin in [0,3,20]:T[begin:begin+3,begin:begin+3]=A
    return T
