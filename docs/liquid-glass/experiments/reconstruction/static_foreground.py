"""One shared constant foreground color/opacity over clean vector coverage.

P=(1-a)B+a(t B+q), t=1-opacity, q=opacity*color.
0<=t<=1 and0<=q_c<=1-t keep the response monotone and in gamut.
The fit cannot change either scene's glass, vector geometry or reference mask.
"""
import numpy as np
from scipy.optimize import minimize
from vector_foreground import coverage


def fit_shared(studies,p,co,styles=None):
    samples=[];asset=None;default=None
    for s in studies:
        pred=s.render(p,np.asarray(co));x0,y0,_,_=s.crop
        layers=[x for x in s.case['foreground_layers'] if 'vector_asset' in x and x['mode']!='transmission']
        assert len(layers)==1,'Fit one static vector at a time'
        layer=layers[0]
        if asset is None:asset=layer['vector_asset'];default=dict(color=layer['color'],opacity=layer.get('opacity',1.));digest=layer['vector_sha256']
        assert asset==layer['vector_asset'] and digest==layer['vector_sha256'],'Require identical vector geometry'
        a,b,c,d=layer['box'];samples.append((pred[b-y0:d-y0,a-x0:c-x0],layer['target'] if 'target' in layer else s.target[b:d,a:c],coverage(s.folder,layer)))
    style=(styles or {}).get(asset,default);opacity=style['opacity'];base=np.array([1-opacity,*np.asarray(style['color'])*opacity])
    def errors(v):
        return np.array([float(np.mean((B*(1-A[...,None])+A[...,None]*(v[0]*B+v[1:])-T)**2))*255**2 for B,T,A in samples])
    before=errors(base);scale=max(1,float(before.mean()))
    def constraints(v):return np.r_[1-v[0]-v[1:],(before-errors(v))/scale]
    result=minimize(lambda v:float(errors(v).mean())/scale,base,method='SLSQP',bounds=[(0,1)]*4,constraints=[dict(type='ineq',fun=constraints)],options=dict(maxiter=200,ftol=1e-12))
    best=base;after=before
    for t in [1,.5,.25,.125,.0625,.03125,.015625]:
        v=base+t*(result.x-base);trial=errors(v)
        if np.all(constraints(v)>=-1e-8) and np.all(trial<=after+1e-8) and trial.sum()<after.sum():best=v;after=trial
    opacity=float(1-best[0]);color=(best[1:]/opacity).tolist() if opacity>1e-9 else [0.,0.,0.]
    shared={**(styles or {}),asset:dict(color=color,opacity=opacity)}
    return shared,dict(before_mse=before.tolist(),after_mse=after.tolist(),optimizer_evaluations=int(result.nfev),formula='P=(1-a)B+a(t*B+q); t=1-opacity, q=opacity*color; one shared color/opacity')
