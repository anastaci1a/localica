import unittest
import numpy as np
from crowned_footprint import longitudinal,field,boundary


class CrownTests(unittest.TestCase):
    def test_cardinal_bounds_and_unique_crown(self):
        a,b,w=2.5,1.,.185;x=np.linspace(0,a,501)
        q,qd=longitudinal(x,a,b,w)
        self.assertAlmostEqual(q[0],0);self.assertAlmostEqual(q[-1],b)
        self.assertTrue(np.all(np.diff(q)>0));self.assertTrue(np.all(np.diff(qd)>=-1e-12))
        y=np.sqrt(np.maximum(b*b-q*q,0))
        self.assertTrue(np.all(y[1:]<y[0]));np.testing.assert_allclose(field(x,y,[a,b],w)[0],1,atol=1e-14)

    def test_analytic_gradient(self):
        rng=np.random.default_rng(222);x=rng.uniform(-2.4,2.4,100);y=rng.uniform(-.9,.9,100);h=1e-6
        T,gx,gy=field(x,y,[2.5,1],.185)
        np.testing.assert_allclose(gx,(field(x+h,y,[2.5,1],.185)[0]-field(x-h,y,[2.5,1],.185)[0])/(2*h),atol=1e-8)
        np.testing.assert_allclose(gy,(field(x,y+h,[2.5,1],.185)[0]-field(x,y-h,[2.5,1],.185)[0])/(2*h),atol=1e-8)

    def test_scale_rotation_and_mirror(self):
        x=np.linspace(-2.4,2.4,100);y=np.full_like(x,.4)
        d,nx,ny=boundary(x,y,[2.5,1],.185)
        sd,snx,sny=boundary(x*490,y*490,[1225,490],.185)
        np.testing.assert_allclose(sd/490,d,atol=1e-12);np.testing.assert_allclose(snx,nx,atol=1e-12)
        r,rx,ry=boundary(y,x,[1,2.5],.185)
        np.testing.assert_allclose(r,d);np.testing.assert_allclose(rx,ny);np.testing.assert_allclose(ry,nx)
        _,mx,my=boundary(-x,y,[2.5,1],.185)
        np.testing.assert_allclose(mx,-nx);np.testing.assert_allclose(my,ny)

    def test_invalid_sizes(self):
        for half,w in [([1,0],.2),([2,1],0),([2,1],2)]:
            with self.assertRaises(ValueError):boundary(np.array([0.]),np.array([0.]),half,w)


if __name__=='__main__':unittest.main()
