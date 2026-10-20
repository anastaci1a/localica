"""Fit an exterior-only, neutral contact shadow after the optical passes.

No glass pixel or optical parameter changes. Exterior context is measured
against the recovered background, rather than carried over from Apple pixels.
"""
from pathlib import Path
import argparse,json,time
import cv2,numpy as np
from scipy.optimize import differential_evolution,minimize
from extended_fit import Study


def shadow(study,p,x,y):
    q=p.get('shadow',dict(sigma=.12,opacity=0,offset=[0,0]))
    _,_,_,_,(sd,_,_)=study.shape(x-q['offset'][0],y-q['offset'][1],p)
    return q['opacity']*np.exp(-.5*(np.maximum(sd,0)/q['sigma'])**2)


def refit_fixed_region(study,p,reference):
    """Keep the original exterior scoring pixels when geometry is refined."""
    *_,(sd,_,_)=study.shape(study.x,study.y,reference)
    x0,y0,x1,y1=study.crop
    B=study.source[y0:y1,x0:x1];T=study.target_crop
    known=cv2.imread(str(study.folder/'known.png'),cv2.IMREAD_GRAYSCALE)[y0:y1,x0:x1]>0
    mask=(sd>1.5/study.L)&(sd<.20)&known
    X=study.x*study.L+study.case['center'][0];Y=study.y*study.L+study.case['center'][1]
    for a,b,c,d in study.case.get('exterior_exclusions',[]):mask&=~((X>=a)&(X<=c)&(Y>=b)&(Y<=d))
    px,py=study.x[mask],study.y[mask];B=B[mask];T=T[mask]
    q=p['shadow'];start=[q['opacity'],q['sigma'],*q['offset']]
    def evaluate(v):
        candidate={**p,'shadow':dict(opacity=float(v[0]),sigma=float(v[1]),offset=list(map(float,v[2:4])))}
        alpha=shadow(study,candidate,px,py)
        return float(np.mean((B*(1-alpha[...,None])-T)**2))
    result=minimize(evaluate,start,method='Powell',bounds=[(0,.3),(.015,.5),(-.05,.05),(-.10,.10)],options=dict(maxfev=900,xtol=1e-7,ftol=1e-10))
    v=result.x if result.fun<evaluate(start) else start
    candidate={**p,'shadow':dict(opacity=float(v[0]),sigma=float(v[1]),offset=list(map(float,v[2:4])))}
    return candidate,dict(pixels=int(mask.sum()),rmse=float(np.sqrt(evaluate(v))*255))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);args=ap.parse_args();t0=time.time()
    study=Study(args.folder);old=json.loads((args.folder/'accepted.json').read_text());p=old['parameters']
    x,y=study.x,study.y;_,_,_,_,(sd,_,_)=study.shape(x,y,p)
    x0,y0,x1,y1=study.crop
    base=study.source[y0:y1,x0:x1]
    known=cv2.imread(str(args.folder/'known.png'),cv2.IMREAD_GRAYSCALE)[y0:y1,x0:x1]>0
    mask=(sd>1.5/study.L)&(sd<.20)&known
    # The reference's next neighboring material must not enter the mask.
    for a,b,c,d in study.case.get('exterior_exclusions',[]):
        X=x*study.L+study.case['center'][0];Y=y*study.L+study.case['center'][1]
        mask&=~((X>=a)&(X<=c)&(Y>=b)&(Y<=d))
    px=x[mask];py=y[mask];B=base[mask];T=study.target_crop[mask]
    def evaluate(v):
        candidate={**p,'shadow':dict(opacity=float(v[0]),sigma=float(v[1]),offset=list(map(float,v[2:4])))}
        a=shadow(study,candidate,px,py)
        return float(np.mean((B*(1-a[...,None])-T)**2))
    result=differential_evolution(evaluate,[(0,.3),(.015,.5),(-.05,.05),(-.10,.10)],seed=222,popsize=8,maxiter=70,tol=1e-8,polish=True)
    before=float(np.mean((B-T)**2));advance=result.fun<=before
    candidate={**p,'shadow':dict(opacity=float(result.x[0]),sigma=float(result.x[1]),offset=list(map(float,result.x[2:4])))}
    old_scores=old['scores'].copy();old_scores['exterior']=dict(pixels=int(mask.sum()),rmse=float(np.sqrt(before)*255))
    candidate_scores=old_scores.copy();candidate_scores['exterior']=dict(pixels=int(mask.sum()),rmse=float(np.sqrt(result.fun)*255))
    current={**old,'parameters':candidate,'scores':candidate_scores} if advance else old
    record=dict(pass_number=12,experiment='Recovered-background exterior contact shadow',keys=['shadow.opacity','shadow.sigma','shadow.offset'],evaluations=int(result.nfev),seconds=time.time()-t0,
        candidate=dict(parameters=candidate,material=old['material'],scores=candidate_scores),accepted=bool(advance),incumbent_scores=old_scores,current=current,
        gate='Glass is unchanged; exterior RGB mismatch cannot increase. No reference shadow is copied.',
        limits='Only recovered, non-neighbor exterior pixels constrain this neutral Gaussian-distance approximation. Not a measured physical light rig.')
    dest=args.folder/'pass-12';dest.mkdir(exist_ok=False)
    (dest/'record.json').write_text(json.dumps(record,indent=2)+'\n');(args.folder/'accepted.json').write_text(json.dumps(current,indent=2)+'\n')
    print(json.dumps(dict(pass_number=12,accepted=bool(advance),before_rmse=float(np.sqrt(before)*255),after_rmse=float(np.sqrt(result.fun)*255),parameters=current['parameters'].get('shadow'),seconds=time.time()-t0)),flush=True)


if __name__=='__main__':main()
