"""Smooth convex pill footprint; a geometric hypothesis, not Apple's formula.

All inputs share one length unit. q is even and convex. The footprint q(x)^2 +
y^2 <= b^2 has no flat top/bottom interval. Its normal comes from the analytic
implicit gradient. r-b is a boundary coordinate, not Euclidean signed distance.
"""
import numpy as np
from scipy.special import expit


def longitudinal(x, a, b, softness):
    if not (a>=b>0 and 0<softness<=1):
        raise ValueError('Require major half >= minor half > 0 and 0 < softness <= 1')
    e=softness*b;neck=a-b
    base=np.logaddexp(0.,(e-neck)/e)
    radius=np.hypot(x,e)
    den=np.logaddexp(0.,(np.hypot(a,e)-neck)/e)-base
    if den<=0:raise ValueError('Unresolved footprint scale')
    q=b*(np.logaddexp(0.,(radius-neck)/e)-base)/den
    derivative=b/den*expit((radius-neck)/e)*x/(e*radius)
    return q,derivative


def field(x,y,half,softness):
    """Implicit value T and its exact gradient; T=1 is the boundary."""
    horizontal=half[0]>=half[1]
    major,minor=(x,y) if horizontal else (y,x)
    a,b=max(half),min(half)
    q,qd=longitudinal(major,a,b,softness)
    T=(q*q+minor*minor)/(b*b)
    ga=2*q*qd/(b*b);gb=2*minor/(b*b)
    return (T,ga,gb) if horizontal else (T,gb,ga)


def boundary(x,y,half,softness):
    T,gx,gy=field(x,y,half,softness)
    norm=np.hypot(gx,gy)
    # Normal is undefined at the center; symmetry-preserving zero is sufficient
    # for an image field whose center displacement is separately declared.
    nx=np.divide(gx,norm,out=np.zeros_like(gx),where=norm>1e-14)
    ny=np.divide(gy,norm,out=np.zeros_like(gy),where=norm>1e-14)
    return min(half)*(np.sqrt(T)-1),nx,ny
