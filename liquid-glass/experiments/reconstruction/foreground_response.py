"""Bounded per-channel foreground response with independent coverage.

F_c(B)=t_c[1-s_c(1-B_c)^g_c]. This is an empirical foreground response,
not a Fresnel law. Historical masks use eroded interiors; clean SVG fitting
uses fixed coverage and protects each background's full-box error.
"""
import cv2,numpy as np
from scipy.optimize import least_squares,minimize


def response(B,p):
    p=np.asarray(p).reshape(3,3)
    return p[:,0]*(1-p[:,1]*(1-np.clip(B,0,1))**p[:,2])


def affine_response(style):
    """Embed the old color/opacity operation exactly at exponent1."""
    if 'response' in style:return np.asarray(style['response'],float)
    opacity=style['opacity'];v=1-opacity;q=np.asarray(style['color'],float)*opacity
    t=q+v;s=np.divide(v,t,out=np.zeros_like(t),where=t>0)
    return np.stack([t,s,np.ones(3)],axis=1)


def fit_blended(samples,style):
    start=affine_response(style).ravel()
    def errors(v):
        return np.array([float(np.mean(((1-A[...,None])*B+A[...,None]*response(B,v)-T)**2))*255**2 for B,T,A in samples])
    before=errors(start);scale=max(1,float(before.mean()))
    fit=minimize(lambda v:errors(v).mean()/scale,start,method='SLSQP',bounds=[b for _ in range(3) for b in [(0,1),(0,1),(.2,12)]],
        constraints=[dict(type='ineq',fun=lambda v:(before-errors(v))/scale)],options=dict(maxiter=200,ftol=1e-12,eps=2e-5))
    best=start;after=before
    for t in [1,.5,.25,.125,.0625,.03125]:
        v=start+t*(fit.x-start);trial=errors(v)
        if np.all(trial<=before+1e-8) and trial.sum()<after.sum():best=v;after=trial
    return dict(response=best.reshape(3,3).tolist()),dict(before_mse=before.tolist(),after_mse=after.tolist(),evaluations=int(fit.nfev),formula='F_c(B)=t_c*(1-s_c*(1-B_c)^gamma_c); fixed SVG coverage')


def fit_response(A,Y):
    p=[]
    for c in range(3):
        def residual(q):return q[0]*(1-q[1]*(1-np.clip(A[:,c],0,1))**q[2])-Y[:,c]
        best_x=None;best_cost=float('inf')
        for gamma in [.5,1.,3.,8.]:
            fit=least_squares(residual,[min(1,float(Y[:,c].max())+.02),.5,gamma],bounds=([0,0,.1],[1,1,16]),max_nfev=200)
            cost=float(np.sum(fit.fun**2))
            if cost<best_cost:best_x=fit.x;best_cost=cost
        p.append(best_x)
    return np.array(p)


def calibrate(B,T,seed,parameters=None):
    inside=cv2.erode(seed.astype(np.uint8),np.ones((3,3),np.uint8))>0
    assert inside.sum()>30, 'Insufficient uncontaminated glyph interior'
    p=fit_response(B[inside],T[inside]) if parameters is None else np.asarray(parameters)
    stencil=cv2.dilate(seed.astype(np.uint8),np.ones((3,3),np.uint8))>0
    background=cv2.inpaint(np.uint8(np.rint(T*255)),stencil.astype(np.uint8)*255,5,cv2.INPAINT_TELEA).astype(float)/255
    ink=response(B,p);delta=background-ink
    alpha=np.clip(np.sum((background-T)*delta,2)/np.maximum(np.sum(delta*delta,2),1e-9),0,1)*stencil
    alpha[inside]=1
    error=(response(B,p)-T)[inside]*255
    return p,alpha,dict(pixels=int(inside.sum()),rmse=float(np.sqrt(np.mean(error**2))),parameters=p.tolist(),formula='t*(1-s*(1-B)^g), per channel in encoded RGB')
