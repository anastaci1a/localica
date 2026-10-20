"""Portability and measurement controls; no Apple inputs required."""
import json
from pathlib import Path
import numpy as np
from render import lookup,illumination,shade
from reproduce import masks,points,scores,parameters
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
