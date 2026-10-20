"""Prepared fixed geometry/material for high-resolution moving-background video.

Only the source coordinates translate. Geometry, lighting and subpixel sampling
are the same as Study.render; no baked refracted image is translated or enlarged.
"""
import cv2,numpy as np


class Prepared:
    def __init__(self,study,p,co,scale):
        self.study=study;self.p=p;self.co=np.asarray(co);self.scale=scale;self.tiles=[]
        for row,bottom,W,x,y in study.raster_tiles(scale):
            X,Y,d,J=study.geometry_field(x,y,p)
            core=J[...,-1]
            gain=(1-core[...,None])*self.co[:3]+core[...,None]*self.co[-3:]
            illumination=J[...,:-1]@self.co[6:-3]
            blend=None
            if p.get('blur_transition',0)>1e-8:
                u=np.clip(d/p['blur_transition'],0,1);blend=u**3*(u*(6*u-15)+10)
            self.tiles.append((row,bottom,W,X*study.L+study.case['center'][0],Y*study.L+study.case['center'][1],gain,illumination,blend,core if 'material_chroma_sensitivity' in p else None))

    def render(self,tiles=None):
        s=self.study;p=self.p;x0,y0,x1,y1=s.crop
        co=self.co
        if 'material_chroma_sensitivity' in p:
            from material_context import matrix
            co=matrix(s,p)@co
        out=np.empty(((y1-y0)*self.scale,(x1-x0)*self.scale,3),np.float32)
        for row,bottom,W,X,Y,gain,light,blend,core in self.tiles if tiles is None else tiles:
            if core is not None:gain=(1-core[...,None])*co[:3]+core[...,None]*co[-3:]
            x=(X+s.backdrop_shift[0]).astype(np.float32);y=(Y+s.backdrop_shift[1]).astype(np.float32)
            C=cv2.remap(s.blur(s.kernel_sigma(p)),x,y,cv2.INTER_LINEAR)
            if blend is not None:
                clear=cv2.remap(s.source,x,y,cv2.INTER_LINEAR)
                C=clear+(C-clear)*blend[...,None]
            C=np.maximum(C,0)**p['gamma']
            C=np.clip(C*gain+co[3:6]+light[...,None],0,1)
            out[row:bottom]=C.reshape(bottom-row,2,W,2,3).mean((1,3))
        if s.kernel_sigma(p,'post_sigma')>.001:out=cv2.GaussianBlur(out,(0,0),s.kernel_sigma(p,'post_sigma')*self.scale)
        return out
