"""Repeated, recorded curvature searches on the existing five-case study.

Run after reproduce.py has populated an output directory with the baseline.
Each restart is retained, not just the winner. Selection uses training loss.
"""
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS','1')
from pathlib import Path
import argparse,json
import cv2,numpy as np
from scipy.optimize import differential_evolution,minimize,OptimizeResult
from reproduce import CASES,points,masks,render,scores,sample_backdrop,model_light,prepare,training_samples
from render import case_geometry,fit_material,shade,spline_basis


def fit(source,target,case,model,seed,initial=None,iterations=80,subpixels=1):
    xs,ys,x,y,indices,weights=training_samples(case)
    px,py=x.flat[indices],y.flat[indices];tx,ty=xs.flat[indices],ys.flat[indices]
    if subpixels>1:
        oy,ox=np.mgrid[:subpixels,:subpixels].astype(float)
        px=px[:,None]+(ox.ravel()+.5)/subpixels-.5
        py=py[:,None]+(oy.ravel()+.5)/subpixels-.5
    boundary=case_geometry(case,px,py);expected=target[ty.astype(int),tx.astype(int)]
    basis=spline_basis(-boundary[0],case['radius']) if model in ('potential','tensor','chromatic') else None
    if model in ('potential','tensor','chromatic'):
        bounds=[(0,2.5)]*7+[(0,.3),(0,.65),(0,.8),(.05,1)]
        if model=='chromatic':bounds.append((0,.2))
        def params(v):
            p=dict(coefficients=list(v[:7]),core_x=v[7],core_y=v[8],tangent=v[9],tangent_band=v[10],tensor=model in ('tensor','chromatic'))
            if model=='chromatic':p['dispersion']=v[11]
            return p
    else:
        # beta<1 retains a vertical limiting rim; beta=.5 gives an ellipsoidal
        # meridian for n=2,crown=0. IOR bounds include the RGB dispersion proxy.
        bounds=[(.01,1.8),(0,1.5),(.2,.95),(0,.7),(1,40)]
        if model=='spectral':bounds.append((0,.025))
        def params(v):
            p=dict(height=v[0],gap=v[1],beta=v[2],crown=v[3],shoulder=v[4],ior=1.33)
            if model=='spectral':p['dispersion']=v[5]
            return p
    optical=len(bounds);bounds += [(0,max(case.get('sigma_max',35),1e-6)),(1,12)]
    if case.get('blur_transition'):bounds.append((2,30))
    blurs={}
    def decode(v):
        return dict(model=model,parameters=params(v),sigma=round(v[optical]*4)/4,rim_width=v[optical+1],blur_transition=v[-1] if case.get('blur_transition') else None)
    def evaluate(v):
        f=decode(v);p=f['parameters']
        if basis is not None:p['_basis']=basis
        key=f['sigma']
        if key not in blurs:blurs[key]=cv2.GaussianBlur(source,(0,0),key) if key else source
        sample=sample_backdrop(source,blurs[key],case,f,px,py,boundary)
        sample=sample.reshape((len(indices),subpixels**2,3)) if subpixels>1 else sample.reshape(-1,3)
        light=model_light(px,py,case,f,boundary)
        co,_=fit_material(sample,light,expected,weights)
        pred=shade(sample,light,co)
        if subpixels>1:pred=pred.mean(1)
        loss=float(np.mean(((pred-expected)*weights[:,None])**2))
        return loss,co
    start=None
    if initial:
        p=initial['parameters']
        if model in ('potential','tensor','chromatic'):
            start=p['coefficients']+[p['core_x'],p['core_y'],p.get('tangent',0),p.get('tangent_band',.5)]
            if model=='chromatic':start.append(p.get('dispersion',0))
        else:
            start=[p.get('height',.5),p.get('gap',.4),p.get('beta',.5),p.get('crown',.05),p.get('shoulder',8)]
            if model=='spectral':start.append(p.get('dispersion',0))
        start += [initial['sigma'],initial['rim_width']]
        if case.get('blur_transition'):start.append(initial.get('blur_transition',10))
        start=np.clip(start,np.asarray(bounds)[:,0],np.asarray(bounds)[:,1])
    objective=lambda v:evaluate(v)[0]
    result=differential_evolution(objective,bounds,seed=seed,popsize=7,maxiter=iterations,tol=.0005,polish=False,x0=start)
    best=result
    if start is not None and objective(start)<best.fun:best=OptimizeResult(x=start,fun=objective(start))
    # Three local refinement rounds expose convergence rather than treating a
    # single stochastic search as an optimum. Keep the progression in evidence.
    rounds=[]
    for _ in range(3):
        polished=minimize(objective,best.x,method='Powell',bounds=bounds,options={'maxiter':35,'xtol':.0005,'ftol':1e-7})
        rounds.append(float(polished.fun))
        if polished.fun<best.fun:best=polished
    loss,co=evaluate(best.x);f=decode(best.x)
    f.update(material=co.tolist(),fit_loss=loss,seed=seed,search_iterations=iterations,fit_subpixels_per_axis=subpixels,refinement_losses=rounds)
    f['sampling']='native-grid, deterministic 6500 subset; outer 4px weight 2.5, other rim 1.3'
    return f


def main():
    ap=argparse.ArgumentParser();ap.add_argument('out',type=Path);ap.add_argument('--case',choices=['media','regular','slider'],required=True);ap.add_argument('--model',choices=['potential','tensor','chromatic','surface','reflection','spectral','colored'],required=True);ap.add_argument('--seed',type=int,default=1401);ap.add_argument('--restarts',type=int,default=2);ap.add_argument('--iterations',type=int,default=80);ap.add_argument('--subpixels',type=int,choices=[1,2,4],default=1);args=ap.parse_args()
    name=args.case;case=CASES[name];folder=args.out
    source,target,_,_=prepare(folder.parent,name,case)
    report=json.loads((folder/f'{name}-report.json').read_text());models=report['models']
    prior=models.get(args.model) or models.get('tensor' if args.model=='chromatic' and 'tensor' in models else 'spline' if args.model in ('potential','tensor','chromatic') else 'reflection' if args.model=='spectral' else 'surface' if args.model=='reflection' else 'cap')
    record=[];x0,y0,x1,y1=case['crop']
    for restart in range(args.restarts):
        seed=args.seed+restart
        print('Search',name,args.model,seed,flush=True)
        f=fit(source,target,case,args.model,seed,prior,args.iterations,args.subpixels)
        output=render(source,case,f).astype(np.float32);f['scores']=scores(output,target[y0:y1,x0:x1],case)
        (folder/f'{name}-{args.model}-{seed}.json').write_text(json.dumps(f,indent=2))
        cv2.imwrite(str(folder/f'{name}-{args.model}-{seed}.png'),np.uint8(np.clip(output,0,1)*255)[:,:,::-1])
        record.append(f)
        print(name,args.model,seed,'loss',f['fit_loss'],'RMSE',f['scores']['all_glass']['rmse'],'rim',f['scores']['rim']['rmse'],'parameters',f['parameters'],flush=True)
        if restart==0 or f['fit_loss']<prior['fit_loss']:prior=f
    best=min(record,key=lambda f:f['fit_loss'])
    models[args.model]=best
    (folder/f'{name}-{args.model}.json').write_text(json.dumps(best,indent=2))
    output=render(source,case,best)
    cv2.imwrite(str(folder/f'{name}-{args.model}.png'),np.uint8(np.clip(output,0,1)*255)[:,:,::-1])
    (folder/f'{name}-report.json').write_text(json.dumps(report,indent=2))


if __name__=='__main__':main()
