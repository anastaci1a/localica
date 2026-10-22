"""Shared geometric transmission/pixel integration for circle and pill controls.

4x4 pixel integration is AA, not optical blur. Raw unit transmission is common;
optional frozen constant grading reproduces historical diagnostics only.
"""
import cv2,numpy as np
from geometric_optics import trace_flat_front_orthographic

def render_rows(source,case,p,surface,index,distance,start,stop,raw_transmission=False,exact_bilinear=False):
    """4x4 finite pixel integration with coupled lens/background coverage."""
    x0,y0,x1,y1=case['crop'];L=min(case['half'])
    yy,xx=np.mgrid[y0+start:y0+stop:1/4,x0:x1:1/4]
    x=(xx-.375-case['center'][0])/L;y=(yy-.375-case['center'][1])/L
    q=trace_flat_front_orthographic(surface,x,y,index,distance)
    if not q['complete'].all():raise ValueError('Unresolved primary path; no pixel fallback')
    X=q['X']*L+case['center'][0];Y=q['Y']*L+case['center'][1]
    if X.min()<0 or Y.min()<0 or X.max()>=source.shape[1]-1 or Y.max()>=source.shape[0]-1:raise ValueError('Undefined bilinear source footprint')
    if exact_bilinear:
        a=np.floor(X).astype(int);b=np.floor(Y).astype(int);fx=(X-a)[...,None];fy=(Y-b)[...,None]
        C=(source[b,a]*(1-fx)+source[b,a+1]*fx)*(1-fy)+(source[b+1,a]*(1-fx)+source[b+1,a+1]*fx)*fy
    else:C=cv2.remap(source,X.astype(np.float32),Y.astype(np.float32),cv2.INTER_LINEAR)
    if not raw_transmission:
        fixed=C*np.asarray(p['material'][:3])+np.asarray(p['material'][3:6])
        C=np.where(q['inside'][...,None],fixed,C)
    return C.reshape(stop-start,4,x1-x0,4,3).mean((1,3)).astype(np.float32),[float(X.min()),float(Y.min()),float(X.max()),float(Y.max())]
