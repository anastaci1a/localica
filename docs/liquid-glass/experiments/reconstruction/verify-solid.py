"""Independent geometric/path controls for solid.py; no Apple inputs."""
import numpy as np
from solid import trace,interface,next_boundary,inside,primary_map
from curvature import surface,capsule_offset,footprint

rng=np.random.default_rng(223)
case=dict(half=[80,60],radius=60,exponent=2)
p=dict(height=1,beta=.5,crown=0,shoulder=1,ior=1.33,rear=1.,gap=.3)
x=rng.uniform(-40,40,80);y=rng.uniform(-30,30,80)
z,nx,ny,nz=surface(x,y,case['half'],60,p,2)
P=np.stack([x,y,z],-1);N=np.stack([nx,ny,nz],-1);I=np.zeros_like(P);I[:,2]=-1
D,R,F,valid=interface(I,N,1/1.33)
hit,normal=next_boundary(P,D,case,p)
# Independent quadratic ellipsoid intersection, not the bisection equation.
axes=np.array([80.,60.,60.]);A=np.sum((D/axes)**2,axis=1);B=2*np.sum(P*D/axes**2,axis=1)
expected=P+D*(-B/A)[:,None]
assert np.max(np.abs(hit-expected))<1e-5
assert np.max(np.abs(np.linalg.norm(D,axis=1)-1))<1e-12
out,reflected,F2,valid2=interface(D,-normal,1.33)
sin_in=np.linalg.norm(np.cross(D,normal),axis=1)
sin_out=np.linalg.norm(np.cross(out,normal),axis=1)
assert valid2.all() and np.max(np.abs(1.33*sin_in-sin_out))<1e-12
for t in np.linspace(.01,.99,15):assert inside(P+(hit-P)*t,case,p).all()

result=trace(x,y,case,p,bounces=6)
energy=result['residual']+sum(a['weight'] for a in result['paths'])+sum(a['weight'] for a in result['environment'])
assert np.max(np.abs(energy-1))<1e-12
# Scaling/mirroring preserves the actual ray paths, not a post-warped image.
mirror=trace(-x,y,case,p,bounces=6)
scaledcase=dict(half=[240,180],radius=180,exponent=2)
scaled=trace(3*x,3*y,scaledcase,p,bounces=6)
for a,b,c in zip(result['paths'],mirror['paths'],scaled['paths']):
    assert np.max(np.abs(a['position']*[-1,1]-b['position']))<1e-5
    assert np.max(np.abs(a['position']*3-c['position']))<1e-5
    assert np.max(np.abs(a['weight']-b['weight']))<1e-10

unit=p.copy();unit['ior']=1
identity=trace(x,y,case,unit,bounces=6)
assert np.max(np.abs(identity['paths'][0]['position']-np.stack([x,y],-1)))<1e-10
assert np.max(np.abs(identity['paths'][0]['weight']-1))<1e-12
# TIR must retain reflection and explicitly lack transmission.
I=np.array([[np.sqrt(.75),0,-.5]]);N=np.array([[0.,0,1.]])
out,R,F,valid=interface(I,N,1.5)
assert not valid[0] and F[0]==1 and np.isnan(out).all() and abs(np.linalg.norm(R)-1)<1e-12

# Example with cross-coupled curvature: quiet center and stronger tangential
# magnification away from center emerge from the two curved interfaces.
c=dict(half=[392,170],radius=170)
q=dict(height=.8,beta=.5,crown_x=.4,crown_y=0,cross_curvature=1,shoulder=1,ior=1.33,rear=1.,gap=1.)
x=np.array([40.,80.,140.]);e=.01
def scale(y):
    Y=np.full_like(x,y)
    j=(primary_map(x+e,Y,c,q)[0]-primary_map(x-e,Y,c,q)[0])/(2*e)
    return 1/j
center=scale(0);edge=scale(-150)
assert np.max(np.abs(center-1))<1e-8 and np.min(edge)>1.03

# A capsule is C1 at the straight/cap join but its height curvature jumps there.
# Check the integrated-smoothstep replacement and independent height gradients.
w=20.;s=np.linspace(-25,25,301);e=1e-4
value,derivative=capsule_offset(s,w)
numeric=(capsule_offset(s+e,w)[0]-capsule_offset(s-e,w)[0])/(2*e)
assert np.max(np.abs(numeric-derivative))<1e-8
v,d=capsule_offset(np.array([-w,0,w]),w)
assert np.max(np.abs(v-[0,w*.1875,w]))<1e-12
assert np.max(np.abs(d-[0,.5,1]))<1e-12
# The derivative's derivative approaches zero at both outer joins.
for endpoint in [-w,w]:
 slope=(capsule_offset(np.array([endpoint+e]),w)[1]-capsule_offset(np.array([endpoint-e]),w)[1])/(2*e)
 assert abs(slope[0])<1e-6
for join in [0,.3]:
 p={**q,'cap_join':join,'shoulder':2}
 x=np.array([1.,80.,222.,240.]);y=np.array([20.,30.,40.,60.]);e=1e-4
 z,nx,ny,nz=surface(x,y,c['half'],c['radius'],p)
 dx=(surface(x+e,y,c['half'],c['radius'],p)[0]-surface(x-e,y,c['half'],c['radius'],p)[0])/(2*e)
 dy=(surface(x,y+e,c['half'],c['radius'],p)[0]-surface(x,y-e,c['half'],c['radius'],p)[0])/(2*e)
 assert np.max(np.abs(dx+nx/nz))<1e-6 and np.max(np.abs(dy+ny/nz))<1e-6
print('PASS C2 capsule offset, endpoint derivatives and cross-coupled height normals')
# Crosswise flattening must preserve the silhouette and longitudinal section.
c=dict(half=[118.5,56.5],radius=43.38,oval=[.4994011732582939,-.23031415352591186])
q=dict(height=1.2,beta=.5,crown_x=.6,crown_y=0,cross_curvature=1,shoulder=1,ior=1.33,rear=1,gap=.1)
x=np.array([0.,20.,60.,90.]);y=np.array([3.,10.,20.,18.]);e=1e-4
theta=np.linspace(.01,np.pi-.01,100);u=np.cos(theta);a,b=c['oval'];bx=c['half'][0]*u;by=c['half'][1]*np.sin(theta)*(1+a*u*u+b*u**4)
for power in [2,3,6]:
 p={**q,'cross_section_power':power}
 z,nx,ny,nz=surface(x,y,c['half'],c['radius'],p,0,c['oval'])
 H=lambda X,Y:surface(X,Y,c['half'],c['radius'],p,0,c['oval'])[0]
 dx=(H(x+e,y)-H(x-e,y))/(2*e);dy=(H(x,y+e)-H(x,y-e))/(2*e)
 assert np.max(np.abs(dx+nx/nz))<1e-6 and np.max(np.abs(dy+ny/nz))<1e-6
 original=surface(x,np.zeros_like(x),c['half'],c['radius'],q,0,c['oval'])[0]
 assert np.max(np.abs(H(x,np.zeros_like(x))-original))<1e-12
 field=footprint(bx,by,c['half'],c['radius'],0,c['oval'],0,power)[0]
 assert np.max(np.abs(field-1))<1e-12
print('PASS independent transverse profile normals; silhouette and longitudinal-section invariance')
print('PASS closed-body intersections against analytic ellipsoid; Snell, energy, scale/mirror, unit IOR and explicit TIR')
print('Geometry-only horizontal scale: center',center.tolist(),'at y=-150',edge.tolist())
