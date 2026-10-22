"""Private geometry-only toolbar surface for the agreed restricted tracer.

No image map, source sampling, material or independent normal field. The shared
bevel_profile is imported unchanged from lead's pinned geometric_optics module.
Coordinates have one common length unit. Production toolbar coordinates use
minor half-extent1; scaled fixtures change ALL lengths, not just point positions.
"""
from dataclasses import dataclass
import numpy as np
from crowned_footprint import field, longitudinal
from geometric_optics import bevel_profile


@dataclass(frozen=True)
class CrownedBevel:
    """Closed solid T<=1, -h<=z<=0; h=depth*(1-T**1.5).

    rho=sqrt(T) is convex, even and continuous, but NOT a homogeneous norm
    or Euclidean distance. q is convex/nonnegative: writing R=hypot(x,e),
    w=(R-neck)/e and C=b/den>0 gives
      q''=C*[sigmoid(w)*(1-sigmoid(w))*x²/(e²R²)+sigmoid(w)*e/R³]>=0.
    Thus rho=norm((q(x),y))/b is convex. Shared profile has h'<=0,h''<=0,
    so h is concave. Its center extension is C2 for core0/power3. K is convex.
    |grad rho|<=max(1,q'(a))/b on T<=1; depth*3 times this bounds |grad h|.
    Front/back meet in an actual CREASE at T=1, not a unique surface normal.
    These certificates concern primary orthographic transmission, not radiance.
    """
    half: tuple=(392/170,1.)
    depth: float=.02
    softness: float=.1
    radial_fixture: bool=False

    def __post_init__(self):
        if len(self.half)!=2 or not np.isfinite([*self.half,self.depth,self.softness]).all():
            raise ValueError('Finite two-component half-size/depth/softness required')
        if min(self.half)<=0 or self.depth<=0 or not 0<self.softness<=1:
            raise ValueError('Positive sizes/depth and softness in (0,1] required')
        if self.radial_fixture and self.half[0]!=self.half[1]:
            raise ValueError('True radial fixture requires equal half-sizes')

    @property
    def maximum_height(self):
        return self.depth

    @property
    def rho_gradient_bound(self):
        b=min(self.half)
        if self.radial_fixture:return 1/b
        a=max(self.half)
        _,qp=longitudinal(np.asarray(a),a,b,self.softness)
        return max(1.,float(qp))/b

    @property
    def slope_bound(self):
        return self.depth*3*self.rho_gradient_bound

    def level(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        if not np.isfinite(x).all() or not np.isfinite(y).all():
            raise ValueError('Finite geometry coordinates required')
        if self.radial_fixture:
            b=min(self.half)
            return (x*x+y*y)/(b*b),2*x/(b*b),2*y/(b*b)
        return field(x,y,self.half,self.softness)

    def height_gradient(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        T,tx,ty=self.level(x,y)
        # hypot and multiplication order deliberately match lead's true circle.
        rho=np.hypot(x,y)/min(self.half) if self.radial_fixture else np.sqrt(T)
        h,hr,_=bevel_profile(rho,0.,self.depth,3,0.)
        factor=np.divide(hr,rho,out=np.zeros_like(rho),where=rho>0)
        if self.radial_fixture:
            factor=factor/(min(self.half)**2)
            hx,hy=factor*x,factor*y
            inside=rho<=1
        else:
            hx,hy=.5*factor*tx,.5*factor*ty
            inside=T<=1
        return h,hx,hy,inside

    def boundary_kind(self,x,y):
        T,*_=self.level(x,y)
        # This is a diagnostic flag only: it never alters T/inside or normals.
        tol=64*np.finfo(float).eps*np.maximum(1.,np.abs(T))
        return np.where(abs(T-1)<=tol,'CREASE',np.where(T<1,'INTERIOR','EXTERIOR'))

    def boundary_mask(self,x,y):
        """Actual exact footprint crease; no tolerance or optical mask change."""
        if self.radial_fixture:
            return np.hypot(x,y)/min(self.half)==1
        return self.level(x,y)[0]==1

    def back_vertices(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
        h,_,_,inside=self.height_gradient(x,y)
        return np.where(inside[...,None],np.stack([x,y,-h],axis=-1),np.nan)

    def section(self,coordinates,axis='x'):
        v=np.asarray(coordinates,float);zero=np.zeros_like(v)
        if axis=='x':x,y=v,zero
        elif axis=='y':x,y=zero,v
        else:raise ValueError('Section axis must be x or y')
        h,hx,hy,inside=self.height_gradient(x,y)
        normal=np.stack([-hx,-hy,-np.ones_like(h)],axis=-1)
        normal/=np.linalg.norm(normal,axis=-1,keepdims=True)
        return dict(back=self.back_vertices(x,y),front=np.stack([x,y,zero],axis=-1),
                    inside=inside,back_face_normal=normal,
                    boundary_kind=self.boundary_kind(x,y),
                    limits='Back face limit is defined; whole-solid normal at CREASE is not unique.')
