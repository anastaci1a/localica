"""Reuse fixed geometry/lighting while proposing normal-spline coefficients.

F_new=F_base-(a_new-a_base)n, including the fixed orientation gain.
Background samples are recomputed for every proposal. No rendered image is
warped; the final candidate is rescored independently with Study.render.
"""
import numpy as np
from scipy.interpolate import BSpline
from extended_fit import KNOTS
from prepared import Prepared


class ProfileCache:
    def __init__(self,study,p,co):
        assert p['spline']
        self.study=study;self.prepared=Prepared(study,p,co,1);self.parts=[]
        coefficients=np.array([p[f'c{i}'] for i in range(7)])
        for row,bottom,W,x,y in study.raster_tiles(1):
            _,_,half,radius,(sd,nx,ny)=study.shape(x,y,p);d=np.maximum(-sd,0)
            basis=BSpline.design_matrix(np.clip(d/radius,0,1).ravel(),KNOTS,3).toarray()[:,:7].reshape((*x.shape,7))
            cap=nx*nx if half[0]>=half[1] else ny*ny
            weight=1+p.get('cap_normal_gain',0)*cap
            self.parts.append((basis,(basis@coefficients)*weight,weight,nx*study.L,ny*study.L))

    def render(self,coefficients):
        def tiles():
            for tile,(basis,base,weight,nx,ny) in zip(self.prepared.tiles,self.parts):
                row,bottom,W,X,Y,gain,light,blend=tile
                delta=(basis@np.asarray(coefficients))*weight-base
                yield row,bottom,W,X-delta*nx,Y-delta*ny,gain,light,blend
        return self.prepared.render(tiles())
