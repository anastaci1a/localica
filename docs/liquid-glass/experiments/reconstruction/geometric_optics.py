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


def harmonic_cap(first,second):
    """Concave, increasing combination of two nonnegative actual heights.

    Returns height and its partial derivatives with respect to each height.
    H=a*b/(a+b); Hessian=-2/(a+b)^3 [[b²,-ab],[-ab,a²]].
    Thus concave input heights give a concave combined height. Weights sum
    <=1, so a global gradient bound is max(input gradient bounds). At a=b=0
    the solid's edge is unresolved; zero returned weights are NOT a normal.
    """
    a,b=np.broadcast_arrays(np.asarray(first,float),np.asarray(second,float))
    if np.any(a<0) or np.any(b<0):raise ValueError('Nonnegative heights required')
    total=a+b
    h=np.divide(a*b,total,out=np.zeros_like(total),where=total>0)
    wa=np.divide(b*b,total*total,out=np.zeros_like(total),where=total>0)
    wb=np.divide(a*a,total*total,out=np.zeros_like(total),where=total>0)
    return h,wa,wb


def refract(incident, against_incident_normal, n_from, n_to):
    """Snell transmission; TIR/critical grazing has no replacement ray.

    The Boolean flags k<=0 (TIR or its zero-transmission critical boundary).
    """
    i,n=np.broadcast_arrays(np.asarray(incident,float),np.asarray(against_incident_normal,float))
    if i.shape[-1]!=3 or not np.isfinite([n_from,n_to]).all() or n_from<=0 or n_to<=0 or not np.isfinite(i).all() or not np.isfinite(n).all():raise ValueError('Invalid optical input')
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


@dataclass(frozen=True)
class HarmonicCircularBack:
    """Flat front; one concave back combining a radial and quadratic crown."""
    depth: float=.15
    crown_depth: float=.4

    def __post_init__(self):
        if not np.isfinite([self.depth,self.crown_depth]).all() or min(self.depth,self.crown_depth)<=0:raise ValueError('Positive finite crown depths required')

    @property
    def maximum_height(self):return self.depth*self.crown_depth/(self.depth+self.crown_depth)
    @property
    def slope_bound(self):return max(3*self.depth,2*self.crown_depth)

    def boundary_mask(self,x,y):return np.hypot(x,y)==1

    def height_gradient(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));r=np.hypot(x,y);inside=r<=1
        h1,h1r,_=bevel_profile(np.where(inside,r,1),0,self.depth,3)
        h2=self.crown_depth*np.maximum(1-r*r,0);h,w1,w2=harmonic_cap(h1,h2)
        factor=np.divide(h1r,r,out=np.zeros_like(r),where=(r>0)&inside)
        return h,w1*factor*x-w2*2*self.crown_depth*x,w1*factor*y-w2*2*self.crown_depth*y,inside

    def section(self,r):
        h,hx,_,inside=self.height_gradient(r,np.zeros_like(r));return -h,-hx,inside


class RadialCurvatureBack:
    """One C2 concave radial height, defined by nonnegative curvature knots.

    Piecewise-linear k=-h'' is integrated twice. h'=0 at the flat core;
    h=0 at the outer edge. Intersections, normals and sections evaluate this
    same piecewise cubic. No diagnostic image displacement is evaluated.
    """
    def __init__(self,knots,curvature):
        from scipy.interpolate import PPoly
        self.knots=np.array(knots,float,copy=True);self.curvature=np.array(curvature,float,copy=True)
        if self.knots.ndim!=1 or self.curvature.shape!=self.knots.shape or len(self.knots)<2 or not np.isfinite(self.knots).all() or not np.isfinite(self.curvature).all() or self.knots[0]<0 or self.knots[-1]!=1 or np.any(np.diff(self.knots)<=0) or np.any(self.curvature<0) or self.curvature[0]!=0:raise ValueError('Ordered knots, nonnegative finite curvature and C2 core join required')
        k=PPoly(np.array([np.diff(self.curvature)/np.diff(self.knots),self.curvature[:-1]]),self.knots)
        self._integral=k.antiderivative(2);self._slope=self._integral.derivative()
        self.maximum_height=float(self._integral(1));self.slope_bound=float(self._slope(1))
        self.knots.flags.writeable=False;self.curvature.flags.writeable=False

    def boundary_mask(self,x,y):return np.hypot(x,y)==1

    def radial_profile(self,r):
        r=np.asarray(r,float);s=np.clip(r,self.knots[0],1)
        return self.maximum_height-self._integral(s),np.where(r<self.knots[0],0,-self._slope(s))

    def height_gradient(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));r=np.hypot(x,y);h,hr=self.radial_profile(r)
        factor=np.divide(hr,r,out=np.zeros_like(r),where=r>0)
        return h,factor*x,factor*y,r<=1

    def section(self,r):
        h,hx,_,inside=self.height_gradient(r,np.zeros_like(r));return -h,-hx,inside


def trace_flat_front_orthographic(surface,x,y,index,background_distance):
    """Exact primary transmission for a certified concave height surface.

    Required certificate: height concave over a convex footprint; slope_bound
    and maximum_height global. These ensure no TIR and no later back/side hit.
    Surface height_gradient MUST derive from the same height used for geometry.
    Only CircularBevel is certified here; other surfaces need separate proof.
    """
    if index<1 or not np.isfinite([index,background_distance]).all() or background_distance<=surface.maximum_height:raise ValueError('Invalid index or background clearance')
    if not np.isfinite([surface.maximum_height,surface.slope_bound]).all() or surface.maximum_height<0 or surface.slope_bound<0:raise ValueError('Finite nonnegative global surface bounds required')
    limit=np.inf if index==1 else 1/np.sqrt(index*index-1)
    if surface.slope_bound>=limit:raise ValueError('Cannot certify a transmitted branch across footprint')
    x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float))
    if not np.isfinite(x).all() or not np.isfinite(y).all():raise ValueError('Finite view coordinates required')
    h,hx,hy,inside=surface.height_gradient(x,y)
    if np.any(inside&(~np.isfinite(h)|(h<0))):raise ValueError('Invalid physical height inside footprint')
    zero_thickness=inside&(h==0)
    boundary=np.asarray(surface.boundary_mask(x,y),bool)|zero_thickness
    valid_surface=inside&~boundary
    # Do not evaluate an extrapolated bevel as an optical solid outside radius1.
    hx=np.where(valid_surface,hx,0);hy=np.where(valid_surface,hy,0);h=np.where(inside,h,0)
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
                boundary_unresolved=boundary,zero_thickness_unresolved=zero_thickness,
                back=np.where(valid_surface[...,None],np.stack([x,y,-h],axis=-1),np.nan),
                outward_normal=np.where(valid_surface[...,None],-n_against,np.nan),
                exit_direction=direction,discriminant=k,path_length=distance,
                complete=~boundary)


@dataclass(frozen=True)
class ClosedCircularOval:
    """One closed implicit solid; entry, exit, normals and views share F.

    F=x²+y²+(1+kappa*x²)*((z+depth)/depth)²-1.
    Convex on the enclosing |x|<=1 slab for 0<=kappa<=1/3.
    """
    depth: float=.15
    kappa: float=0.

    def __post_init__(self):
        if not np.isfinite([self.depth,self.kappa]).all() or self.depth<=0 or not 0<=self.kappa<=1/3:raise ValueError('Invalid closed oval')

    @property
    def half_extents(self):return (1.,1.,self.depth)
    @property
    def center(self):return (0.,0.,-self.depth)

    def level_gradient(self,points):
        x,y,z=np.moveaxis(np.asarray(points,float),-1,0);u=(z+self.depth)/self.depth
        f=x*x+y*y+(1+self.kappa*x*x)*u*u-1
        gradient=np.stack([2*x*(1+self.kappa*u*u),2*y,2*(1+self.kappa*x*x)*u/self.depth],axis=-1)
        return f,gradient

    def boundary_mask(self,x,y):return np.asarray(x)**2+np.asarray(y)**2==1

    def front_height(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));t=x*x+y*y
        return self.depth*(np.sqrt(np.maximum(1-t,0)/(1+self.kappa*x*x))-1),t<1


def trace_closed_orthographic(surface,x,y,index,background_distance,tolerance=1e-11,max_iterations=64):
    """Ordered primary rays for a separately certified convex implicit solid.

    Uses actual front root, then bounded bisection for the second intersection.
    Total internal reflection, tangent and non-downward exit are INCOMPLETE,
    with NaN background coordinates, never replacement samples. Convexity and
    positive outgoing normal derivative certify no subsequent solid reentry.
    No reflected radiance or Fresnel weighting is implemented.
    """
    ext=np.asarray(surface.half_extents,float);center=np.asarray(surface.center,float)
    if not np.isfinite([index,background_distance,tolerance]).all() or index<1 or background_distance<=-center[2]+ext[2] or tolerance<=0 or max_iterations<1:raise ValueError('Invalid closed-solid scene')
    x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));shape=x.shape
    if not np.isfinite(x).all() or not np.isfinite(y).all():raise ValueError('Finite view coordinates required')
    x=x.ravel();y=y.ravel();z,inside=surface.front_height(x,y);boundary=surface.boundary_mask(x,y)
    X=x.copy();Y=y.copy();complete=~boundary;blocked=np.zeros_like(inside)
    entry=np.full((len(x),3),np.nan);exit=entry.copy();ni=entry.copy();no=entry.copy();internal=entry.copy();outgoing=entry.copy()
    discriminant=np.full(len(x),np.nan);width=np.full(len(x),np.nan);residual=np.full(len(x),np.nan)
    j=np.flatnonzero(inside&~boundary)
    if len(j):
        p=np.stack([x[j],y[j],z[j]],axis=-1);f,g=surface.level_gradient(p)
        if np.any(abs(f)>1e-10) or np.any(~np.isfinite(g)):raise ValueError('Invalid front intersection')
        n=g/np.linalg.norm(g,axis=-1)[:,None];i=np.zeros_like(n);i[:,2]=-1
        d,first_blocked,_=refract(i,n,1.,index)
        if first_blocked.any():raise ValueError('Unavailable entry branch')
        event=next_convex_boundary(surface,p,d,tolerance,max_iterations)
        q=event['point'];nq=event['outward_normal'];width_event=event['bracket'];fq=event['residual']
        e=np.full_like(d,np.nan);tir=np.zeros(len(j),bool);k=np.full(len(j),np.nan);regular=event['complete']
        e[regular],tir[regular],k[regular]=refract(d[regular],-nq[regular],index,1.)
        good=~tir&(e[:,2]<0);distance=np.full(len(j),np.nan)
        distance[good]=(-background_distance-q[good,2])/e[good,2]
        good&=distance>0
        X[j]=np.where(good,q[:,0]+distance*e[:,0],np.nan);Y[j]=np.where(good,q[:,1]+distance*e[:,1],np.nan)
        complete[j]=good;blocked[j]=tir
        entry[j]=p;exit[j]=q;ni[j]=n;no[j]=nq;internal[j]=d;outgoing[j]=e;discriminant[j]=k;width[j]=width_event;residual[j]=fq
    X[boundary]=np.nan;Y[boundary]=np.nan
    scalar=lambda v:v.reshape(shape)
    vector=lambda v:v.reshape(shape+(3,))
    return dict(X=scalar(X),Y=scalar(Y),inside=scalar(inside),complete=scalar(complete),boundary_unresolved=scalar(boundary),blocked=scalar(blocked),entry=vector(entry),exit=vector(exit),entry_normal=vector(ni),exit_normal=vector(no),internal_direction=vector(internal),exit_direction=vector(outgoing),discriminant=scalar(discriminant),intersection_bracket=scalar(width),exit_level_residual=scalar(residual))


def fresnel_components(cosine,n_from,n_to):
    """Power reflectance of an uncoated lossless scalar dielectric interface.

    Positive incidence cosine is measured toward the surface's outward side.
    Caller supplies the actual medium transition; equal-index R=0, critical/TIR R=1.
    Independent of color encoding: these weights must operate on radiance.
    """
    c=np.asarray(cosine,float)
    if not np.isfinite([n_from,n_to]).all() or n_from<=0 or n_to<=0 or not np.isfinite(c).all() or np.any(c< -1e-12) or np.any(c>1+1e-12):raise ValueError('Valid indices and positive incidence cosine required')
    c=np.clip(c,0,1)
    if n_from==n_to:return np.zeros(c.shape+(2,))
    sint2=(n_from/n_to)**2*(1-c*c);blocked=sint2>=1;ct=np.sqrt(np.maximum(1-sint2,0))
    ds=n_from*c+n_to*ct;dp=n_to*c+n_from*ct
    rs=np.divide(n_from*c-n_to*ct,ds,out=np.ones_like(c),where=ds>0)
    rp=np.divide(n_to*c-n_from*ct,dp,out=np.ones_like(c),where=dp>0)
    return np.where(blocked[...,None],1,np.stack([rs*rs,rp*rp],axis=-1))


def fresnel_unpolarized(cosine,n_from,n_to):
    """Unpolarized mean of actual dielectric s/p power reflectances."""
    return fresnel_components(cosine,n_from,n_to).mean(axis=-1)


def reflect_direction(incident,outward_normal):
    """Propagation-vector reflection about the same physical surface normal."""
    i=np.asarray(incident,float);n=np.asarray(outward_normal,float)
    if not np.isfinite(i).all() or not np.isfinite(n).all() or np.any(abs(np.linalg.norm(i,axis=-1)-1)>1e-10) or np.any(abs(np.linalg.norm(n,axis=-1)-1)>1e-10):raise ValueError('Finite unit vectors required')
    return i-2*np.sum(i*n,axis=-1)[...,None]*n


class CircularHeightSolid:
    """Convex closed solid for a separately certified concave circular back.

    The very same height supplies the back level and normal; front is z=0 and
    the footprint r<=1. At an edge-thickness0 crease there is no unique normal.
    This wrapper adds no new geometry parameters or displacement map.
    """
    def __init__(self,back):
        if not isinstance(back,(RadialCurvatureBack,CircularBevel,HarmonicCircularBack)) or back.maximum_height<=0:raise ValueError('A positive certified concave circular height is required')
        self.back=back;self.half_extents=np.array([1.,1.,back.maximum_height/2]);self.center=np.array([0.,0.,-back.maximum_height/2]);self.level_point_evaluations=0;self.maximum_level_points=None

    def level_gradient(self,p):
        p=np.asarray(p,float);count=p[...,0].size
        if self.maximum_level_points is not None and self.level_point_evaluations+count>self.maximum_level_points:raise RuntimeError('Declared level-point budget would be exceeded')
        self.level_point_evaluations+=count;x,y,z=p[...,0],p[...,1],p[...,2];h,hx,hy,_=self.back.height_gradient(x,y);r2=x*x+y*y
        values=np.stack([z,-z-h,r2-1],-1);j=np.argmax(values,-1);ones=np.ones_like(x);zero=np.zeros_like(x)
        g=np.stack([np.stack([zero,zero,ones],-1),np.stack([-hx,-hy,-ones],-1),np.stack([2*x,2*y,zero],-1)],-2)
        return np.take_along_axis(values,j[...,None],-1)[...,0],np.take_along_axis(g,j[...,None,None],-2)[...,0,:]

    def front_height(self,x,y):
        x,y=np.broadcast_arrays(np.asarray(x,float),np.asarray(y,float));return np.zeros_like(x),np.hypot(x,y)<1

    def boundary_mask(self,x,y):return self.back.boundary_mask(x,y)

    def nonsmooth_mask(self,p,uncertainty):
        h,_,_,_=self.back.height_gradient(p[:,0],p[:,1]);near=abs(np.hypot(p[:,0],p[:,1])-1)<=2*uncertainty
        return near&((abs(p[:,2])<=2*uncertainty)|(abs(p[:,2]+h)<=2*uncertainty))


def next_convex_boundary(surface,points,directions,tolerance=1e-11,max_iterations=64):
    """Ordered next exit from an on-boundary inward ray in a convex solid.

    No origin epsilon/nudge. Returns actual bracket/residual and actual normals.
    Surface occupancy/convexity is a required separate certificate. This traces
    a segment, not a background/environment lookup or a complete radiance path.
    """
    p=np.asarray(points,float);d=np.asarray(directions,float);ext=np.asarray(surface.half_extents,float)
    if p.shape!=d.shape or p.ndim!=2 or p.shape[-1]!=3 or not np.isfinite(p).all() or not np.isfinite(d).all() or np.any(abs(np.linalg.norm(d,axis=-1)-1)>1e-10) or not np.isfinite(tolerance) or tolerance<=0 or max_iterations<1:raise ValueError('Finite points, unit directions and positive solver controls required')
    if ext.shape!=(3,) or not np.isfinite(ext).all() or np.any(ext<=0):raise ValueError('Positive finite enclosing box required')
    if not len(p):return dict(point=p.copy(),outward_normal=p.copy(),bracket=np.empty(0),residual=np.empty(0),complete=np.empty(0,bool),iterations=0)
    f0,g0=surface.level_gradient(p)
    if np.any(abs(f0)>1e-9) or np.any(np.sum(g0*d,axis=-1)>=0):raise ValueError('Ray must start on the actual boundary and point inward')
    lo=np.zeros(len(p));hi=np.full(len(p),2*np.linalg.norm(ext)+tolerance);fhi,_=surface.level_gradient(p+hi[:,None]*d)
    if np.any(fhi<=0):raise ValueError('Exit bracket not outside solid')
    for iterations in range(max_iterations):
        mid=(lo+hi)/2;fm,_=surface.level_gradient(p+mid[:,None]*d);lo=np.where(fm<=0,mid,lo);hi=np.where(fm>0,mid,hi)
        if np.max(hi-lo)<=tolerance:break
    else:raise ValueError('Next-boundary solver did not converge')
    t=(lo+hi)/2;q=p+t[:,None]*d;f,g=surface.level_gradient(q);normal=g/np.linalg.norm(g,axis=-1)[:,None]
    if np.any(np.sum(d*normal,axis=-1)<=0):raise ValueError('Wrong next exit root')
    unresolved=surface.nonsmooth_mask(q,hi-lo) if hasattr(surface,'nonsmooth_mask') else np.zeros(len(p),bool)
    return dict(point=q,outward_normal=np.where(unresolved[:,None],np.nan,normal),bracket=hi-lo,residual=f,complete=~unresolved,iterations=iterations+1)
