"""Portability and measurement controls; no Apple inputs required."""
import json
from pathlib import Path
import numpy as np
from render import geometry,lookup
from reproduce import masks,points,scores,parameters

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
