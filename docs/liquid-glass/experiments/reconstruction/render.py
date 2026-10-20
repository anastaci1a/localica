"""Vectorized portable optical hypotheses plus a separately fitted image material.

Optical formulas mirror ../../model.js; this is an offline comparison renderer,
not the live lab's nine-tap/synthetic-light material. Inputs/outputs are encoded RGB.
"""
import numpy as np
from shapes import superellipse
from scipy.interpolate import BSpline
from scipy.optimize import lsq_linear


def spline_basis(distance,radius):
    knots=[0]*4+[.015,.04,.09,.2,.45,.75]+[1]*4
    t=np.clip(distance/radius,0,1)
    return BSpline.design_matrix(t.ravel(),knots,3).toarray()[:,:7].reshape((*t.shape,7))


def case_geometry(case,x,y):
    if case.get('exponent'):return superellipse(x,y,case['half'],case['exponent'])
    return geometry(x,y,case['half'],case['radius'])


def geometry(x, y, half, radius):
    qx,qy=np.abs(x)-half[0]+radius,np.abs(y)-half[1]+radius
    ax,ay=np.maximum(qx,0),np.maximum(qy,0)
    length=np.hypot(ax,ay)
    sd=length+np.minimum(np.maximum(qx,qy),0)-radius
    nx=np.where(length>0,ax/np.maximum(length,1e-20)*np.sign(x),np.where(qx>qy,np.sign(x),0))
    ny=np.where(length>0,ay/np.maximum(length,1e-20)*np.sign(y),np.where(qx>qy,0,np.sign(y)))
    return sd,nx,ny


def lookup(x,y,half,radius,parameters,model='field',boundary=None,exponent=0):
    sd,nx,ny=geometry(x,y,half,radius) if boundary is None else boundary
    if model=='identity':return x.copy(),y.copy()
    b=min(radius,min(half)*parameters.get('band',1))
    u=np.clip(-sd/b,0,1);v=1-u;S=u**3*(u*(6*u-15)+10)
    if model=='spline':
        basis=parameters.get('_basis')
        if basis is None:basis=spline_basis(-sd,radius)
        a=min(half)*(basis@np.array(parameters['coefficients']))
        coreS=np.clip(-sd/radius,0,1);coreS=coreS**3*(coreS*(6*coreS-15)+10)
        X=(1-parameters['core_x']*coreS)*x-a*nx
        Y=(1-parameters['core_y']*coreS)*y-a*ny
    elif model=='field':
        rho=parameters.get('rim',.35);kind=parameters.get('kind',2)
        P=16*u*u*v*v if kind==0 else v**3*(1+3*u) if kind==1 else rho*v**3*(1+3*u)+(1-rho)*256/27*u*v**3
        a=b*parameters['edge']*P
        X=(1-parameters['core']*S)*x-a*nx;Y=(1-parameters['core']*S)*y-a*ny
    elif model=='cap':
        H=min(half)*parameters['height'];G=min(half)*parameters['gap'];ior=parameters['ior']
        if H==0 or ior==1:return x.copy(),y.copy()
        f2=1-v**3;f=np.sqrt(f2);C=1-.036*(x*x/half[0]**2+y*y/half[1]**2)
        Nx=H*(1.5*v*v/b*C*nx+f2*.072*x/half[0]**2)
        Ny=H*(1.5*v*v/b*C*ny+f2*.072*y/half[1]**2)
        size=np.sqrt(Nx*Nx+Ny*Ny+f2);Nx/=size;Ny/=size;Nz=f/size
        eta=1/ior;A=-eta*Nz+np.sqrt(1-eta*eta*(1-Nz*Nz))
        tx,ty,tz=-A*Nx,-A*Ny,-eta-A*Nz
        exitK=1-ior*ior*(1-tz*tz)
        if np.any(exitK<0):raise ValueError('TIR outside comparator domain')
        travel=H*f*C/-tz;outTravel=G/np.sqrt(exitK)
        baseX,baseY=x+tx*travel,y+ty*travel
        if exponent:base_sd=(np.abs(baseX)/half[0])**exponent+(np.abs(baseY)/half[1])**exponent-1
        else:base_sd,*_=geometry(baseX,baseY,half,radius)
        if np.any((sd<=0)&(base_sd>1e-5)):raise ValueError('Plane exit outside cap body')
        X=x+tx*travel+ior*tx*outTravel;Y=y+ty*travel+ior*ty*outTravel
    else:raise ValueError(model)
    return np.where(sd<=0,X,x),np.where(sd<=0,Y,y)


def illumination(x,y,half,radius,rim_width,boundary=None):
    sd,nx,ny=geometry(x,y,half,radius) if boundary is None else boundary;d=np.maximum(-sd,0)
    # Low-order directional light basis. No per-pixel residual/detail fitting.
    thin=np.exp(-(d/1.4)**2)
    e=np.exp(-(d/rim_width)**2);f=np.exp(-(d/(rim_width*4))**2)
    X=x/half[0];Y=y/half[1]
    core=np.clip(d/(min(half)*.30),0,1);core=core**3*(core*(6*core-15)+10)
    return np.stack([thin,thin*nx,thin*ny,e,e*nx,e*ny,f,f*nx,f*ny,X,Y,X*X,Y*Y,X*Y,core],axis=-1)


def fit_material(sample,light,target,weights):
    """3 rim gains + 3 offsets + 14 light coefficients + 3 core gains.
    Same nuisance freedom for identity, default optics and every fitted model.
    """
    n=len(sample);count=6+light.shape[-1]-1+3;A=np.zeros((n,3,count),np.float64)
    for c in range(3):A[:,c,c]=sample[:,c]*(1-light[:,-1]);A[:,c,c+3]=1
    A[:,:,6:-3]=light[:,None,:-1]
    for c in range(3):A[:,c,-3+c]=sample[:,c]*light[:,-1]
    a=A.reshape(-1,count)*np.repeat(weights,3)[:,None];y=target.reshape(-1)*np.repeat(weights,3)
    # Bounded least squares, not an unconstrained answer followed by an arbitrary
    # optical-loss penalty. Reduce to a small Cholesky system; a weak ridge keeps
    # grayscale/constant inputs identifiable enough for numerical optimization.
    gram=a.T@a;rhs=a.T@y;ridge=1e-8*max(float(np.diag(gram).max()),1)
    prior=np.zeros(count);prior[:3]=prior[-3:]=1
    gram+=np.eye(count)*ridge;rhs+=ridge*prior
    lower=np.full(count,-.8);upper=np.full(count,.8)
    lower[:3]=lower[-3:]=.05;upper[:3]=upper[-3:]=1.5
    co=np.linalg.solve(gram,rhs)
    if np.any(co<lower) or np.any(co>upper):
        L=np.linalg.cholesky(gram)
        result=lsq_linear(L.T,np.linalg.solve(L,rhs),bounds=(lower,upper),method='bvls',tol=1e-8,max_iter=100)
        if not result.success:raise RuntimeError(result.message)
        co=result.x
    return co,0.


def shade(sample,light,co):
    gain=(1-light[...,-1,None])*co[:3]+light[...,-1,None]*co[-3:]
    return np.clip(sample*gain+co[3:6]+(light[...,:-1]@co[6:-3])[...,None],0,1)
