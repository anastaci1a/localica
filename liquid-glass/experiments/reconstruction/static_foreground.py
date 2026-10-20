"""One shared constant foreground color/opacity over clean vector coverage.

P=(1-a)B+a(t B+q), t=1-opacity, q=opacity*color.
0<=t<=1 and0<=q_c<=1-t keep the response monotone and in gamut.
The fit cannot change either scene's glass, vector geometry or reference mask.
"""
import numpy as np
from scipy.optimize import minimize
from vector_foreground import coverage


def fit_samples(samples,style):
    assert 'response' not in style,'Use the response fitter for a nonlinear foreground'
    opacity=style['opacity'];base=np.array([1-opacity,*np.asarray(style['color'])*opacity])
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
    return dict(color=color,opacity=opacity),before,after,int(result.nfev)


def fit_shared(studies,p,co,styles=None):
    groups={};defaults={};digests={};order=None
    for s in studies:
        pred=s.render(p,np.asarray(co));x0,y0,_,_=s.crop;current=[]
        for layer in s.case['foreground_layers']:
            if 'vector_asset' not in layer or layer['mode']=='transmission' or not layer.get('fit_style',True):continue
            key=layer.get('style_key',layer['vector_asset']);current.append(key)
            assert key not in current[:-1],'Use distinct style keys for repeated placements'
            assert digests.get(key,layer['vector_sha256'])==layer['vector_sha256'],'Require identical vector geometry'
            digests[key]=layer['vector_sha256'];defaults[key]=dict(color=layer['color'],opacity=layer.get('opacity',1.))
            # A child symbol sees already-composited fixed content, such as
            # a selection backing. Do not fit it against the bare glass.
            background=s.apply_foreground(pred.copy(),stop_before=layer['box'])
            a,b,c,d=layer['box'];groups.setdefault(key,[]).append((background[b-y0:d-y0,a-x0:c-x0],s.target[b:d,a:c],coverage(s.folder,layer)))
        if order is None:order=current
        assert current==order,'Require matching foreground roles/order across scenes'
    assert order,'No static vectors to fit'
    shared=dict(styles or {});before=[];after=[];evaluations=0
    for key in order:
        shared[key],b,a,count=fit_samples(groups[key],shared.get(key,defaults[key]));before.append(b);after.append(a);evaluations+=count
    return shared,dict(before_mse=np.array(before).T.ravel().tolist(),after_mse=np.array(after).T.ravel().tolist(),asset_order=order,score_order='Scene-major, then asset_order',optimizer_evaluations=evaluations,formula='P=(1-a)B+a(t*B+q); t=1-opacity, q=opacity*color; shared color/opacity per role')
