"""Curvature hypotheses for the fixed HD cases; not recovered Apple geometry.

`potential` is a gradient image map (thin-phase analogy, not exact ray tracing).
`surface` traces two Snell interfaces through a smooth meridional height field.
The latter's RGB rays approximate dispersion; they are not spectral integration.
"""
import numpy as np
from scipy.interpolate import BSpline


KNOTS=[0]*4+[.015,.04,.09,.2,.45,.75]+[1]*4


def potential(x,y,half,radius,p,boundary):
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
    if p.get('tensor'):
        # Tensor image field, not a surface/gradient claim. The normal and
        # tangent eigen-directions follow the outline's parallel contours.
        tangent_position=-ny*x+nx*y
        X=(1-p['core_x'])*x-a*nx+p['tangent']*B*tangent_position*ny
        Y=(1-p['core_y'])*y-a*ny-p['tangent']*B*tangent_position*nx
    else:
        normal=-a+.5*Kd*x*x
        X=(1-K)*x+normal*nx;Y=(1-p['core_y'])*y+normal*ny
    return np.where(sd<=0,X,x),np.where(sd<=0,Y,y)


def surface(x,y,half,radius,p,exponent=0):
    """z=H (1-t)^beta C, on t<1. No inner shoulder or profile join.

    t is the outline's implicit radial field. Its exponent is a footprint
    parameter; beta and crown control height/curvature independently.
    Returned normal is the exact analytic normal, including its rim limit.
    """
    hx,hy=half;n=exponent or 2
    if not exponent and hx!=hy:
        a=hx-radius;q=np.maximum(np.abs(x)-a,0)
        t=(q*q+y*y)/radius**2
        tx=2*q*np.sign(x)/radius**2;ty=2*y/radius**2
    else:
        t=(np.abs(x)/hx)**n+(np.abs(y)/hy)**n
        tx=n*np.sign(x)*np.abs(x/hx)**(n-1)/hx
        ty=n*np.sign(y)*np.abs(y/hy)**(n-1)/hy
    m=p.get('shoulder',1)
    # m=1 is the simple meridian. Larger m flattens the center continuously,
    # moving curvature toward the rim without a distance-profile inner join.
    derivative=m*np.maximum(t,0)**(m-1)
    t=np.maximum(t,0)**m;tx=tx*derivative;ty=ty*derivative
    s=np.maximum(1-t,1e-12);H=min(half)*p['height'];beta=p['beta']
    c=p.get('crown',0);C=1-c*.5*((x/hx)**2+(y/hy)**2)
    z=H*s**beta*C
    # Multiply (-grad(z),1) by s**(1-beta), avoiding singular slope division.
    Nx=H*(beta*tx*C+s*c*x/hx**2)
    Ny=H*(beta*ty*C+s*c*y/hy**2)
    Nz=s**(1-beta);norm=np.sqrt(Nx*Nx+Ny*Ny+Nz*Nz)
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


def colored_reflection_basis(x,y,half,p,normals,boundary):
    """First-order RGB environment, evaluated in the reflected direction.

    Three extra nuisance coefficients versus the achromatic basis. This is a
    competing explanation for colored rims, not a measured room environment.
    """
    nx,ny,nz=normals;F=fresnel(nz,p.get('ior',1.33))
    env=np.stack([F,F*2*nz*nx,F*2*nz*ny,F*(2*nz*nz-1)],axis=-1)
    X=x/half[0];Y=y/half[1]
    core=np.clip(-boundary[0]/(min(half)*.30),0,1);core=core**3*(core*(6*core-15)+10)
    result=np.zeros((*x.shape,3,18))
    for channel in range(3):
        result[...,channel,channel*4:channel*4+4]=env
    body=np.stack([X,Y,X*X,Y*Y,X*Y,core],axis=-1)
    result[...,12:]=body[...,None,:]
    return result
