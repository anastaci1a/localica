"""Bounded per-channel transmission response for a reference-derived glyph.

F_c(B)=t_c[1-s_c(1-B_c)^g_c]. This is an empirical foreground response,
not a Fresnel law. Fit only eroded glyph interiors; keep the response frozen
while the backdrop moves. Coverage and response are separate quantities.
"""
import cv2,numpy as np
from scipy.optimize import least_squares


def response(B,p):
    p=np.asarray(p).reshape(3,3)
    return p[:,0]*(1-p[:,1]*(1-np.clip(B,0,1))**p[:,2])


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
