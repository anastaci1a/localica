"""Vectorized portable optical hypotheses plus a separately fitted image material.

Optical formulas mirror ../../model.js; this is an offline comparison renderer,
not the live lab's nine-tap/synthetic-light material. Inputs/outputs are encoded RGB.
"""
import cv2
import numpy as np


def geometry(x, y, half, radius):
    qx,qy=np.abs(x)-half[0]+radius,np.abs(y)-half[1]+radius
    ax,ay=np.maximum(qx,0),np.maximum(qy,0)
    length=np.hypot(ax,ay)
    sd=length+np.minimum(np.maximum(qx,qy),0)-radius
    nx=np.where(length>0,ax/np.maximum(length,1e-20)*np.sign(x),np.where(qx>qy,np.sign(x),0))
    ny=np.where(length>0,ay/np.maximum(length,1e-20)*np.sign(y),np.where(qx>qy,0,np.sign(y)))
    return sd,nx,ny


def lookup(x,y,half,radius,parameters,model='field'):
    sd,nx,ny=geometry(x,y,half,radius)
    if model=='identity':return x.copy(),y.copy()
    b=min(radius,min(half)*parameters['band'])
    u=np.clip(-sd/b,0,1);v=1-u;S=u**3*(u*(6*u-15)+10)
    if model=='field':
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
        base_sd,*_=geometry(x+tx*travel,y+ty*travel,half,radius)
        if np.any((sd<=0)&(base_sd>1e-5)):raise ValueError('Plane exit outside cap body')
        X=x+tx*travel+ior*tx*outTravel;Y=y+ty*travel+ior*ty*outTravel
    else:raise ValueError(model)
    return np.where(sd<=0,X,x),np.where(sd<=0,Y,y)


def illumination(x,y,half,radius,rim_width):
    sd,nx,ny=geometry(x,y,half,radius);d=np.maximum(-sd,0)
    # Low-order directional light basis. No per-pixel residual/detail fitting.
    thin=np.exp(-(d/1.4)**2)
    e=np.exp(-(d/rim_width)**2);f=np.exp(-(d/(rim_width*4))**2)
    return np.stack([thin,thin*nx,thin*ny,e,e*nx,e*ny,f,f*nx,f*ny],axis=-1)


def fit_material(sample,light,target,weights):
    """3 gains + 3 offsets + 9 shared achromatic rim coefficients.
    Same nuisance freedom for identity, default optics and every fitted model.
    """
    n=len(sample);count=6+light.shape[-1];A=np.zeros((n,3,count),np.float64)
    for c in range(3):A[:,c,c]=sample[:,c];A[:,c,c+3]=1
    A[:,:,6:]=light[:,None,:]
    a=A.reshape(-1,count)*np.repeat(weights,3)[:,None];y=target.reshape(-1)*np.repeat(weights,3)
    co=np.linalg.lstsq(a,y,rcond=None)[0]
    # Reject implausible cancellation between optical detail and material.
    invalid=np.maximum(.15-co[:3],0).sum()+np.maximum(co[:3]-1.5,0).sum()
    invalid+=np.maximum(np.abs(co[3:])-.8,0).sum()
    return co,invalid


def shade(sample,light,co):
    return np.clip(sample*co[:3]+co[3:6]+(light@co[6:])[...,None],0,1)
