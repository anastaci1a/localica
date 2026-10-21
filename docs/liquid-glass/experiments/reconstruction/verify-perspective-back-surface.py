"""Known quadratic solid: independent forward Snell and inverse PDE controls."""
import json
import numpy as np
from perspective_back_surface import inverse_state,projected_height_gradient
from surface_compatibility import inverse_gradient


def forward(points,c,g,n,K,h0):
    # Solve H=h0+1/2(p+Hw)^T K(p+Hw) on the first, bounded root.
    p=np.asarray(points,float);m=p/c;a=np.sqrt(n*n+(n*n-1)*np.sum(m*m,axis=-1));w=m/a[...,None]
    coefficient=np.einsum('...i,ij,...j->...',w,K,w);linear=np.einsum('...i,ij,...j->...',p,K,w);constant=h0+.5*np.einsum('...i,ij,...j->...',p,K,p)
    root=np.sqrt((1-linear)**2-2*coefficient*constant);H=2*constant/(1-linear+root)
    assert np.all((H>0)&(H<g));q=p+H[...,None]*w;slope=q@K.T
    i=np.concatenate([w,-np.ones((*p.shape[:-1],1))],axis=-1);i/=np.linalg.norm(i,axis=-1)[...,None]
    facing=np.concatenate([slope,np.ones((*p.shape[:-1],1))],axis=-1);facing/=np.linalg.norm(facing,axis=-1)[...,None]
    cosine=-np.sum(i*facing,axis=-1);disc=1-n*n*(1-cosine*cosine);assert np.all(disc>0)
    v=n*i+(n*cosine-np.sqrt(disc))[...,None]*facing
    assert np.all(v[...,2]<0)
    U=q+(g-H)[...,None]*v[...,:2]/(-v[...,2,None])
    return U/(1+g/c),H,slope


x,y=np.meshgrid(np.linspace(-.4,.4,23),np.linspace(-.3,.3,19));p=np.stack([x,y],axis=-1);rows=[]
for c,g,n,K,h0 in [(3.,2.,1.5,np.array([[.08,.02],[.02,.04]]),.4),(8.,3.,1.15,np.array([[.025,-.01],[-.01,.06]]),.55),(2.,2.,1.6,np.zeros((2,2)),.5)]:
    F,H,slope=forward(p,c,g,n,K,h0);state=inverse_state(p,H,F,c,g,n)
    error=float(abs(state['back_gradient']-slope).max());assert error<1e-12
    step=1e-5;derivatives=[];partial=[];map_derivatives=[]
    for axis in np.eye(2):
        plus=forward(p+step*axis,c,g,n,K,h0);minus=forward(p-step*axis,c,g,n,K,h0)
        derivatives.append((plus[1]-minus[1])/(2*step));map_derivatives.append((plus[0]-minus[0])/(2*step))
        partial.append((projected_height_gradient(p+step*axis,H,plus[0],c,g,n)-projected_height_gradient(p-step*axis,H,minus[0],c,g,n))/(2*step))
    gradient=np.stack(derivatives,axis=-1);gerror=float(abs(state['projected_gradient']-gradient).max());assert gerror<1e-9
    kh=(projected_height_gradient(p,H+step,F,c,g,n)-projected_height_gradient(p,H-step,F,c,g,n))/(2*step)
    kg=state['projected_gradient'];residual=partial[0][...,1]-partial[1][...,0]+kg[...,0]*kh[...,1]-kg[...,1]*kh[...,0]
    integrability=float(abs(residual).max());assert integrability<1e-8
    scaled=inverse_state(p*3,H*3,F*3,c*3,g*3,n);assert abs(scaled['projected_gradient']-kg).max()<1e-12
    rows.append(dict(camera=c,index=n,known_surface_slope_error=error,projected_gradient_fd_error=gerror,expanded_integrability_max=integrability,image_map_curl_max=float(abs(map_derivatives[0][...,1]-map_derivatives[1][...,0]).max())))
# Independent orthographic reduction, not a new image-map compatibility claim.
v=np.stack([.02+.01*x,.03+.02*y],-1);H=np.full(x.shape,.4);G=2.;n=1.5;C=1e10
expected=inverse_gradient(v,G-H,n);actual=projected_height_gradient(p,H,p+v,C,G,n)
assert np.max(abs(actual-expected))<1e-10
try:inverse_state(np.array([0.,0.]),.4,np.array([10.,0.]),2.,2.,1.5)
except ValueError:pass
else:raise AssertionError('Wrong Snell branch accepted')
print(json.dumps(dict(passed=True,controls=rows,orthographic_gradient_error=float(abs(actual-expected).max()),limits='Local equation only. Positive thickness, complete sidewall/first-hit paths, global integrability and source support require separate proof.'),indent=2))
