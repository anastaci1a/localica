"""Private actual concave back: shared harmonic cap, no independent normal."""
from dataclasses import dataclass
import numpy as np
from crowned_bevel import CrownedBevel
from geometric_optics import harmonic_cap

@dataclass(frozen=True)
class HarmonicCrownedBack:
    half: tuple=(392/170,1.)
    depth: float=.15
    crown_depth: float=.4
    softness: float=.1
    radial_fixture: bool=False

    def __post_init__(self):
        if not np.isfinite([self.depth,self.crown_depth]).all() or min(self.depth,self.crown_depth)<=0:raise ValueError('Positive finite heights required')
        self.base  # Validate accepted footprint/radial fixture immediately.

    @property
    def base(self):return CrownedBevel(self.half,self.depth,self.softness,self.radial_fixture)

    @property
    def maximum_height(self):return self.depth*self.crown_depth/(self.depth+self.crown_depth)

    @property
    def slope_bound(self):
        quadratic=2*self.crown_depth/(min(self.half) if self.radial_fixture else self.half[0])
        return max(self.base.slope_bound,quadratic)

    def boundary_mask(self,x,y):return self.base.boundary_mask(x,y)

    def height_gradient(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        h1,gx1,gy1,inside=self.base.height_gradient(x,y)
        h1=np.where(inside,h1,0.)
        if self.radial_fixture:
            b=min(self.half);r=np.hypot(x,y)/b
            h2=self.crown_depth*np.maximum(1-r*r,0.)
            gx2=-2*self.crown_depth*x/(b*b);gy2=-2*self.crown_depth*y/(b*b)
        else:
            a=self.half[0]
            h2=np.where(inside,self.crown_depth*(1-(x/a)**2),0.)
            gx2=-2*self.crown_depth*x/(a*a);gy2=np.zeros_like(y)
        h,w1,w2=harmonic_cap(h1,h2)
        return h,w1*gx1+w2*gx2,w1*gy1+w2*gy2,inside

    def section(self,v,axis='x'):
        v=np.asarray(v,float);zero=np.zeros_like(v)
        if axis=='x':x,y=v,zero
        elif axis=='y':x,y=zero,v
        else:raise ValueError('axis x/y required')
        h,hx,hy,inside=self.height_gradient(x,y)
        back=np.where(inside[...,None],np.stack([x,y,-h],-1),np.nan)
        return dict(back=back,front=np.stack([x,y,zero],-1),gradient=np.stack([hx,hy],-1),inside=inside,boundary_unresolved=self.boundary_mask(x,y))
