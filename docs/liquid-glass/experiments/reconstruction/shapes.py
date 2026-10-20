"""Continuous oval comparator; exponent is a measured-outline hypothesis."""
import numpy as np


def superellipse(x,y,half,exponent):
    """Numerical nearest boundary + exact implicit normal, first quadrant.

    A coarse global angular search brackets the nearest point before vectorized
    ternary refinement. This is an offline SDF, not an interactive GPU method.
    """
    shape=x.shape;xx=np.abs(x.ravel()).astype(float);yy=np.abs(y.ravel()).astype(float)
    power=2/exponent;theta=np.linspace(0,np.pi/2,129)
    bx=half[0]*np.cos(theta)**power;by=half[1]*np.sin(theta)**power
    distance=np.empty(len(xx));NX=np.empty(len(xx));NY=np.empty(len(xx))
    for start in range(0,len(xx),4096):
        stop=min(start+4096,len(xx));px=xx[start:stop];py=yy[start:stop]
        error=(px[:,None]-bx)**2+(py[:,None]-by)**2
        i=np.argmin(error,axis=1);lo=theta[np.maximum(i-1,0)];hi=theta[np.minimum(i+1,128)]
        def curve(t):return half[0]*np.cos(t)**power,half[1]*np.sin(t)**power
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
        gx=(qx/half[0])**(exponent-1)/half[0];gy=(qy/half[1])**(exponent-1)/half[1]
        n=np.hypot(gx,gy);NX[start:stop]=gx/n;NY[start:stop]=gy/n
        inside=(px/half[0])**exponent+(py/half[1])**exponent<=1
        distance[start:stop]=np.sqrt(err)*np.where(inside,-1,1)
    return distance.reshape(shape),NX.reshape(shape)*np.where(x>=0,1,-1),NY.reshape(shape)*np.where(y>=0,1,-1)


def minimum_radius(half,exponent):
    t=np.linspace(.00001,np.pi/2-.00001,10000)
    x=half[0]*np.cos(t)**(2/exponent);y=half[1]*np.sin(t)**(2/exponent)
    fx=exponent*x**(exponent-1)/half[0]**exponent;fy=exponent*y**(exponent-1)/half[1]**exponent
    fxx=exponent*(exponent-1)*x**(exponent-2)/half[0]**exponent;fyy=exponent*(exponent-1)*y**(exponent-2)/half[1]**exponent
    curvature=(fxx*fy*fy+fyy*fx*fx)/(fx*fx+fy*fy)**1.5
    return float(1/curvature.max())
