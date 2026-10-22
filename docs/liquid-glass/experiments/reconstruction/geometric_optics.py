"""Restricted exact geometric optics for a flat-front, concave-height solid.

Coordinates normalized by minor half-size; +z towards the orthographic camera.
No empirical image map or independent normal field enters this module.
"""
from dataclasses import dataclass
import numpy as np


def bevel_profile(rho,core,depth,power=3,edge=0.):
    """One height profile shared by circle and convex continuous pill gauges.

    Returns h, dh/drho, d2h/drho2. The caller derives rho and its gradient
    from the SAME footprint. For power>=3, the plateau/bevel join is C2.
    """
    if not np.isfinite([core,depth,power,edge]).all() or not 0<=core<1 or depth<=0 or power<3 or edge<0:raise ValueError('Invalid convex bevel')
    rho=np.asarray(rho,float)
    if np.any(rho<0):raise ValueError('Gauge must be nonnegative')
    u=np.maximum((rho-core)/(1-core),0)
    return (edge+depth*(1-u**power),
            -depth*power*u**(power-1)/(1-core),
            -depth*power*(power-1)*u**(power-2)/(1-core)**2)


def refract(incident, against_incident_normal, n_from, n_to):
    """Snell transmission; TIR/critical grazing has no replacement ray.

    The Boolean flags k<=0 (TIR or its zero-transmission critical boundary).
    """
    i,n=np.broadcast_arrays(np.asarray(incident,float),np.asarray(against_incident_normal,float))
    if i.shape[-1]!=3 or n_from<=0 or n_to<=0:raise ValueError('Invalid optical input')
    if np.max(abs(np.linalg.norm(i,axis=-1)-1))>1e-10 or np.max(abs(np.linalg.norm(n,axis=-1)-1))>1e-10:raise ValueError('Unit vectors required')
    ci=-np.sum(i*n,axis=-1)
    if np.any(ci < -1e-12):raise ValueError('Normal faces away from incident medium')
    eta=n_from/n_to;k=1-eta*eta*(1-ci*ci);tir=k<=0
    out=eta*i+(eta*ci-np.sqrt(np.maximum(k,0)))[...,None]*n
    return np.where(tir[...,None],np.nan,out),tir,k


@dataclass(frozen=True)
class CircularBevel:
    """Closed solid: front0, back-height h, side radius1 over [-edge,0]."""
    core: float=.6
    depth: float=.02
    power: int=3
    edge: float=0.

    def __post_init__(self):
        if not np.isfinite([self.core,self.depth,self.power,self.edge]).all() or not 0<=self.core<1 or self.depth<=0 or self.power<3 or self.edge<0:raise ValueError('Invalid convex bevel')

    @property
    def maximum_height(self):return self.edge+self.depth
    @property
    def slope_bound(self):return self.depth*self.power/(1-self.core)

    def boundary_mask(self,x,y):
        return np.hypot(x,y)==1

    def height_gradient(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));r=np.hypot(x,y)
        h,hr,_=bevel_profile(r,self.core,self.depth,self.power,self.edge)
        factor=np.divide(hr,r,out=np.zeros_like(r),where=r>0)
        return h,factor*x,factor*y,r<=1

    def section(self,r):
        """Actual back geometry for a section/mesh, not a decorative profile."""
        h,hx,_,inside=self.height_gradient(r,np.zeros_like(r))
        return -h,-hx,inside


def trace_flat_front_orthographic(surface,x,y,index,background_distance):
    """Exact primary transmission for a certified concave height surface.

    Required certificate: height concave over a convex footprint; slope_bound
    and maximum_height global. These ensure no TIR and no later back/side hit.
    Surface height_gradient MUST derive from the same height used for geometry.
    Only CircularBevel is certified here; other surfaces need separate proof.
    """
    if index<1 or not np.isfinite([index,background_distance]).all() or background_distance<=surface.maximum_height:raise ValueError('Invalid index or background clearance')
    limit=np.inf if index==1 else 1/np.sqrt(index*index-1)
    if surface.slope_bound>=limit:raise ValueError('Cannot certify a transmitted branch across footprint')
    x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));h,hx,hy,inside=surface.height_gradient(x,y)
    boundary=surface.boundary_mask(x,y)
    # Do not evaluate an extrapolated bevel as an optical solid outside radius1.
    hx=np.where(inside,hx,0);hy=np.where(inside,hy,0);h=np.where(inside,h,0)
    n_against=np.stack([hx,hy,np.ones_like(h)],axis=-1);n_against/=np.linalg.norm(n_against,axis=-1)[...,None]
    i=np.zeros_like(n_against);i[...,2]=-1
    direction,tir,k=refract(i,n_against,index,1.)
    if np.any(tir&inside) or np.any(direction[...,2]>=0):raise ValueError('Incomplete background path')
    distance=(background_distance-h)/(-direction[...,2])
    X=x+distance*direction[...,0];Y=y+distance*direction[...,1]
    # At the closed solid's crease there is no unique interface normal/path.
    # Report it, rather than rendering the one-sided back-surface limit.
    valid_surface=inside&~boundary
    return dict(X=np.where(boundary,np.nan,np.where(inside,X,x)),
                Y=np.where(boundary,np.nan,np.where(inside,Y,y)),inside=inside,
                boundary_unresolved=boundary,
                back=np.where(valid_surface[...,None],np.stack([x,y,-h],axis=-1),np.nan),
                outward_normal=np.where(valid_surface[...,None],-n_against,np.nan),
                exit_direction=direction,discriminant=k,path_length=distance,
                complete=~boundary)
