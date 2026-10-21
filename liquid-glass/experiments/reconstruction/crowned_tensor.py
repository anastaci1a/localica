"""Bounded toolbar hypotheses in normalized coordinates; not Apple geometry.

Ordinary blur, tensor-map tangential easing and convex footprint softness are
independent parameters. The inherited map is not an identified optical solid.
"""
import numpy as np
import cv2
from scipy.interpolate import BSpline
from render import case_geometry,shade
from crowned_footprint import boundary as crown_boundary
from settled import mapping as incumbent_mapping,lighting as incumbent_lighting

KNOTS=[0]*4+[.015,.04,.09,.2,.45,.75]+[1]*4

def intensity_response(color, p):
    """Shared encoded-RGB response after source filtering, before material.

    This optional empirical curve does not change geometry or dispersion.
    Positive material gains are additionally needed for monotone final color.
    """
    response=p.get('intensity_response')
    if response is None:return color
    if response.get('kind')!='power':raise ValueError('Unknown intensity response')
    gamma=float(response['gamma'])
    if not np.isfinite(gamma) or gamma<=0:raise ValueError('Positive finite gamma required')
    return color if gamma==1 else np.maximum(color,0.)**gamma

def boundary(x,y,p):
    if p.get('footprint_softness'):
        return crown_boundary(x,y,p['shape']['half'],p['footprint_softness'])
    return case_geometry(p['shape'],x,y)

def mapping(x,y,p):
    if not p.get('footprint_softness') and 'tangent_exponent' not in p:
        return incumbent_mapping(x,y,p)
    sd,nx,ny=boundary(x,y,p);d=-sd;q=p['parameters'];r=p['shape']['radius']
    basis=BSpline.design_matrix(np.clip(d/r,0,1).ravel(),KNOTS,3).toarray()[:,:7].reshape((*x.shape,7))
    a=min(p['shape']['half'])*(basis@np.asarray(q['coefficients']))
    u=np.clip(d/(r*q['tangent_band']),0,1);m=p.get('tangent_exponent',4.)
    W=(1-u)**m*(1+m*u);tau=-ny*x+nx*y
    X=(1-q['core_x'])*x-a*nx+q['tangent']*W*tau*ny
    Y=(1-q['core_y'])*y-a*ny-q['tangent']*W*tau*nx
    return np.where(sd<=0,X,x),np.where(sd<=0,Y,y)

def lighting(x,y,p):
    if not p.get('footprint_softness'):return incumbent_lighting(x,y,p)
    sd,nx,ny=boundary(x,y,p);d=np.maximum(-sd,0)
    thin=np.exp(-(d/p['thin_rim'])**2);e=np.exp(-(d/p['rim_width'])**2);f=np.exp(-(d/(4*p['rim_width']))**2)
    X=x/p['shape']['half'][0];Y=y/p['shape']['half'][1];u=np.clip(d/.30,0,1);c=u**3*(u*(6*u-15)+10)
    return np.stack([thin,thin*nx,thin*ny,e,e*nx,e*ny,f,f*nx,f*ny,X,Y,X*X,Y*Y,X*Y,c],axis=-1)

def render(source,case,p,samples=4,scale=1):
    L=min(case['half']);sigma=p['source_sigma']*L;blur=cv2.GaussianBlur(source,(0,0),sigma) if sigma else source
    x0,y0,x1,y1=case['crop'];H=(y1-y0)*scale;W=(x1-x0)*scale
    result=np.zeros((H,W,3),np.float32);coverage=np.zeros((H,W),np.float32)
    for top in range(0,H,16):
        bot=min(top+16,H)
        yy,xx=np.mgrid[top*samples:bot*samples,0:W*samples]
        x=(x0+(xx+.5)/(scale*samples)-.5-case['center'][0])/L
        y=(y0+(yy+.5)/(scale*samples)-.5-case['center'][1])/L
        X,Y=mapping(x,y,p)
        def at(image):return cv2.remap(image,(X*L+case['center'][0]).astype(np.float32),(Y*L+case['center'][1]).astype(np.float32),cv2.INTER_LINEAR)
        C=at(blur)
        if p.get('blur_transition'):
            sd,*_=boundary(x,y,p);u=np.clip(-sd/p['blur_transition'],0,1);w=u**3*(u*(6*u-15)+10);clear=at(source);C=clear+(C-clear)*w[...,None]
        C=shade(intensity_response(C,p),lighting(x,y,p),np.asarray(p['material']));sd,*_=boundary(x,y,p);inside=(sd<=0).astype(float)
        shape=(bot-top,samples,W,samples);weight=inside.reshape(shape).sum((1,3))
        result[top:bot]=(C*inside[...,None]).reshape((*shape,3)).sum((1,3))/np.maximum(weight,1)[...,None]
        coverage[top:bot]=weight/(samples*samples)
    if p.get('post_sigma'):
        # Filter glass color inside its coverage domain; never blur sharp alpha.
        sigma=p['post_sigma']*L*scale
        numerator=cv2.GaussianBlur(result*coverage[...,None],(0,0),sigma)
        denominator=cv2.GaussianBlur(coverage,(0,0),sigma)
        result=numerator/np.maximum(denominator,1e-12)[...,None]
    return result,coverage

def gaussian_coverage_shadow(case,p,scale=1,samples=4):
    """Neutral opacity from a Gaussian convolution of the whole footprint.

    Coverage is generated independently of glass color. Width and offsets use L;
    output is opacity on the requested crop. Padding includes four sigma and the
    shift, preventing a diagnostic crop from clipping the generating footprint.
    """
    q=p.get('shadow');x0,y0,x1,y1=case['crop'];H=(y1-y0)*scale;W=(x1-x0)*scale
    if not q or q['opacity']==0:return np.zeros((H,W),np.float32)
    if not (0<=q['opacity']<=1 and q['sigma']>0):raise ValueError('Invalid shadow')
    L=min(case['half']);sigma=q['sigma']*L;dx,dy=np.asarray(q['offset'])*L
    pad=4*sigma+max(abs(dx),abs(dy))+2;cx,cy=case['center'];hx,hy=case['half']
    bx0,by0=np.floor([cx-hx-pad,cy-hy-pad]).astype(int);bx1,by1=np.ceil([cx+hx+pad,cy+hy+pad]).astype(int)
    BW=(bx1-bx0)*scale;BH=(by1-by0)*scale;alpha=np.zeros((BH,BW),np.float32)
    for top in range(0,BH,16):
        bot=min(top+16,BH);yy,xx=np.mgrid[top*samples:bot*samples,0:BW*samples]
        x=(bx0+(xx+.5)/(scale*samples)-.5-cx)/L;y=(by0+(yy+.5)/(scale*samples)-.5-cy)/L
        sd,*_=boundary(x,y,p);alpha[top:bot]=(sd<=0).reshape(bot-top,samples,BW,samples).mean((1,3))
    blurred=cv2.GaussianBlur(alpha,(0,0),sigma*scale,borderType=cv2.BORDER_CONSTANT)
    Y,X=np.mgrid[0:H,0:W].astype(np.float32)
    X+=(x0-bx0-dx)*scale;Y+=(y0-by0-dy)*scale
    return q['opacity']*cv2.remap(blurred,X,Y,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
