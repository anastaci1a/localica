"""Inverse height-gradient equation for a finite camera and flat lens front.

Camera (0,0,C), front z=0, back z=-h(q), backdrop z=-G, index n>1.
H(p)=h(q(p)), q=p+H(p)w(p). F is in unlensed image-plane coordinates.
This local necessary equation does not establish a complete first-hit solid.
"""
import numpy as np


def inverse_state(points,height,mapping,camera,gap,index):
    p=np.asarray(points,float);F=np.asarray(mapping,float);H=np.asarray(height,float)
    C,G,n=map(float,(camera,gap,index))
    if p.ndim<1 or p.shape[-1]!=2 or F.shape!=p.shape:
        raise ValueError('Matching two-component points and inverse map required')
    if not np.isfinite([C,G,n]).all() or C<=0 or G<=0 or n<=1:
        raise ValueError('Require finite C>0, G>0, n>1')
    if not np.isfinite(p).all() or not np.isfinite(F).all() or not np.isfinite(H).all() or np.any((H<=0)|(H>=G)):
        raise ValueError('Require finite rays with 0<H<G')
    H=np.broadcast_to(H,p.shape[:-1]);m=p/C;a=np.sqrt(n*n+(n*n-1)*np.sum(m*m,axis=-1))
    w=m/a[...,None];eye=np.broadcast_to(np.eye(2),(*p.shape[:-1],2,2))
    Dw=(eye/a[...,None,None]-(n*n-1)*m[..., :,None]*m[...,None,:]/a[...,None,None]**3)/C
    t=np.concatenate([w,-np.ones((*p.shape[:-1],1))],axis=-1);t/=np.linalg.norm(t,axis=-1)[...,None]
    q=p+H[...,None]*w;U=(1+G/C)*F
    v=np.concatenate([U-q,-(G-H)[...,None]],axis=-1);v/=np.linalg.norm(v,axis=-1)[...,None]
    normal=n*t-v
    # n*t-v must face the exterior below the graph; v must be on its outgoing
    # side. Squaring Snell's equation without this test admits a wrong branch.
    if np.any(normal[...,2]>=0) or np.any(n*np.sum(t*v,axis=-1)<=1):
        raise ValueError('Wrong transmitted back-surface branch')
    slope=normal[...,:2]/normal[...,2,None]
    denominator=1-np.sum(w*slope,axis=-1)
    if np.any(denominator<=0):raise ValueError('Tangent or non-first local back intersection')
    A=eye+H[...,None,None]*Dw
    grad=np.einsum('...ji,...j->...i',A,slope)/denominator[...,None]
    return dict(projected_gradient=grad,back_gradient=slope,back_point=q,internal_direction=t,outgoing_direction=v)


def projected_height_gradient(points,height,mapping,camera,gap,index):
    return inverse_state(points,height,mapping,camera,gap,index)['projected_gradient']
