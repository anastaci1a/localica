"""Portability and measurement controls; no Apple inputs required."""
import json
from pathlib import Path
import numpy as np
from render import lookup,illumination,shade
from reproduce import masks,points,scores,parameters,training_samples,sample_backdrop
from background import paper
from shapes import superellipse,minimum_radius
from scipy.interpolate import BSpline

fixtures=json.loads((Path(__file__).parent.parent/'model-fixtures.json').read_text())
error=0.;count=0
for case in fixtures['cases']:
    x,y=np.array([case['p'][0]],dtype=float),np.array([case['p'][1]],dtype=float)
    half,r=case['half'],case['r']
    for model in ['field','cap']:
        p=case[model+'Options'].copy()
        if model=='cap':
            p['height']/=min(half);p['gap']/=min(half)
        actual=np.array(lookup(x,y,half,r,p,model)).ravel()
        expected=np.array(case[model]['map'])
        error=max(error,float(np.max(np.abs(actual-expected))));count+=1
assert error<1e-8,(count,error)

case=dict(center=[24,24],half=[19,15],radius=12,crop=[0,0,48,48],foreground=[[20,20,28,28]])
xs,ys,x,y=points(case);sd,train,held,valid=masks(case,xs,ys,x,y)
assert not (train&held).any() and np.array_equal(train|held,valid)
assert not valid[20:29,20:29].any()
rng=np.random.default_rng(222);target=rng.random((48,48,3),dtype=np.float32)*.5
identical=scores(target,target,case)
assert identical['all_glass']['rmse']==0 and abs(identical['ssim']-1)<1e-6
shifted=scores(target+.1,target,case)
assert abs(shifted['all_glass']['rmse']-25.5)<1e-4
excluded=target.copy();excluded[~valid]=1
assert scores(excluded,target,case)['all_glass']['rmse']==0
assert scores(np.roll(target,1,axis=1),target,case)['all_glass']['rmse']>20
for _ in range(1000):
    v=[rng.random(),rng.uniform(.03,1),rng.uniform(0,.18),rng.random(),0,1]
    p,_,_=parameters(v,'bounded');rho=p['rim']
    assert p['core']+p['edge']*(rho*16/9+(1-rho)*64/27)<=.980000001
    assert p['core']+p['edge']*(rho*256/243+(1-rho)*1024/729)<=.980000001
print(f'PASS {count} portable map fixtures; maximum absolute error {error:.3g}')
print('PASS identity, known brightness error, spatial shift, excluded-pixel and disjoint-block controls')
print('PASS 1000 constrained-field parameter samples satisfy both sufficient local nonfolding bounds')

x=rng.uniform(-70,70,100);y=rng.uniform(-70,70,100)
sd,nx,ny=superellipse(x,y,[50,50],2)
assert np.max(np.abs(sd-(np.hypot(x,y)-50)))<1e-4
assert np.max(np.abs(np.hypot(nx,ny)-1))<1e-12
t=np.linspace(.001,np.pi/2-.001,80)
x=118.5*np.cos(t)**.8;y=56.5*np.sin(t)**.8
sd,nx,ny=superellipse(x,y,[118.5,56.5],2.5)
assert np.max(np.abs(sd))<1e-4
assert 35.65<minimum_radius([118.5,56.5],2.5)<35.67
knots=[0]*4+[.015,.04,.09,.2,.45,.75]+[1]*4
bs=BSpline(knots,[.8,.5,.2,.6,.3,.1,.02,0,0,0],3)
assert all(abs(bs(1,nu=i))<1e-10 for i in range(3))
zero=dict(coefficients=[0]*7,core_x=0,core_y=0)
assert np.max(np.abs(np.array(lookup(x,y,[120,60],50,zero,'spline'))-[x,y]))==0
print('PASS continuous-oval distance/normal/curvature checks and spline C2/zero-power limits')

yy,xx=np.mgrid[:96,:160];base=np.stack([.8+.02*xx/160,.81+.02*yy/96,np.full(xx.shape,.84)],axis=-1).astype(np.float32)
image=base.copy();image[10:20,10:110]=0;image[40:60,60:100]=.7
excluded=np.zeros(xx.shape,bool);excluded[40:60,60:100]=True
field,evidence=paper(image,excluded)
assert np.max(np.abs(field-base))<1e-5
light=illumination(np.array([0.,10.]),np.array([0.,10.]),[100,50],50,4)
co=np.zeros(23);co[:3]=co[-3:]=1
sample=np.array([[.2,.3,.4],[.6,.7,.8]])
assert np.max(np.abs(shade(sample,light,co)-sample))<1e-12
co[-3:]=.5
expected=sample*(1-.5*light[:,-1,None])
assert np.max(np.abs(shade(sample,light,co)-expected))<1e-12
print('PASS recovered background field without a color patch; identity/core-contrast material controls')

from curvature import surface,snell_map,fresnel
from render import geometry,fit_material
# Independent height differences validate the analytic normal, including crown
# and the continuous center-to-rim shoulder. Stay away from the silhouette.
for exponent,half in [(0,[60,60]),(2.5,[120,60])]:
    p=dict(height=.7,gap=.4,beta=.55,crown=.12,shoulder=5,ior=1.33)
    x=np.array([3.,21.,45.]);y=np.array([5.,15.,20.]);eps=1e-4
    z,nx,ny,nz=surface(x,y,half,60,p,exponent)
    dx=(surface(x+eps,y,half,60,p,exponent)[0]-surface(x-eps,y,half,60,p,exponent)[0])/(2*eps)
    dy=(surface(x,y+eps,half,60,p,exponent)[0]-surface(x,y-eps,half,60,p,exponent)[0])/(2*eps)
    assert np.max(np.abs(dx+nx/nz))<1e-6 and np.max(np.abs(dy+ny/nz))<1e-6
    assert np.max(np.abs(nx*nx+ny*ny+nz*nz-1))<1e-12
    # Geometric scale covariance and mirror symmetry, not image-specific checks.
    a=np.array(snell_map(x,y,z,nx,ny,nz,1.33,24))
    z2,*N2=surface(3*x,3*y,np.array(half)*3,180,p,exponent)
    assert np.max(np.abs(np.array(snell_map(3*x,3*y,z2,*N2,1.33,72))-3*a))<1e-9
    zm,*Nm=surface(-x,y,half,60,p,exponent)
    am=np.array(snell_map(-x,y,zm,*Nm,1.33,24))
    assert np.max(np.abs(am-a*np.array([[-1],[1]])))<1e-9
    assert np.max(np.abs(np.array(snell_map(x,y,z,nx,ny,nz,1,24))-[x,y]))<1e-9
assert abs(fresnel(np.array([1.]),1.33)[0]-((1.33-1)/(1.33+1))**2)<1e-12
assert abs(fresnel(np.array([0.]),1.33)[0]-1)<1e-12

p=dict(coefficients=[.2,.4,.5,.4,.3,.2,.1],core_x=.01,core_y=.02,tangent=.4,tangent_band=.5)
x=np.array([12.,70.]);y=np.array([-51.,-43.]);h=[120,60];r=60;eps=1e-4
def mapped(a,b):return np.array(lookup(a,b,h,r,p,'potential'))
dx=(mapped(x+eps,y)-mapped(x-eps,y))/(2*eps)
dy=(mapped(x,y+eps)-mapped(x,y-eps))/(2*eps)
assert np.max(np.abs(dx[1]-dy[0]))<1e-6,'Potential mixed derivatives must agree'
p['tensor']=True
a=np.array(lookup(x,y,h,r,p,'tensor'));u=np.clip((60-np.abs(y))/30,0,1)
# First point is on a straight side, so tangential derivative is explicit.
dx=(np.array(lookup(x+eps,y,h,r,p,'tensor'))-np.array(lookup(x-eps,y,h,r,p,'tensor')))/(2*eps)
assert abs(dx[0,0]-(1-p['core_x']-p['tangent']*(1-u[0])**4*(1+4*u[0])))<1e-8

# Subpixel regression must average products, not multiply average quantities.
sample=rng.uniform(.1,.8,(100,4,3));light=rng.uniform(-.2,.2,(100,4,15));light[...,-1]=rng.uniform(0,1,(100,4))
co=np.zeros(23);co[:3]=.6;co[-3:]=1.1;co[3:6]=.02;co[6:10]=.01
expected=shade(sample,light,co).mean(1)
fitted,_=fit_material(sample,light,expected,np.ones(100))
assert np.sqrt(np.mean((shade(sample,light,fitted).mean(1)-expected)**2))<1e-5
print('PASS smooth-surface analytic normals, scale/mirror/Snell/Fresnel limits, potential derivatives, tensor scale and integrated material regression')

xs,ys,x,y,indices,weights=training_samples(case)
assert set(zip((xs.flat[indices]%2).astype(int),(ys.flat[indices]%2).astype(int)))=={(0,0),(0,1),(1,0),(1,1)}
odd=target.copy();odd[1::2]+=.05
assert scores(odd,target,case)['selection_loss']>0,'Native subset must see odd-row detail'
from curvature import colored_reflection_basis
normals=surface(np.array([3.,10.]),np.array([5.,12.]),[19,19],19,dict(height=.5,beta=.5,crown=0,shoulder=2))[1:]
light=colored_reflection_basis(np.array([3.,10.]),np.array([5.,12.]),[19,19],dict(ior=1.33),normals,(np.array([-10.,-8.]),None,None))
co=np.zeros(26);co[:3]=co[-3:]=1
sample=np.array([[.2,.3,.4],[.3,.4,.5]])
assert np.max(np.abs(shade(sample,light,co)-sample))<1e-12
testcase=dict(center=[40,40],half=[19,19],radius=19)
x=np.array([2.,5.]);y=np.array([3.,6.]);boundary=geometry(x,y,[19,19],19)
source=np.full((80,80,3),.4,np.float32)
p=dict(height=.5,gap=.4,beta=.5,crown=0,shoulder=2,ior=1.33,dispersion=0)
f=dict(model='spectral',parameters=p)
samples=sample_backdrop(source,source,testcase,f,x,y,boundary)
assert np.max(np.abs(samples-.4))<1e-6
print('PASS native subset includes every pixel parity; RGB reflection identity and constant-source spectral control')

if __name__=='__main__':
    import argparse
    ap=argparse.ArgumentParser();ap.add_argument('--figures',type=Path);args=ap.parse_args()
    if args.figures:
        from publish import read,glyph,compose,coverage
        from reproduce import CASES
        for name,probes in [('media',[(900,470),(950,600)]),('regular',[(780,542),(1143,542)])]:
            target=read(args.figures/f'{name}-target.png');mask=glyph(name,target)>0
            for x,y in probes:assert mask[y,x],(name,x,y,'Missing reference symbol segment')
            report=json.loads((args.figures/f'{name}-report.json').read_text())
            model=min(['field','cap','bounded','spline'],key=lambda m:report['models'][m]['fit_loss'])
            output=compose(name,CASES[name],target,read(args.figures/f'{name}-{model}.png'),args.figures)
            assert np.array_equal(output[mask],target[mask])
            cov=coverage(name);assert cov.min()>=0 and cov.max()<=1
        print('PASS native reference symbols including play gradient/archive dash; integrated shape coverage')
