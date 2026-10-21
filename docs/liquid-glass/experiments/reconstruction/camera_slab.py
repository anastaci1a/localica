"""Finite pinhole camera through an infinite plane-parallel dielectric slab.

Camera (0,0,C), front z=0, back z=-h, texture plane z=-G. The result is in
unlensed image-plane coordinates, not physical background-plane coordinates.
This control has no curved boundary, sidewalls, reflection, blur or Apple fit.
"""
import numpy as np


def _inputs(points, camera, gap, thickness, index):
    p=np.asarray(points,dtype=float)
    c,g,h,n=map(float,(camera,gap,thickness,index))
    if p.ndim<1 or p.shape[-1]!=2 or not np.isfinite(p).all():
        raise ValueError('Finite two-component image-plane coordinates required')
    if not np.isfinite([c,g,h,n]).all() or c<=0 or g<=0 or not 0<=h<=g or n<1:
        raise ValueError('Require C>0, G>0, 0<=h<=G, n>=1')
    return p,c,g,h,n


def slab_map(points,camera,gap,thickness,index):
    p,c,g,h,n=_inputs(points,camera,gap,thickness,index)
    if h==0 or n==1:return p.copy()
    m=p/c
    a=np.sqrt(n*n+(n*n-1)*np.sum(m*m,axis=-1))
    return p+h/(1+g/c)*(m/a[...,None]-m)


def slab_jacobian(points,camera,gap,thickness,index):
    p,c,g,h,n=_inputs(points,camera,gap,thickness,index)
    m=p/c;a=np.sqrt(n*n+(n*n-1)*np.sum(m*m,axis=-1))
    eye=np.broadcast_to(np.eye(2),(*p.shape[:-1],2,2))
    j=eye/a[...,None,None]-(n*n-1)*m[..., :,None]*m[...,None,:]/a[...,None,None]**3
    return eye+h/(c+g)*(j-eye)


def slab_eigenvalues(points,camera,gap,thickness,index):
    """Tangential then radial derivatives; equal at the optical axis."""
    p,c,g,h,n=_inputs(points,camera,gap,thickness,index)
    m=p/c;a=np.sqrt(n*n+(n*n-1)*np.sum(m*m,axis=-1));f=h/(c+g)
    return np.stack([1+f*(1/a-1),1+f*(n*n/a**3-1)],axis=-1)
