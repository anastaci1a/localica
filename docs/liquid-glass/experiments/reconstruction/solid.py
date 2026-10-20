"""Closed smooth lens: geometric ray paths, not a prescribed image warp.

Body: T(x,y)<=1 and -rear*h(x,y)<=z<=h(x,y). Requires a convex
supported height body; arbitrary future parameters are not certified convex.
Background is a plane below the body; other directions see an environment.
Finite internal reflections retain/report their untraced remaining energy.
"""
import numpy as np
from curvature import surface,surface_terms


def interface(direction,normal,eta):
    """Propagation vectors; normal opposes incidence; eta=n_in/n_out.

    TIR has no transmission vector (NaN), with reflected fraction exactly one.
    Never reinterpret failed transmission as a zero direction.
    """
    cosine=-np.sum(direction*normal,axis=-1)
    if np.any(cosine< -1e-6):raise ValueError('Interface normal faces away from incident ray')
    cosine=np.clip(cosine,0,1)
    reflected=direction+2*cosine[...,None]*normal
    if eta==1:return direction.copy(),reflected,np.zeros_like(cosine),np.ones_like(cosine,dtype=bool)
    k=1-eta*eta*(1-cosine*cosine);valid=k>=0;ct=np.sqrt(np.maximum(k,0))
    transmitted=eta*direction+(eta*cosine-ct)[...,None]*normal
    transmitted=np.where(valid[...,None],transmitted,np.nan)
    # Fresnel amplitudes with eta=n_in/n_out (different from the cap helper's n).
    ds=eta*cosine+ct;dp=cosine+eta*ct
    rs=np.divide(eta*cosine-ct,ds,out=np.ones_like(ds),where=ds!=0)
    rp=np.divide(cosine-eta*ct,dp,out=np.ones_like(dp),where=dp!=0)
    F=np.where(valid,.5*(rs*rs+rp*rp),1.)
    return transmitted,reflected,F,valid


def body_height(x,y,case,p):
    h,_,_,_,t=surface_terms(x,y,case['half'],case['radius'],p,case.get('exponent',0),case.get('oval'))
    return np.where(t<=1,np.maximum(h,0),0),t


def inside(point,case,p):
    h,t=body_height(point[...,0],point[...,1],case,p)
    return (t<=1)&(point[...,2]<=h)&(point[...,2]>=-p['rear']*h)


def next_boundary(origin,direction,case,p,iterations=30):
    """Bracket the next exit in the enclosing box, then bisect convex occupancy.

    This finds the first exit for a convex body and an inward ray. Its use on
    nonconvex bodies needs interval/subdivision intersection, not this method.
    """
    H=min(case['half'])*p['height'];epsilon=min(case['half'])*1e-8
    start=origin+direction*epsilon
    low=np.zeros(origin.shape[:-1]);high=np.full_like(low,np.inf)
    for axis,(lower,upper) in enumerate([(-case['half'][0],case['half'][0]),(-case['half'][1],case['half'][1]),(-p['rear']*H,H)]):
        D=direction[...,axis];bound=np.where(D>0,upper,lower)
        distance=np.divide(bound-start[...,axis],D,out=np.full_like(D,np.inf),where=np.abs(D)>1e-14)
        high=np.minimum(high,distance)
    if np.any(high<0) or not np.isfinite(high).all():raise ValueError('Invalid ray/box bracket')
    for _ in range(iterations):
        mid=(low+high)*.5;occupied=inside(start+direction*mid[...,None],case,p)
        low=np.where(occupied,mid,low);high=np.where(occupied,high,mid)
    hit=start+direction*((low+high)*.5)[...,None]
    _,nx,ny,nz=surface(hit[...,0],hit[...,1],case['half'],case['radius'],p,case.get('exponent',0),case.get('oval'))
    back=hit[...,2]<0;scale=np.where(back,p['rear'],1.)
    normal=np.stack([nx*scale,ny*scale,np.where(back,-nz,nz)],axis=-1)
    normal/=np.linalg.norm(normal,axis=-1,keepdims=True)
    return hit,normal


def trace(x,y,case,p,bounces=4,cutoff=1e-5,intersection_steps=30,events=False):
    """Specular path series through both curved interfaces.

    A transmitted exterior ray either hits the backdrop or sees the environment;
    its reflected branch continues inside. This captures TIR without fabricating
    a background lookup. Returned residual bounds the omitted unit-radiance term.
    """
    shape=x.shape;xx=np.asarray(x,dtype=float).ravel();yy=np.asarray(y,dtype=float).ravel()
    h,t=body_height(xx,yy,case,p);mask=t<=1
    xx=np.where(mask,xx,0);yy=np.where(mask,yy,0)
    h,nx,ny,nz=surface(xx,yy,case['half'],case['radius'],p,case.get('exponent',0),case.get('oval'))
    normal=np.stack([nx,ny,nz],axis=-1);incoming=np.zeros_like(normal);incoming[:,2]=-1
    direction,R,F,valid=interface(incoming,normal,1/p['ior'])
    if not valid.all():raise ValueError('Air-entry TIR is outside supported IOR domain')
    origin=np.stack([xx,yy,h],axis=-1);energy=(1-F)*mask
    environments=[dict(direction=R.reshape((*shape,3)),weight=(F*mask).reshape(shape))]
    paths=[];history=[];H=min(case['half'])*p['height'];plane=-p['rear']*H-min(case['half'])*p['gap']
    for _ in range(bounces):
        active=np.flatnonzero(energy>cutoff)
        if not len(active):break
        P=origin[active];D=direction[active];hit,N=next_boundary(P,D,case,p,intersection_steps)
        out,reflected,F,transmits=interface(D,-N,p['ior'])
        weight=energy[active]*(1-F)
        backdrop=transmits&(out[:,2]<-1e-10)
        env=transmits&~backdrop
        point=np.zeros((len(xx),2));W=np.zeros(len(xx));E=np.zeros(len(xx));ER=np.zeros((len(xx),3))
        if backdrop.any():
            travel=(plane-hit[backdrop,2])/out[backdrop,2]
            point[active[backdrop]]=hit[backdrop,:2]+out[backdrop,:2]*travel[:,None]
            W[active[backdrop]]=weight[backdrop]
        if env.any():E[active[env]]=weight[env];ER[active[env]]=out[env]
        paths.append(dict(position=point.reshape((*shape,2)),weight=W.reshape(shape)))
        environments.append(dict(direction=ER.reshape((*shape,3)),weight=E.reshape(shape)))
        if events:history.append(dict(origin=P,direction=D,hit=hit,normal=N,transmission=out,transmits=transmits))
        energy[active]*=F;direction[active]=reflected;origin[active]=hit
    return dict(paths=paths,environment=environments,residual=energy.reshape(shape),events=history)


def primary_map(x,y,case,p):
    """First background path only, for local displacement/Jacobian diagnostics."""
    result=trace(x,y,case,p,bounces=1)
    if not result['paths']:raise ValueError('No transmitted background path')
    path=result['paths'][0]
    if np.any(path['weight']<=0):raise ValueError('Primary ray does not reach background')
    return np.moveaxis(path['position'],-1,0)
