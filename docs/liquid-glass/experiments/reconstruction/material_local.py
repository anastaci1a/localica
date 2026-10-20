"""Local quadratic material proposals, checked with the full native renderer.

With the clipping state frozen, subpixel shading and output Gaussian filtering
are affine in the23 material coefficients. Their RGB error has23x23 sufficient
statistics. Recompute the clipping state after accepted trust-region steps.
This accelerates proposal search only; actual native loss/glass gates decide.
"""
import cv2,numpy as np
from scipy.optimize import minimize


def statistics(cache,co):
    s=cache.study;H,W=cache.H,cache.W;C,J=cache.C,cache.J
    core=J[...,-1]
    gain=(1-core[...,None])*co[:3]+core[...,None]*co[-3:]
    z=C*gain+co[3:6]+(J[...,:-1]@co[6:-3])[...,None]
    pred=cache.render(co);residual=pred.astype(np.float64)-s.target_crop
    forms={name:[np.zeros((23,23)),np.zeros(23)] for name in ['glass','rim']}
    for channel in range(3):
        active=(z[...,channel]>0)&(z[...,channel]<1)
        average=lambda v:v.reshape(H,2,W,2,*v.shape[2:]).mean((1,3))
        F=np.zeros((H,W,23),np.float64)
        F[...,channel]=average(active*C[...,channel]*(1-core))
        F[...,channel+3]=average(active)
        F[...,6:20]=average(active[...,None]*J[...,:-1])
        F[...,channel+20]=average(active*C[...,channel]*core)
        sigma=s.kernel_sigma(cache.p,'post_sigma')
        if sigma>.001:F=cv2.GaussianBlur(F,(0,0),sigma)
        for name,mask in [('glass',s.valid),('rim',s.rim)]:
            X=F[mask];e=residual[...,channel][mask];factor=255**2/(3*mask.sum())
            forms[name][0]+=(X.T@X)*factor
            forms[name][1]+=(X.T@e)*factor
    return forms


def trial(caches,old,keys,iterations=10,progress=None):
    from paired_fit import scores,permitted
    point=np.asarray(old['material'],float);metric=old['scores'];calls=0;radius=.08
    def bound(k):return (.05,1.5) if k<3 or k>=20 else (-.8,.8) if k<6 else (-4,4)
    for iteration in range(iterations):
        forms=[statistics(c,point) for c in caches];calls+=1
        scale=max(1,float(np.mean([m['loss'] for m in metric])))
        def value(scene,v,kind):
            d=v-point[keys];gH,gg=forms[scene]['glass'];rH,rg=forms[scene]['rim']
            H,g=(.7*gH+.3*rH,.7*gg+.3*rg) if kind=='loss' else (gH,gg)
            base=metric[scene]['loss'] if kind=='loss' else metric[scene]['glass']['rmse']**2
            return float(base+2*g[keys]@d+d@H[np.ix_(keys,keys)]@d)
        def objective(v):return np.mean([value(i,v,'loss') for i in range(len(caches))])/scale
        def constraints(v):
            return np.array([x for i,m in enumerate(old['scores']) for x in [(m['loss']-value(i,v,'loss'))/scale,(m['glass']['rmse']**2-value(i,v,'glass'))/scale]])
        bounds=[(max(bound(k)[0],point[k]-radius),min(bound(k)[1],point[k]+radius)) for k in keys]
        result=minimize(objective,point[keys],method='SLSQP',bounds=bounds,constraints=[dict(type='ineq',fun=constraints)],options=dict(maxiter=100,ftol=1e-11))
        best=point.copy();best_metric=metric
        for t in [1,.5,.25,.125,.0625,.03125,.015625]:
            q=point.copy();q[keys]=point[keys]+t*(result.x-point[keys]);actual=scores(caches,q);calls+=1
            if permitted(actual,old['scores']) and sum(m['loss'] for m in actual)<sum(m['loss'] for m in best_metric):best=q;best_metric=actual
        improvement=sum(m['loss'] for m in metric)-sum(m['loss'] for m in best_metric)
        point,metric=best,best_metric
        if progress:progress(dict(iteration=iteration+1,evaluations=calls,losses=[m['loss'] for m in metric],radius=radius))
        if improvement<1e-7:
            radius*=.25
            if radius<.002:break
        else:radius=min(.15,radius*1.25)
    return point,metric,calls
