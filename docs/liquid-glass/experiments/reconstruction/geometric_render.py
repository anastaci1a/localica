"""Shared geometric transmission/pixel integration for circle and pill controls.

Finite pixel integration is AA, not optical blur. Raw unit transmission is common;
optional frozen constant grading reproduces historical diagnostics only.
"""
import cv2,numpy as np
from geometric_optics import trace_flat_front_orthographic

def render_rows(source,case,p,surface,index,distance,start,stop,raw_transmission=False,exact_bilinear=False,samples_per_axis=4,source_known=None,required_pixels=None,return_support=False,return_layers=False):
    """Finite pixel integration with coupled lens/background coverage.

    Default4 preserves the historical geometric control. Explicit2 preserves an
    existing2x2 element protocol; changing quadrature is not an optical gain.
    Optional known support audits all four source texels. Unknown clear exterior
    may be NaN only when an explicit required-pixel mask excludes it. Every lens
    subray and every subray of a required pixel must remain defined and known.
    No unsupported coordinate is clamped or sampled. Confidence means the input
    contract's known/model support, not necessarily observed photographic truth.
    """
    if isinstance(samples_per_axis,bool) or int(samples_per_axis)!=samples_per_axis or not 1<=samples_per_axis<=8:raise ValueError('Integer AA samples per axis1..8 required')
    s=int(samples_per_axis);x0,y0,x1,y1=case['crop'];L=min(case['half'])
    yy,xx=np.mgrid[y0+start:y0+stop:1/s,x0:x1:1/s]
    phase=(s-1)/(2*s);x=(xx-phase-case['center'][0])/L;y=(yy-phase-case['center'][1])/L
    q=trace_flat_front_orthographic(surface,x,y,index,distance)
    if not q['complete'].all():raise ValueError('Unresolved primary path; no pixel fallback')
    X=q['X']*L+case['center'][0];Y=q['Y']*L+case['center'][1]
    finite=np.isfinite(X)&np.isfinite(Y);defined=finite&(X>=0)&(Y>=0)&(X<source.shape[1]-1)&(Y<source.shape[0]-1)
    if source_known is None and required_pixels is None:
        if not defined.all():raise ValueError('Undefined bilinear source footprint')
    else:
        if source_known is not None:
            if source_known.shape!=source.shape[:2]:raise ValueError('Known mask must match source')
            rows,cols=np.nonzero(defined);a=np.floor(X[rows,cols]).astype(int);b=np.floor(Y[rows,cols]).astype(int)
            known=np.zeros_like(defined)
            known[rows,cols]=(source_known[b,a]>0)&(source_known[b,a+1]>0)&(source_known[b+1,a]>0)&(source_known[b+1,a+1]>0);defined&=known
        if required_pixels is None:required=np.ones((stop-start,x1-x0),bool)
        else:
            required=np.asarray(required_pixels,bool)
            if required.shape==(y1-y0,x1-x0):required=required[start:stop]
            if required.shape!=(stop-start,x1-x0):raise ValueError('Required mask must match crop or rows')
        required_rays=np.repeat(np.repeat(required,s,axis=0),s,axis=1)|q['inside']
        if np.any(required_rays&~defined):raise ValueError('Unknown source for lens or required guard ray')
    # Index only defined rays. Invalid unscored exterior stays explicitly NaN.
    C=np.full(X.shape+(3,),np.nan,float)
    if defined.all() and not exact_bilinear:
        C=cv2.remap(source,X.astype(np.float32),Y.astype(np.float32),cv2.INTER_LINEAR)
    else:
        rows,cols=np.nonzero(defined);a=np.floor(X[rows,cols]).astype(int);b=np.floor(Y[rows,cols]).astype(int)
        if exact_bilinear:
            fx=(X[rows,cols]-a)[:,None];fy=(Y[rows,cols]-b)[:,None]
            C[rows,cols]=(source[b,a]*(1-fx)+source[b,a+1]*fx)*(1-fy)+(source[b+1,a]*(1-fx)+source[b+1,a+1]*fx)*fy
        else:
            C[rows,cols]=cv2.remap(source,X[rows,cols].astype(np.float32)[None],Y[rows,cols].astype(np.float32)[None],cv2.INTER_LINEAR)[0]
    if not raw_transmission:
        fixed=C*np.asarray(p['material'][:3])+np.asarray(p['material'][3:6]);C=np.where(q['inside'][...,None],fixed,C)
    result=C.reshape(stop-start,s,x1-x0,s,3).mean((1,3)).astype(np.float32)
    bounds=[float(X.min()),float(Y.min()),float(X.max()),float(Y.max())]
    support=defined.reshape(stop-start,s,x1-x0,s).all((1,3))
    out=(result,bounds,support) if return_support else (result,bounds)
    if return_layers:
        alpha=q['inside'].reshape(stop-start,s,x1-x0,s).mean((1,3))
        lens=np.where(q['inside'][...,None],C,0).reshape(stop-start,s,x1-x0,s,3).mean((1,3))
        lens=np.divide(lens,alpha[...,None],out=np.zeros_like(lens),where=alpha[...,None]>0)
        clear=np.where((~q['inside'])[...,None],C,0).reshape(stop-start,s,x1-x0,s,3).mean((1,3))
        out+=({'lens_color':lens,'coverage':alpha,'clear_premultiplied':clear},)
    return out


def coverage_blur(material,coverage,sigma_pixels):
    """Ordinary output-color filter; preserve sharp physical shape coverage.

    Reuses crowned_tensor's coverage-normalized numerator/denominator operation
    with an explicit finite3sigma kernel. This is a material approximation, not
    geometric displacement, AA, dispersion or a full microfacet transport model.
    RGB outside coverage may be undefined and contributes nothing to the filter.
    Caller must certify crop padding and all covered/source samples first.
    """
    if not np.isfinite(sigma_pixels) or sigma_pixels<0:raise ValueError('Finite nonnegative color sigma required')
    material=np.asarray(material);coverage=np.asarray(coverage)
    if material.shape!=coverage.shape+(3,) or np.any(coverage<0) or np.any(coverage>1) or not np.isfinite(coverage).all():raise ValueError('Coverage/material shape or alpha invalid')
    if not np.isfinite(material[coverage>0]).all():raise ValueError('Undefined lens material cannot be filtered')
    if sigma_pixels==0:return material.copy()
    radius=int(np.ceil(3*sigma_pixels));size=2*radius+1
    numerator=cv2.GaussianBlur(np.where((coverage>0)[...,None],material,0)*coverage[...,None],(size,size),sigma_pixels,borderType=cv2.BORDER_CONSTANT)
    denominator=cv2.GaussianBlur(coverage,(size,size),sigma_pixels,borderType=cv2.BORDER_CONSTANT)
    return np.divide(numerator,denominator[...,None],out=np.zeros_like(numerator),where=denominator[...,None]>0)


def neutral_color_response(material,gain=1.,bias=0.,highlight_lift=0.,return_preclip=False):
    """Shared achromatic encoded-RGB grading, independent of geometry/pixels.

    Constant gain/bias includes a neutral white veil (gain=t,bias=1-t).
    Optional neutral highlight lift uses one fixed luminance smoothstep(.25,.65).
    This is an empirical color response, not a radiometric transmission claim.
    Apply before coverage_blur; never substitute it for refraction or an outline.
    """
    if not np.isfinite([gain,bias,highlight_lift]).all() or gain<0:raise ValueError('Finite common tone parameters and nonnegative gain required')
    value=np.asarray(material)*gain+bias
    y=value@np.array([.2126,.7152,.0722]);u=np.clip((y-.25)/.4,0,1);w=u**3*(u*(6*u-15)+10)
    value=value+highlight_lift*w[...,None]
    return (np.clip(value,0,1),value) if return_preclip else np.clip(value,0,1)
