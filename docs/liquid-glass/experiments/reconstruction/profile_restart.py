"""Restart an empirical normal-profile fit outside a monotone-decay basin.

Seeds are dimensionless spline families, not image-feature coordinates. The
existing renderer, frozen masks and native acceptance metrics remain unchanged.
This explores image maps; it does not recover a physical refracting solid.
"""
from pathlib import Path
import argparse, copy, hashlib, itertools, json, time
import numpy as np
from scipy.optimize import minimize
from extended_fit import Study, BOUNDS
from source_support import coverage_report, supported


def seeds(old, signed=False, demagnify=False):
    yield copy.deepcopy(old)
    if demagnify:
        for cx,cy,sigma,quiet in itertools.product((-.6,-.3,-.15,0.),(-.6,-.3,-.15,0.),(2.,8.),(False,True)):
            p=copy.deepcopy(old)
            p.update(core_x=cx,core_y=cy,sigma=sigma)
            if quiet:
                p.update({f'c{i}':v for i,v in enumerate([.5,.25,0.,0.,0.,0.,0.])})
                p.update(cap_normal_gain=0.,rim_width=.02,thin_width=.004)
            yield p
        return
    if signed:
        for edge,shoulder,cap,sigma in itertools.product(
                (.25,.75,1.5),(-.1,-.25,-.5,-1.),(-.4,0.,.6),(.5,2.,6.)):
            p=copy.deepcopy(old)
            p.update(spline=True,sigma=sigma,post_sigma=.3,gamma=1.,
                     tangent=0.,core_x=0.,core_y=0.,cap_normal_gain=cap,
                     blur_transition=0.,coupling=0.,rim_width=.02,thin_width=.004)
            c=[edge,.5*edge,shoulder,shoulder,shoulder,.7*shoulder,.2*shoulder]
            p.update({f'c{i}':v for i,v in enumerate(c)})
            yield p
        return
    for edge,peak,cap,sigma,tail in itertools.product(
            (.5,1.,1.5),(1.5,2.,2.5),(0.,.6,1.2),(0.,2.,6.),(0,1)):
        p=copy.deepcopy(old)
        c=([edge,peak,peak,peak,peak,.9*peak,.4*peak] if tail==0 else
           [edge,peak,peak,peak,.8*peak,.4*peak,0.])
        p.update(spline=True,sigma=sigma,post_sigma=0.,gamma=1.,
                 tangent=0.,core_x=0.,core_y=0.,cap_normal_gain=cap,
                 blur_transition=0.,coupling=0.)
        p.update({f'c{i}':v for i,v in enumerate(c)})
        yield p


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('folder',type=Path)
    ap.add_argument('--pass',dest='number',type=int,required=True)
    ap.add_argument('--budget',type=int,default=4000)
    ap.add_argument('--basins',type=int,default=3)
    ap.add_argument('--signed',action='store_true',help='Also admit outward normal lookups, preserving native gates')
    ap.add_argument('--demagnify',action='store_true',help='Test a reduced central image, independently of the normal rim profile')
    args=ap.parse_args()
    dest=args.folder/f'pass-{args.number:02}'
    assert not dest.exists(),'Preserve completed experiments'
    t0=time.time();study=Study(args.folder)
    old=json.loads((args.folder/'accepted.json').read_text())
    archive=[];evaluations=0
    H,W=study.source.shape[:2]
    x=study.x.ravel()[study.indices];y=study.y.ravel()[study.indices]

    def evaluate(p):
        nonlocal evaluations
        evaluations+=1
        # Cheap conservative rejection before material fitting. Exact native
        # footprint tests below decide acceptance, including mask holes.
        X,Y,*_=study.geometry_field(x,y,p)
        sx=X*study.L+study.case['center'][0]
        sy=Y*study.L+study.case['center'][1]
        r=int(np.ceil(3*round(study.kernel_sigma(p)*4)/4))
        if np.any((sx<r)|(sx>W-1-r)|(sy<r)|(sy>H-1-r)):
            return 1e3
        loss,co=study.fit(p)
        archive.append((loss,copy.deepcopy(p),co.copy()))
        if evaluations%250==0:
            print(json.dumps(dict(evaluations=evaluations,best_training=min(a[0] for a in archive),
                                  seconds=time.time()-t0)),flush=True)
        return loss

    for p in seeds(old['parameters'],args.signed,args.demagnify):evaluate(p)
    keys=[*[f'c{i}' for i in range(7)],'sigma','cap_normal_gain','post_sigma','gamma']
    if args.signed:keys+=['rim_width','thin_width']
    if args.demagnify:keys+=['core_x','core_y']
    starts=[]
    for item in sorted(archive,key=lambda a:a[0]):
        # Do not spend all restarts in nearly identical neighboring seeds.
        diversity_keys=[*[f'c{i}' for i in range(7)],'cap_normal_gain']+(['core_x','core_y'] if args.demagnify else [])
        v=np.array([item[1][k] for k in diversity_keys])
        if any(np.linalg.norm(v-np.array([s[1][k] for k in diversity_keys]))<.25 for s in starts):continue
        starts.append(item)
        if len(starts)>=args.basins:break
    for _,start,_ in starts:
        def objective(v):
            p=copy.deepcopy(start);p.update(zip(keys,map(float,v)))
            return evaluate(p)
        minimize(objective,[start[k] for k in keys],method='Powell',
                 bounds=[(-2.5,2.5) if args.signed and k.startswith('c') and k[1:].isdigit() else
                         (-.8,.18) if args.demagnify and k in ('core_x','core_y') else BOUNDS[k] for k in keys],
                 options=dict(maxfev=args.budget,maxiter=20,xtol=.0004,ftol=1e-8))
    # Native assessment is separate from the training rank. Keep the strongest
    # seeds as well as converged candidates, without promoting invalid support.
    finalists=[];seen=set()
    for training,p,co in sorted(archive,key=lambda a:a[0]):
        key=tuple(round(p[k],3) for k in keys)
        if key in seen:continue
        seen.add(key)
        pred=study.render(p,co);scores=study.scores(pred)
        if 'exterior' in old['scores']:scores['exterior']=old['scores']['exterior']
        footprint=coverage_report(study,p)
        valid=supported(study,footprint)
        finalists.append(dict(training_loss=training,parameters=p,material=co.tolist(),
                              scores=scores,support=footprint,supported=valid))
        if len(finalists)>=12:break
    eligible=[c for c in finalists if c['supported'] and
              c['scores']['loss']<=old['scores']['loss']+1e-9 and
              c['scores']['glass']['rmse']<=old['scores']['glass']['rmse']+1e-9]
    candidate=min(eligible or finalists,key=lambda c:c['scores']['loss'])
    advance=candidate in eligible
    current={k:candidate[k] for k in ('parameters','material','scores')} if advance else old
    experiment='Global signed normal-profile and rim-width restart' if args.signed else 'Global nonmonotone normal-profile restart'
    if args.demagnify:experiment='Signed center-scale and normal-profile refinement'
    record=dict(pass_number=args.number,experiment=experiment,
                keys=keys,evaluations=evaluations,seconds=time.time()-t0,
                code_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                execution_script=Path(__file__).name,candidate=candidate,
                accepted=advance,incumbent_scores=old['scores'],current=current,
                finalists=finalists,supported_training_evaluations=len(archive),
                seed_families=('Independent reduced center scales with retained/quiet rim profiles and two source kernels' if args.demagnify else
                    'Signed shoulder with positive outer rim, three cap gains and three source kernels' if args.signed else
                    'Positive outer-to-inner hump, three cap gains and three source kernels'),
                gate='Native loss/glass cannot regress; defined source and 3-sigma footprint >=.999. Fixed shape/shadow preserve exterior.',
                limits='Conditional on estimated uniform track; empirical image map, not an identified physical solid.')
    dest.mkdir()
    (dest/'record.json').write_text(json.dumps(record,indent=2)+'\n')
    for name,state in [('candidate',candidate),('accepted',current)]:
        pred=study.render(state['parameters'],np.array(state['material']))
        study.comparison(pred,dest/f'{name}.png',state['parameters'])
    (args.folder/'accepted.json').write_text(json.dumps(current,indent=2)+'\n')
    print(json.dumps(dict(pass_number=args.number,accepted=advance,evaluations=evaluations,
                          before=old['scores']['loss'],retained=current['scores']['loss'],
                          seconds=time.time()-t0)),flush=True)


if __name__=='__main__':main()
