"""Distinguish defined source RGB from directly observed source pixels."""
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


def masks(study,defined=None):
    if defined is None:defined=cv2.imread(str(study.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
    name=study.case.get('source_observation_mask')
    if name is None:
        assert not study.case.get('allow_estimated_background'),'Estimated input requires a separate direct-observation mask'
        return defined,defined
    assert name in study.case['source_sha256'],'Freeze the observation mask hash'
    assert study.case.get('allow_estimated_background') and study.case.get('background_estimate'),'Document estimated source geometry/colors'
    observed=cv2.imread(str(study.folder/name),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
    assert observed.shape==defined.shape and np.all(observed<=defined)
    return defined,observed


def kernel_safe(mask,study,p):
    radius=int(np.ceil(3*round(study.kernel_sigma(p)*4)/4))
    return (cv2.erode(mask,np.ones((2*radius+1,2*radius+1),np.uint8),borderType=cv2.BORDER_CONSTANT,borderValue=0) if radius else mask),radius


def fraction(confidence,X,Y,mask,shift=0.):
    support=cv2.remap(confidence,X,Y+np.float32(shift),cv2.INTER_LINEAR)
    return float((support[mask]>.999).mean())


def coverage_report(study,p,X=None,Y=None):
    if X is None or Y is None:X,Y=coordinates(study,p)
    defined,observed=masks(study)
    safe,radius=kernel_safe(defined,study,p);direct_safe,_=kernel_safe(observed,study,p)
    return dict(source_defined_fraction=fraction(defined,X,Y,study.valid),
                gaussian_3sigma_defined_fraction=fraction(safe,X,Y,study.valid),
                source_observed_fraction=fraction(observed,X,Y,study.valid),
                gaussian_3sigma_observed_fraction=fraction(direct_safe,X,Y,study.valid),
                source_gaussian_radius_native_px=radius)


def supported(study,report):
    keys=['source_defined_fraction','gaussian_3sigma_defined_fraction']
    if not study.case.get('allow_estimated_background'):
        keys+=['source_observed_fraction','gaussian_3sigma_observed_fraction']
    return all(report[k]>=.999 for k in keys)
