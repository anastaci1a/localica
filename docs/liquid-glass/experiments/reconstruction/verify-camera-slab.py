"""Independent vector Snell replay and geometric limits, no private images."""
import json
import numpy as np
from camera_slab import slab_map,slab_jacobian,slab_eigenvalues


def transmitted(i,normal,eta):
    cosine=-np.sum(i*normal,axis=-1)
    discriminant=1-eta*eta*(1-cosine*cosine)
    assert np.all(discriminant>0),'Unexpected TIR for this reciprocal slab control'
    return eta*i+(eta*cosine-np.sqrt(discriminant))[...,None]*normal


def trace(p,c,g,h,n):
    i=np.concatenate([p,np.full((*p.shape[:-1],1),-c)],axis=-1)
    i/=np.linalg.norm(i,axis=-1)[...,None]
    t=transmitted(i,np.array([0.,0.,1.]),1/n)
    exit=p+h*t[...,:2]/(-t[...,2,None])
    # Normal faces the incident solid ray at the back interface as well.
    v=transmitted(t,np.array([0.,0.,1.]),n)
    source=exit+(g-h)*v[...,:2]/(-v[...,2,None])
    return source/(1+g/c)


rng=np.random.default_rng(21);points=rng.uniform(-3,3,(4096,2));reports=[]
for c,g,h,n in [(2.,2.,.7,1.5),(10.,4.,3.,1.05),(.5,3.,3.,2.4),(5.,2.,0.,1.5),(5.,2.,1.,1.)]:
    actual=slab_map(points,c,g,h,n);expected=trace(points,c,g,h,n)
    error=float(abs(actual-expected).max());assert error<2e-12
    step=1e-5;numeric=np.stack([(slab_map(points+axis*step,c,g,h,n)-slab_map(points-axis*step,c,g,h,n))/(2*step) for axis in np.eye(2)],axis=-1)
    analytic=slab_jacobian(points,c,g,h,n);derr=float(abs(numeric-analytic).max());assert derr<2e-9
    eigen=slab_eigenvalues(points,c,g,h,n);assert np.all(eigen>0)
    assert np.max(abs(np.linalg.eigvalsh(analytic)-np.sort(eigen,axis=-1)))<1e-12
    scaled=slab_map(points*7,c*7,g*7,h*7,n);assert np.max(abs(scaled-7*actual))<2e-12
    if h==0 or n==1:assert np.array_equal(actual,points)
    reports.append(dict(camera=c,gap=g,thickness=h,index=n,vector_snell_max_error=error,jacobian_max_error=derr,minimum_eigenvalue=float(eigen.min())))
far=slab_map(points,1e9,2.,1.,1.5);assert np.max(abs(far-points))<2e-9
axis=slab_jacobian(np.array([0.,0.]),2.,3.,1.,1.5)
assert np.max(abs(axis-np.eye(2)*(1-1/(2+3)*(1-1/1.5))))<1e-15
for arguments in [(0.,2.,1.,1.5),(2.,0.,0.,1.5),(2.,1.,2.,1.5),(2.,2.,-1.,1.5),(2.,2.,1.,.9),(2.,2.,1.,float('nan'))]:
    try:slab_map(points,*arguments)
    except ValueError:pass
    else:raise AssertionError('Invalid physical input accepted')
print(json.dumps(dict(passed=True,controls=reports,limits='Infinite plane-parallel transmitted slab only. Positive Jacobian here does not rule out folds, TIR, sidewall paths or multi-path images for curved lenses.'),indent=2))
