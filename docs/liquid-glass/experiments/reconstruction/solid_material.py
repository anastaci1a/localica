"""Closed-ray image inputs with a fitted RGB environment; no image-space warp."""
import cv2,numpy as np
from solid import trace


def inputs(source,blur,case,fit,x,y,boundary):
    p=fit['parameters'];bundle=trace(x,y,case,p,bounces=fit.get('ray_bounces',16),intersection_steps=fit.get('intersection_steps',28))
    sample=np.zeros((*x.shape,3));unknown=np.zeros(x.shape)
    core=np.clip(-boundary[0]/(min(case['half'])*.30),0,1);core=core**3*(core*(6*core-15)+10)
    d=-boundary[0];transition=fit.get('blur_transition')
    if transition:
        blend=np.clip(d/transition,0,1);blend=blend**3*(blend*(6*blend-15)+10)
    def at(image,X,Y):
        return cv2.remap(image,(X+case['center'][0]).astype(np.float32),(Y+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR).reshape((*x.shape,3))
    for path in bundle['paths']:
        X,Y=path['position'][...,0],path['position'][...,1];W=path['weight']
        color=at(blur,X,Y)
        if transition:color=at(source,X,Y)*(1-blend[...,None])+color*blend[...,None]
        sample+=color*W[...,None]
        outside=(X+case['center'][0]<0)|(X+case['center'][0]>source.shape[1]-1)|(Y+case['center'][1]<0)|(Y+case['center'][1]>source.shape[0]-1)
        unknown+=W*outside
    env=np.zeros((*x.shape,4))
    for path in bundle['environment']:
        R=path['direction'];W=path['weight']
        env+=np.concatenate([np.ones((*x.shape,1)),R],axis=-1)*W[...,None]
    # Four RGB environment coefficients/channel and five smooth body terms,
    # matching the existing 26-coefficient first-order environment control.
    light=np.zeros((*x.shape,3,18))
    for channel in range(3):light[...,channel,channel*4:channel*4+4]=env
    X=x/case['half'][0];Y=y/case['half'][1]
    light[...,12:]=np.stack([X,Y,X*X,Y*Y,X*Y,core],axis=-1)[...,None,:]
    diagnostics={'untraced_energy_mean':float(bundle['residual'].mean()),'untraced_energy_max':float(bundle['residual'].max()),'unknown_background_energy_mean':float(unknown.mean()),'unknown_background_energy_max':float(unknown.max())}
    return sample,light,diagnostics
