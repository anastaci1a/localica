"""Continuous oval comparator; exponent is a measured-outline hypothesis."""
import numpy as np


def nearest(x,y,half,curve,field):
    """Numerical nearest boundary + exact implicit normal, first quadrant.

    A coarse global angular search brackets the nearest point before vectorized
    ternary refinement. This is an offline SDF, not an interactive GPU method.
    """
    shape=x.shape;xx=np.abs(x.ravel()).astype(float);yy=np.abs(y.ravel()).astype(float)
    theta=np.linspace(0,np.pi/2,129)
    bx,by=curve(theta)
    distance=np.empty(len(xx));NX=np.empty(len(xx));NY=np.empty(len(xx))
    for start in range(0,len(xx),4096):
        stop=min(start+4096,len(xx));px=xx[start:stop];py=yy[start:stop]
        error=(px[:,None]-bx)**2+(py[:,None]-by)**2
        i=np.argmin(error,axis=1);lo=theta[np.maximum(i-1,0)];hi=theta[np.minimum(i+1,128)]
        for _ in range(32):
            a=(2*lo+hi)/3;b=(lo+2*hi)/3;ax,ay=curve(a);bx_,by_=curve(b)
            left=(ax-px)**2+(ay-py)**2<(bx_-px)**2+(by_-py)**2
            hi=np.where(left,b,hi);lo=np.where(left,lo,a)
        qx,qy=curve((lo+hi)/2)
        # Include the exact axis endpoints (the smooth parameterization has
        # infinite angular derivative there for exponents >2).
        err=(qx-px)**2+(qy-py)**2
        for ax,ay in [(half[0],0),(0,half[1])]:
            better=(ax-px)**2+(ay-py)**2<err
            qx=np.where(better,ax,qx);qy=np.where(better,ay,qy);err=(qx-px)**2+(qy-py)**2
        _,gx,gy=field(qx,qy)
        n=np.hypot(gx,gy);NX[start:stop]=gx/n;NY[start:stop]=gy/n
        inside=field(px,py)[0]<=1
        distance[start:stop]=np.sqrt(err)*np.where(inside,-1,1)
    return distance.reshape(shape),NX.reshape(shape)*np.where(x>=0,1,-1),NY.reshape(shape)*np.where(y>=0,1,-1)


def superellipse(x,y,half,exponent):
    power=2/exponent
    def curve(t):return half[0]*np.cos(t)**power,half[1]*np.sin(t)**power
    def field(x,y):
        u=x/half[0];v=y/half[1]
        return u**exponent+v**exponent,exponent*u**(exponent-1)/half[0],exponent*v**(exponent-1)/half[1]
    return nearest(x,y,half,curve,field)


def oval_field(x,y,half,coefficients):
    """Smooth polynomial oval: T=u²+(v/Q(u))², Q=1+a u²+b u⁴."""
    a,b=coefficients;u=x/half[0];v=y/half[1];q=1+a*u*u+b*u**4
    # Outside the supported footprint Q need not stay positive. Mark that region
    # outside; gradients there are not used as surface/boundary normals.
    q=np.maximum(q,.01);dq=2*a*u+4*b*u**3
    t=u*u+(v/q)**2
    tx=(2*u-2*v*v*dq/q**3)/half[0];ty=2*v/(q*q*half[1])
    return t,tx,ty


def polynomial_oval(x,y,half,coefficients):
    a,b=coefficients
    def curve(t):
        u=np.cos(t)
        return half[0]*u,half[1]*np.sin(t)*(1+a*u*u+b*u**4)
    return nearest(x,y,half,curve,lambda x,y:oval_field(x,y,half,coefficients))


def oval_minimum_radius(half,coefficients):
    a,b=coefficients;u=np.linspace(0,1-1e-8,20000);s=np.sqrt(1-u*u)
    q=1+a*u*u+b*u**4;dq=2*a*u+4*b*u**3;ddq=2*a+12*b*u*u
    slope=half[1]/half[0]*(s*dq-u*q/s)
    second=half[1]/half[0]**2*(s*ddq-2*u*dq/s-q/s**3)
    assert np.max(second)<1e-8,'Supported outline must be convex'
    return float(1/np.max(np.abs(second)/(1+slope*slope)**1.5))


def minimum_radius(half,exponent):
    t=np.linspace(.00001,np.pi/2-.00001,10000)
    x=half[0]*np.cos(t)**(2/exponent);y=half[1]*np.sin(t)**(2/exponent)
    fx=exponent*x**(exponent-1)/half[0]**exponent;fy=exponent*y**(exponent-1)/half[1]**exponent
    fxx=exponent*(exponent-1)*x**(exponent-2)/half[0]**exponent;fyy=exponent*(exponent-1)*y**(exponent-2)/half[1]**exponent
    curvature=(fxx*fy*fy+fyy*fx*fx)/(fx*fx+fy*fy)**1.5
    return float(1/curvature.max())
