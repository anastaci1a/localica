"""Shared-model calibration against two independently frozen observations.

Neither per-sample overall loss nor glass RMSE may regress. Native scoring and
the original masks remain authoritative; a joint average cannot hide a loss.
The cached renderer changes only evaluation cost, not optical/material order.
"""
from pathlib import Path
import argparse,copy,json,time
import cv2,numpy as np
from scipy.optimize import minimize
from extended_fit import Study
from render import shade

STAGES={
 1:('Shared native tone and transmission',list(range(6))+[20,21,22]),
 2:('Shared directional rim and environment',list(range(6,20))),
 3:('Shared normal profile and scattering',[]),
 4:('Shared foreground color response',[]),
 5:('Joint shared-material settlement',list(range(23))),
 6:('Foreground-aware shared-material settlement',list(range(23))),
}


class Cached:
    def __init__(self,study,p):
        self.study=study;self.p=p
        a,b,c,d=study.crop
        yy,xx=np.mgrid[b:d:.5,a:c:.5]
        self.C,self.J=study.field((xx-.25-study.case['center'][0])/study.L,(yy-.25-study.case['center'][1])/study.L,p)
        self.H=d-b;self.W=c-a

    def render(self,co):
        pred=shade(self.C,self.J,np.asarray(co)).reshape(self.H,2,self.W,2,3).mean((1,3)).astype(np.float32)
        if self.p['post_sigma']>.001:pred=cv2.GaussianBlur(pred,(0,0),self.p['post_sigma'])
        return pred


def scores(caches,co):return [c.study.scores(c.render(co)) for c in caches]


def permitted(candidate,previous,tolerance=1e-8):
    return all(c['loss']<=p['loss']+tolerance and c['glass']['rmse']<=p['glass']['rmse']+tolerance for c,p in zip(candidate,previous))


def foreground_errors(studies,p,co,params):
    from foreground_response import response
    errors=[]
    for s in studies:
        pred=s.render(p,np.asarray(co));x0,y0,_,_=s.crop
        for layer in s.transmission_layers:
            a,b,c,d=layer['box'];B=pred[b-y0:d-y0,a-x0:c-x0]
            inside=cv2.erode(layer['seed'].astype(np.uint8),np.ones((3,3),np.uint8))>0
            errors.append(float(np.mean((response(B,params)-layer['target'])[inside]**2))*255**2)
    return errors


def material_trial(caches,old,keys):
    co=np.array(old['material']);baseline=old['scores'];scale=max(1,np.mean([s['loss'] for s in baseline]))
    if not keys:return co,baseline,0
    memo={};calls=0;foreground_memo=[]
    def evaluate(v):
        nonlocal calls
        key=np.asarray(v).tobytes()
        if key not in memo:
            current=co.copy();current[keys]=v
            metrics=[];foreground_memo.clear()
            for cache in caches:
                pred=cache.render(current);metrics.append(cache.study.scores(pred))
                if 'foreground_response' in old:
                    from foreground_response import response
                    s=cache.study;x0,y0,_,_=s.crop
                    for layer in s.transmission_layers:
                        a,b,c,d=layer['box'];B=pred[b-y0:d-y0,a-x0:c-x0]
                        inside=cv2.erode(layer['seed'].astype(np.uint8),np.ones((3,3),np.uint8))>0
                        foreground_memo.append(float(np.mean((response(B,old['foreground_response'])-layer['target'])[inside]**2))*255**2)
            memo.clear();memo[key]=metrics;calls+=1
        return memo[key]
    def objective(v):return np.mean([s['loss'] for s in evaluate(v)])/scale
    def constraints(v):
        out=[]
        for trial,base in zip(evaluate(v),baseline):
            out.extend([(base['loss']-trial['loss'])/scale,(base['glass']['rmse']**2-trial['glass']['rmse']**2)/scale])
        if 'foreground_response' in old:
            out.extend((b-a)/scale for a,b in zip(foreground_memo,old['foreground_scores']['after_mse']))
        return np.array(out)
    def bound(k):return (.05,1.5) if k<3 or k>=20 else (-.8,.8) if k<6 else (-4,4)
    result=minimize(objective,co[keys],method='SLSQP',bounds=[bound(k) for k in keys],
        constraints=[dict(type='ineq',fun=constraints)],options=dict(maxiter=55,ftol=1e-10,eps=2e-5))
    # A solver's convergence flag is not an acceptance gate. Check its actual
    # native outputs and backtrack toward the incumbent if necessary.
    best=co.copy();best_scores=baseline
    for t in [1,.5,.25,.125,.0625,.03125,.015625]:
        q=co.copy();q[keys]=co[keys]+t*(result.x-co[keys]);trial=scores(caches,q)
        if permitted(trial,baseline) and sum(s['loss'] for s in trial)<sum(s['loss'] for s in best_scores):best=q;best_scores=trial
    return best,best_scores,calls


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('--pass',dest='number',type=int,required=True);args=ap.parse_args()
    t0=time.time();name,keys=STAGES[args.number];folder=args.folder
    record_path=folder/f'pass-{args.number:02}.json';assert not record_path.exists(),'Keep paired trials immutable'
    studies=[Study(folder/'a'),Study(folder/'b')]
    # Current paired studies have matched reference scale. Generalizing to
    # differently sized references requires converting sigma/post_sigma per L.
    assert abs(studies[0].L-studies[1].L)<1e-12,'Normalize kernels before comparing different reference scales'
    saved=folder/'accepted.json'
    if saved.exists():old=json.loads(saved.read_text())
    else:
        initial=json.loads((folder/'initial.json').read_text());p=initial['parameters'];co=initial['material']
        caches=[Cached(s,p) for s in studies]
        for c in caches:
            reference=c.study.render(p,np.array(co));prediction=c.render(co)
            assert np.max(abs(reference-prediction))<2e-6,'Cache changes renderer'
        old=dict(parameters=p,material=co,scores=scores(caches,co))
        (folder/'baseline.json').write_text(json.dumps(old,indent=2)+'\n')
        saved.write_text(json.dumps(old,indent=2)+'\n')
    current=copy.deepcopy(old);p=current['parameters'];caches=[Cached(s,p) for s in studies];trials=[];evaluations=0
    if args.number in [1,2,5,6]:
        co,metric,evaluations=material_trial(caches,old,keys)
        current.update(material=co.tolist(),scores=metric)
        trials.append(dict(kind='Constrained shared material',scores=metric,evaluations=evaluations))
    elif args.number==3:
        # Coordinate probes test genuinely shared optics, without giving each
        # sample its own displacement profile or material coefficients.
        probes=[('sigma',-.5),('sigma',.5),('post_sigma',-.2),('post_sigma',.2)]
        probes += [(f'c{i}',sign*.025) for i in range(7) for sign in [-1,1]] if p['spline'] else [('amplitude',-.025),('amplitude',.025),('band',-.015),('band',.015)]
        for key,delta in probes:
            candidate=copy.deepcopy(current['parameters']);candidate[key]+=delta
            if key in ['sigma','post_sigma'] and candidate[key]<0:continue
            trial_caches=[Cached(s,candidate) for s in studies];metric=scores(trial_caches,current['material']);evaluations+=1
            accept=permitted(metric,current['scores'])
            trials.append(dict(key=key,delta=delta,accepted=accept,scores=metric))
            if accept:current.update(parameters=candidate,scores=metric)
    else:
        # Foreground is outside the optical score masks. Fit one response to
        # both observed glyphs; never fit an independent response per scene.
        from foreground_response import fit_response,response
        samples=[]
        for s,c in zip(studies,caches):
            pred=c.render(current['material']);x0,y0,_,_=s.crop
            for layer in s.transmission_layers:
                a,b,cx,d=layer['box'];B=pred[b-y0:d-y0,a-x0:cx-x0]
                inside=cv2.erode(layer['seed'].astype(np.uint8),np.ones((3,3),np.uint8))>0
                samples.append((B[inside],layer['target'][inside]))
        if samples:
            A=np.concatenate([s[0] for s in samples]);T=np.concatenate([s[1] for s in samples]);fit=fit_response(A,T)
            # The first independent fit supplies the shared baseline; the
            # second scene cannot receive another tailored color recipe.
            baseline=current.get('foreground_response')
            if baseline is None:baseline=fit_response(*samples[0]).tolist()
            before=[float(np.mean((response(B,baseline)-T)**2))*255**2 for B,T in samples]
            best=np.asarray(baseline);best_loss=before
            for t in [1,.5,.25,.125,.0625,.03125,.015625]:
                q=np.asarray(baseline)+t*(fit-np.asarray(baseline))
                loss=[float(np.mean((response(B,q)-T)**2))*255**2 for B,T in samples]
                if all(a<=b+1e-8 for a,b in zip(loss,best_loss)):best=q;best_loss=loss
            current['foreground_response']=best.tolist()
            current['foreground_scores']=dict(before_mse=before,after_mse=best_loss)
            trials.append(dict(kind='One shared foreground response',**current['foreground_scores']));evaluations=4*3
        else:trials.append(dict(kind='No transmissive foreground; shared source-gamma probe'))
        if not samples:
            for delta in [-.025,.025]:
                candidate=copy.deepcopy(current['parameters']);candidate['gamma']+=delta
                metric=scores([Cached(s,candidate) for s in studies],current['material']);evaluations+=1
                accept=permitted(metric,current['scores']);trials.append(dict(gamma_delta=delta,accepted=accept,scores=metric))
                if accept:current.update(parameters=candidate,scores=metric)
    # Independent normal renderer, including float32 storage and scattering,
    # owns acceptance. Cached optimization never overrides its score.
    metric=[s.scores(s.render(current['parameters'],np.array(current['material']))) for s in studies]
    advance=permitted(metric,old['scores']);current['scores']=metric
    if 'foreground_response' in old:
        before=foreground_errors(studies,old['parameters'],old['material'],old['foreground_response'])
        after=foreground_errors(studies,current['parameters'],current['material'],current['foreground_response'])
        advance=advance and all(a<=b+1e-8 for a,b in zip(after,before))
        current['foreground_scores']=dict(before_mse=before,after_mse=after)
    if not advance:current=old
    record=dict(pass_number=args.number,experiment=name,accepted=advance,previous=old,current=current,trials=trials,evaluations=evaluations,seconds=time.time()-t0,
        gate='Neither sample loss nor whole-glass RMSE may increase. One shared optical profile and23-coefficient material. Foreground follow-up also checks both interior errors.',
        limits='Two observations from the same Apple video, not independent device validation or proof of cross-scene universality.')
    record_path.write_text(json.dumps(record,indent=2)+'\n');saved.write_text(json.dumps(current,indent=2)+'\n')
    for i,s in enumerate(studies):
        if 'foreground_response' in current:s.case['foreground_response']=current['foreground_response']
        pred=s.render(current['parameters'],np.array(current['material']));s.comparison(pred,folder/f'pass-{args.number:02}-{i}.png',current['parameters'])
    print(json.dumps(dict(pass_number=args.number,accepted=advance,scores=current['scores'],evaluations=evaluations,seconds=time.time()-t0)),flush=True)


if __name__=='__main__':main()
