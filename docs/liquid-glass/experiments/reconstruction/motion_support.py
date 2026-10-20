"""Keep diagnostic unknown-source colors out of illustrative motion previews.

Frozen scoring inputs are never edited. Missing motion context is completed by
nearest observed RGB, and travel is capped against the scored source footprint.
Completion is an estimate, not newly recovered evidence.
"""
from collections import OrderedDict
import cv2,numpy as np


def coordinates(study,p):
    original,cache,shift=study.source,study.cache,study.backdrop_shift
    H,W=original.shape[:2];Y,X=np.mgrid[:H,:W]
    try:
        study.source=np.stack([(X+1)/(W+2),(Y+1)/(H+2),np.ones_like(X)],-1).astype(np.float32)
        study.cache=OrderedDict();study.backdrop_shift=[0.,0.]
        C,_=study.field(study.x,study.y,{**p,'sigma':0.,'gamma':1.})
        return (C[...,0]*(W+2)-1).astype(np.float32),(C[...,1]*(H+2)-1).astype(np.float32)
    finally:study.source=original;study.cache=cache;study.backdrop_shift=shift


def prepare(study,p,known,cov,requested):
    X,Y=coordinates(study,p)
    radius=int(np.ceil(3*round(study.kernel_sigma(p)*4)/4))
    safe=cv2.erode(known,np.ones((2*radius+1,2*radius+1),np.uint8)) if radius else known
    def fraction(shift,mask,confidence):
        support=cv2.remap(confidence,X,Y+np.float32(shift),cv2.INTER_LINEAR)
        return float((support[mask]>.999).mean())
    actual=0.;minimum=0.
    for scale in [1.,.75,.5,.25,.125,0.]:
        actual=requested*scale
        minimum=min(fraction(s,study.valid,safe) for s in np.linspace(-actual,actual,33))
        if minimum>=.999:break
    full=min(fraction(s,cov>0,known) for s in np.linspace(-actual,actual,33))
    missing=known<.999
    source=study.source.copy()
    if missing.any():
        _,labels=cv2.distanceTransformWithLabels(missing.astype(np.uint8),cv2.DIST_L2,5,labelType=cv2.DIST_LABEL_PIXEL)
        lookup=np.zeros((int(labels.max())+1,3),np.float32)
        lookup[labels[~missing]]=source[~missing]
        source[missing]=lookup[labels[missing]]
    assert np.array_equal(source[~missing],study.source[~missing])
    return source,actual,dict(requested_native_px=requested,actual_native_px=actual,
        minimum_scored_lookup_3sigma_observed_fraction=minimum,
        minimum_whole_shape_lookup_observed_fraction=full,
        completion='Nearest observed RGB extends unobserved input only for motion; frozen comparisons and scoring inputs are unchanged.',
        limits='Native-grid support tests at33 shifts, not continuous/subpixel or output-scattering confidence. Completed pixels remain estimates.')
