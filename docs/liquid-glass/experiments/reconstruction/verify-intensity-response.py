"""Identity, bounded monotonicity and renderer placement of the shared curve."""
import copy
import json
from pathlib import Path
import cv2
import numpy as np
from crowned_tensor import intensity_response, render

cv2.setNumThreads(1)
x=np.linspace(0,1,65537)
assert intensity_response(x,{}) is x
assert intensity_response(x,{'intensity_response':dict(kind='power',gamma=1)}) is x
for gamma in [.8,1,1.0456395525912732,1.25]:
    y=intensity_response(x,{'intensity_response':dict(kind='power',gamma=gamma)})
    assert y[0]==0 and y[-1]==1 and np.all(np.diff(y)>0)
for invalid in [0,-1,float('nan'),float('inf')]:
    try:intensity_response(x,{'intensity_response':dict(kind='power',gamma=invalid)})
    except ValueError:pass
    else:raise AssertionError('Invalid response accepted')

p=json.loads((Path(__file__).parent/'studies/archive-plus/review-round/pass-02.json').read_text())
# Small independently scaled geometry, same normalized optical/material order.
case=copy.deepcopy(p['case']);case.update(half=[24,12],radius=12,center=[48,32],crop=[16,16,80,48])
p['material']=np.r_[np.ones(3),np.zeros(17),np.ones(3)].tolist()
p['intensity_response']=dict(kind='power',gamma=1.25)
errors=[]
for value,scale in [(0.,1),(.43,1),(1.,1),(.43,2)]:
    C,alpha=render(np.full((64,96,3),value),case,p,scale=scale)
    error=float(abs(C[alpha>0]-value**1.25).max());assert error<3e-7
    errors.append(dict(input=value,scale=scale,max_error=error))
print(json.dumps(dict(passed=True,uniform_controls=errors,limits='Positive power with positive gains and fixed additive lighting is nondecreasing. This does not identify an Apple material.'),indent=2))
