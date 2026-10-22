"""Private closed B surface; no tracing, source lookup, shading or fitting.

F=T+(1+kappa*(x/a)^2)*u^2-1, u=(z+eta)/eta.
One exact level/gradient is used for all sections and mesh vertices.
"""
from dataclasses import dataclass
import numpy as np
from crowned_footprint import field


@dataclass(frozen=True)
class ClosedCrownedSurface:
    half: tuple=(392/170,1.)
    eta: float=.15
    kappa: float=0.
    softness: float=.1
    radial_fixture: bool=False

    def __post_init__(self):
        if len(self.half)!=2 or not np.isfinite([*self.half,self.eta,self.kappa,self.softness]).all():
            raise ValueError('Finite parameters required')
        if not self.half[0]>=self.half[1]>0 or self.eta<=0 or not 0<=self.kappa<=1/3 or not 0<self.softness<=1:
            raise ValueError('Require a>=b>0, eta>0, kappa in [0,1/3], softness in (0,1]')
        if self.radial_fixture and (self.half[0]!=self.half[1] or self.kappa!=0):
            raise ValueError('True circle fixture requires equal half-sizes and kappa0')

    @property
    def half_extents(self):return np.asarray([*self.half,self.eta],float)

    @property
    def center(self):return np.asarray([0.,0.,-self.eta])

    @property
    def regular_gradient_lower_bound(self):
        a,b=self.half
        return min(2/(3*a),2/(np.sqrt(3)*b),2/(np.sqrt(3)*self.eta))

    def footprint(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        if not np.isfinite(x).all() or not np.isfinite(y).all():raise ValueError('Finite points required')
        if self.radial_fixture:
            b=self.half[1]
            return (x*x+y*y)/(b*b),2*x/(b*b),2*y/(b*b)
        return field(x,y,self.half,self.softness)

    def level_gradient(self,points):
        p=np.asarray(points,float)
        if p.shape[-1:]!=(3,) or not np.isfinite(p).all():raise ValueError('Finite (...,3) points required')
        x,y,z=np.moveaxis(p,-1,0);a=self.half[0]
        T,tx,ty=self.footprint(x,y);u=(z+self.eta)/self.eta;s=1+self.kappa*(x/a)**2
        F=T+s*u*u-1
        grad=np.stack([tx+2*self.kappa*x*u*u/(a*a),ty,2*s*u/self.eta],axis=-1)
        return F,grad

    def sheet_heights(self,x,y):
        """Front/back roots of the SAME F; no source-domain extension outside T1."""
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        T,*_=self.footprint(x,y);s=1+self.kappa*(x/self.half[0])**2
        # sqrt only on defined footprint. No hidden projection/clamp into solid.
        root=np.sqrt(np.divide(1-T,s,out=np.full_like(T,np.nan),where=T<=1))
        return -self.eta+self.eta*root,-self.eta-self.eta*root

    def front_height(self,x,y):
        T,*_=self.footprint(x,y)
        return self.sheet_heights(x,y)[0],T<1

    def boundary_mask(self,x,y):
        return self.footprint(x,y)[0]==1

    def surface_points(self,x,y,front=True):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        z=self.sheet_heights(x,y)[0 if front else 1]
        return np.stack([x,y,z],axis=-1)

    def section(self,values,axis='x'):
        v=np.asarray(values,float);zero=np.zeros_like(v)
        if axis=='x':x,y=v,zero
        elif axis=='y':x,y=zero,v
        else:raise ValueError('axis x or y required')
        return self.surface_points(x,y,True),self.surface_points(x,y,False)
