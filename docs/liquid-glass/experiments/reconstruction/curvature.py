"""Curvature hypotheses for the fixed HD cases; not recovered Apple geometry.

`potential` is a gradient image map (thin-phase analogy, not exact ray tracing).
`surface` traces two Snell interfaces through a smooth meridional height field.
The latter's RGB rays approximate dispersion; they are not spectral integration.
"""
import numpy as np
from scipy.interpolate import BSpline


KNOTS=[0]*4+[.015,.04,.09,.2,.45,.75]+[1]*4


def potential(x,y,half,radius,p,boundary,tensor=False):
    sd,nx,ny=boundary;d=-sd
    basis=p.get('_basis')
    if basis is None:
        basis=BSpline.design_matrix(np.clip(d/radius,0,1).ravel(),KNOTS,3).toarray()[:,:7].reshape((*x.shape,7))
    a=min(half)*(basis@np.asarray(p['coefficients']))
    b=radius*p['tangent_band'];u=np.clip(d/b,0,1);v=1-u
    B=v**4*(1+4*u);Bd=-20*u*v**3/b
    # Psi = V(d) + 1/2 K(d)x² + 1/2 cy y², V'=-a, grad(d)=-n.
    # Therefore the normal coupling is required by the same potential that
    # changes tangential magnification. Omitting it breaks mixed derivatives.
    K=p['core_x']+p['tangent']*B;Kd=p['tangent']*Bd
    if tensor:
        # Tensor image field, not a surface/gradient claim. The normal and
        # tangent eigen-directions follow the outline's parallel contours.
        tangent_position=-ny*x+nx*y
        X=(1-p['core_x'])*x-a*nx+p['tangent']*B*tangent_position*ny
        Y=(1-p['core_y'])*y-a*ny-p['tangent']*B*tangent_position*nx
    else:
        normal=-a+.5*Kd*x*x
        X=(1-K)*x+normal*nx;Y=(1-p['core_y'])*y+normal*ny
    return np.where(sd<=0,X,x),np.where(sd<=0,Y,y)


def capsule_offset(s,width):
    """C2 convex positive-part approximation, exact beyond +/-width.

    Integrating cubic smoothstep gives q=w*(2u^3-u^4), u=(s/w+1)/2.
    q, q' and q'' match the flat and linear pieces at their joins. The small
    footprint change is intentional; this is not an exact capsule silhouette.
    """
    if width<0:raise ValueError('Join width must be nonnegative')
    if width==0:return np.maximum(s,0),np.where(s>0,1.,0.)
    u=np.clip((s/width+1)*.5,0,1)
    q=np.where(s>=width,s,width*(2*u**3-u**4))
    return q,u*u*(3-2*u)


def footprint(x,y,half,radius,exponent=0,oval=None,join=0,cross_section_power=1):
    hx,hy=half;n=exponent or 2
    if oval is not None:
        from shapes import oval_field
        t,tx,ty=oval_field(x,y,half,oval)
        U=(x/hx)**2;Ux=2*x/hx**2
    elif not exponent and hx!=hy:
        a=hx-radius
        if join and not (0<join<a):raise ValueError('Capsule join must fit within its straight half-length')
        q,qd=capsule_offset(np.abs(x)-a,join)
        t=(q*q+y*y)/radius**2
        tx=2*q*qd*np.sign(x)/radius**2;ty=2*y/radius**2
        U=q*q/radius**2;Ux=tx
    else:
        t=(np.abs(x)/hx)**n+(np.abs(y)/hy)**n
        tx=n*np.sign(x)*np.abs(x/hx)**(n-1)/hx
        ty=n*np.sign(y)*np.abs(y/hy)**(n-1)/hy
        U=(np.abs(x)/hx)**n;Ux=tx
    if cross_section_power!=1:
        m=cross_section_power
        if m<1:raise ValueError('Cross-section power must be at least one')
        # At each X, replace the normalized transverse quadratic by its m-th
        # power. This preserves T=1 and the longitudinal Y=0 profile, while
        # varying transverse curvature. Outside the body use the old field;
        # this avoids singular perspective extrapolation near |X|=hx.
        S=np.maximum(1-U,1e-12);V=np.maximum(t-U,0);W=np.clip(V/S,0,1)
        Wm=W**m;inside=t<1
        transformed=U+S*Wm
        dx=Ux*(1+(m-1)*Wm)+m*W**(m-1)*(tx-Ux)
        dy=m*W**(m-1)*ty
        t=np.where(inside,transformed,t);tx=np.where(inside,dx,tx);ty=np.where(inside,dy,ty)
    return t,tx,ty


def surface_terms(x,y,half,radius,p,exponent=0,oval=None):
    """Height, unnormalized analytic normal and implicit footprint value."""
    hx,hy=half;t,tx,ty=footprint(x,y,half,radius,exponent,oval,p.get('cap_join',0)*radius,p.get('cross_section_power',1));original_t=t
    original_tx,original_ty=tx,ty
    m=p.get('shoulder',1)
    # m=1 is the simple meridian. Larger m flattens the center continuously,
    # moving curvature toward the rim without a distance-profile inner join.
    if p.get('profile')=='exponential':
        depth=np.maximum(1-t,0);denom=-np.expm1(-m)
        derivative=m*np.exp(-m*depth)/denom
        s=np.maximum(-np.expm1(-m*depth)/denom,1e-12)
    else:
        derivative=m*np.maximum(t,0)**(m-1)
        s=np.maximum(1-np.maximum(t,0)**m,1e-12)
    tx=tx*derivative;ty=ty*derivative
    H=min(half)*p['height'];beta=p['beta']
    c=p.get('crown',0);cx=p.get('crown_x',c*.5);cy=p.get('crown_y',c*.5)
    A=cx*(x/hx)**2+cy*(y/hy)**2;k=p.get('cross_curvature',0)
    # Smooth blend from a broad crown to cross-coupled curvature. Fractional
    # powers T**k would create singular center derivatives for 0<k<1/2.
    W=(1-k)+k*original_t
    DW=np.full_like(original_t,k)
    C=1-A*W
    Cx=-2*cx*x/hx**2*W-A*DW*original_tx
    Cy=-2*cy*y/hy**2*W-A*DW*original_ty
    z=H*s**beta*C
    # Multiply (-grad(z),1) by s**(1-beta), avoiding singular slope division.
    Nx=H*(beta*tx*C-s*Cx)
    Ny=H*(beta*ty*C-s*Cy)
    Nz=s**(1-beta)
    return z,Nx,Ny,Nz,original_t


def surface(x,y,half,radius,p,exponent=0,oval=None):
    """Smooth height and exact analytic unit normal, including the rim limit."""
    z,Nx,Ny,Nz,_=surface_terms(x,y,half,radius,p,exponent,oval)
    norm=np.sqrt(Nx*Nx+Ny*Ny+Nz*Nz)
    return z,Nx/norm,Ny/norm,Nz/norm


def snell_map(x,y,z,Nx,Ny,Nz,ior,gap):
    eta=1/ior;A=-eta*Nz+np.sqrt(1-eta*eta*(1-Nz*Nz))
    tx,ty,tz=-A*Nx,-A*Ny,-eta-A*Nz
    k=1-ior*ior*(1-tz*tz)
    if np.any(k<=0):raise ValueError('Unsupported total internal reflection')
    travel=z/-tz;out=gap/np.sqrt(k)
    return x+tx*(travel+ior*out),y+ty*(travel+ior*out)


def fresnel(cosine,ior):
    eta=1/ior;ct=np.sqrt(1-eta*eta*(1-cosine*cosine))
    rs=(cosine-ior*ct)/(cosine+ior*ct)
    rp=(ior*cosine-ct)/(ior*cosine+ct)
    return .5*(rs*rs+rp*rp)


def transmission_slope(cosine,ior):
    """(1-F)/cos(theta), with its finite grazing limit; requires ior>1.

    Cancelling cosine analytically avoids 0/0 when comparing two almost fully
    reflecting entry interfaces. Subtracting rounded F values loses precision.
    """
    ct=np.sqrt(1-(1-cosine*cosine)/ior**2)
    return 2*ior*ct*((cosine+ior*ct)**-2+(ior*cosine+ct)**-2)


def reflection_basis(x,y,half,p,normals,boundary):
    """Low-order reflected-environment basis, tied to the same surface normal.

    F*E(R) models directional reflection; normal terms allow residual shading.
    No Gaussian distance bands, manually painted side or displaced highlight.
    Coefficients remain fitted encoded-RGB nuisance lighting, not radiance.
    """
    nx,ny,nz=normals;F=fresnel(nz,p.get('ior',1.33))
    rx,ry,rz=2*nz*nx,2*nz*ny,2*nz*nz-1
    X=x/half[0];Y=y/half[1]
    core=np.clip(-boundary[0]/(min(half)*.30),0,1)
    core=core**3*(core*(6*core-15)+10)
    return np.stack([F,F*rx,F*ry,F*rz,F*rx*ry,F*rx*rz,F*ry*rz,
        F*(rx*rx-ry*ry),F*(3*rz*rz-1),X,Y,X*X,Y*Y,X*Y,core],axis=-1)


def colored_reflection_basis(x,y,half,p,normals,boundary,degree=1):
    """First-order RGB environment, evaluated in the reflected direction.

    Three extra nuisance coefficients versus the achromatic basis. This is a
    competing explanation for colored rims, not a measured room environment.
    """
    nx,ny,nz=normals;F=fresnel(nz,p.get('ior',1.33))
    rx,ry,rz=2*nz*nx,2*nz*ny,2*nz*nz-1
    terms=[F,F*rx,F*ry,F*rz]
    if degree==2:terms += [F*rx*ry,F*rx*rz,F*ry*rz,F*(rx*rx-ry*ry),F*(3*rz*rz-1)]
    env=np.stack(terms,axis=-1);count=len(terms)
    X=x/half[0];Y=y/half[1]
    core=np.clip(-boundary[0]/(min(half)*.30),0,1);core=core**3*(core*(6*core-15)+10)
    result=np.zeros((*x.shape,3,3*count+6))
    for channel in range(3):
        result[...,channel,channel*count:(channel+1)*count]=env
    body=np.stack([X,Y,X*X,Y*Y,X*Y,core],axis=-1)
    result[...,3*count:]=body[...,None,:]
    return result
