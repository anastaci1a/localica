"""Prepared fixed geometry/material for high-resolution moving-background video.

Only the source coordinates translate. Geometry, lighting and subpixel sampling
are the same as Study.render; no baked refracted image is translated or enlarged.
"""
import cv2,numpy as np


class Prepared:
    def __init__(self,study,p,co,scale):
        self.study=study;self.p=p;self.co=np.asarray(co);self.scale=scale;self.tiles=[]
        self.chromatic=[]
        self.ambient=[]
        for row,bottom,W,x,y in study.raster_tiles(scale):
            X,Y,d,J=study.geometry_field(x,y,p)
            core=J[...,-1]
            adaptive='material_chroma_sensitivity' in p
            gain=None if adaptive else (1-core[...,None])*self.co[:3]+core[...,None]*self.co[-3:]
            illumination=J[...,:-1]@self.co[6:-3]
            blend=None
            if p.get('blur_transition',0)>1e-8:
                u=np.clip(d/p['blur_transition'],0,1);blend=u**3*(u*(6*u-15)+10)
            # Copy the scalar core: a view would retain all15 lighting planes
            # for every export tile, although they are no longer needed.
            self.tiles.append((row,bottom,W,X*study.L+study.case['center'][0],Y*study.L+study.case['center'][1],gain,illumination,blend,core.copy() if adaptive else None))
            from chromatic_transmission import enabled,weight,rays
            extra=None
            if enabled(p):
                w=weight(study,p,x)
                columns=np.flatnonzero(np.any(w>0,axis=0))
                if columns.size:
                    # Cache only the active columns, not two full-frame RGB
                    # maps. Keep coordinates in float64 until remap packing.
                    left,right=int(columns[0]),int(columns[-1])+1
                    extra=(left,right,w[:,left:right],list(rays(study,p,x[:,left:right],y[:,left:right])))
            self.chromatic.append(extra)
            self.ambient.append(J[...,[0,3,6]].copy() if any(p.get('ambient_rim_response',[])) else None)

    def render(self,tiles=None):
        s=self.study;p=self.p;x0,y0,x1,y1=s.crop
        co=self.co
        if 'material_chroma_sensitivity' in p or 'ambient_rim_response' in p:
            from material_context import effective
            co=effective(s,p,co)
        out=np.empty(((y1-y0)*self.scale,(x1-x0)*self.scale,3),np.float32)
        selected=self.tiles if tiles is None else tiles
        assert tiles is None or not any(v is not None for v in self.chromatic),'Chromatic profile proposals require rebuilt ray tiles'
        count=0
        for index,(row,bottom,W,X,Y,gain,light,blend,core) in enumerate(selected):
            if tiles is not None:assert (row,bottom,W)==self.tiles[index][:3],'Profile tiles must retain the complete raster order'
            count+=1
            if core is not None:gain=(1-core[...,None])*co[:3]+core[...,None]*co[-3:]
            if self.ambient[index] is not None:light=light+self.ambient[index]@(co[[6,9,12]]-self.co[[6,9,12]])
            x=(X+s.backdrop_shift[0]).astype(np.float32);y=(Y+s.backdrop_shift[1]).astype(np.float32)
            C=cv2.remap(s.blur(s.kernel_sigma(p)),x,y,cv2.INTER_LINEAR)
            if blend is not None:
                clear=cv2.remap(s.source,x,y,cv2.INTER_LINEAR)
                C=clear+(C-clear)*blend[...,None]
            C=np.maximum(C,0)**p['gamma']
            extra=self.chromatic[index]
            if extra is not None:
                left,right,w,rays=extra
                dispersed=C[:,left:right].copy()
                for channel,rx,ry,d in rays:
                    dispersed[...,channel]=s.sample_field(rx,ry,d,p)[...,channel]
                C[:,left:right]=C[:,left:right]*(1-w[...,None])+dispersed*w[...,None]
            C=np.clip(C*gain+co[3:6]+light[...,None],0,1)
            out[row:bottom]=C.reshape(bottom-row,2,W,2,3).mean((1,3))
        if tiles is not None:assert count==len(self.tiles),'Incomplete profile proposal'
        if s.kernel_sigma(p,'post_sigma')>.001:out=cv2.GaussianBlur(out,(0,0),s.kernel_sigma(p,'post_sigma')*self.scale)
        from grazing_reflection import apply
        return apply(s,p,out,self.scale)
