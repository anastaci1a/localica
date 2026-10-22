"""Geometry invariants; no private images or fitted score fixtures."""
import unittest
import numpy as np
from geometric_optics import CircularBevel,bevel_profile,refract,trace_flat_front_orthographic,ClosedCircularOval,trace_closed_orthographic

class GeometryTests(unittest.TestCase):
    def setUp(self):
        self.s=CircularBevel();self.x=np.linspace(-.98,.98,501);self.y=np.full_like(self.x,.11)

    def test_gradient_is_derivative_of_section(self):
        h,hx,hy,_=self.s.height_gradient(self.x,self.y);e=1e-6
        for dx,dy,der in [(e,0,hx),(0,e,hy)]:
            fd=(self.s.height_gradient(self.x+dx,self.y+dy)[0]-self.s.height_gradient(self.x-dx,self.y-dy)[0])/(2*e)
            self.assertLess(abs(fd-der).max(),1e-9)
        z,dz,inside=self.s.section(self.x);hh,xx,_,_=self.s.height_gradient(self.x,np.zeros_like(self.x))
        np.testing.assert_array_equal(z,-hh);np.testing.assert_array_equal(dz,-xx)

    def test_snell_reciprocity_and_identity(self):
        q=trace_flat_front_orthographic(self.s,self.x,self.y,1.5,2);n=q['outward_normal'];d=q['exit_direction']
        # Cross-product sine avoids cancellation near zero angle.
        err=abs(1.5*np.linalg.norm(n[:,:2],axis=-1)-np.linalg.norm(np.cross(d,n),axis=-1))
        self.assertLess(err.max(),1e-12)
        back,blocked,_=refract(-d,n,1,1.5);self.assertFalse(blocked.any());self.assertLess(abs(back-[0,0,1]).max(),1e-12)
        one=trace_flat_front_orthographic(self.s,self.x,self.y,1,2)
        self.assertLess(abs(one['X']-self.x).max(),1e-12);self.assertLess(abs(one['Y']-self.y).max(),1e-12)

    def test_symmetry_and_straight_misses(self):
        q=trace_flat_front_orthographic(self.s,self.x,self.y,1.5,2)
        m=trace_flat_front_orthographic(self.s,-self.x,self.y,1.5,2)
        r=trace_flat_front_orthographic(self.s,-self.y,self.x,1.5,2)
        self.assertLess(abs(m['X']+q['X']).max(),1e-12);self.assertLess(abs(r['X']+q['Y']).max(),1e-12)
        outside=trace_flat_front_orthographic(self.s,np.array([1.1]),np.array([0.]),1.5,2)
        self.assertEqual(outside['X'][0],1.1);self.assertFalse(outside['inside'][0]);self.assertTrue(np.isnan(outside['outward_normal']).all())
        seam=trace_flat_front_orthographic(self.s,np.array([1.]),np.array([0.]),1.5,2)
        self.assertTrue(seam['boundary_unresolved'][0]);self.assertFalse(seam['complete'][0]);self.assertTrue(np.isnan(seam['X'][0]))

    def test_rejects_unavailable_transmission(self):
        for index in [1.25,1.5,2.]:
            d,blocked,k=refract([[0,0,-1]],[[.8,0,.6]],index,1)
            self.assertTrue(blocked[0]);self.assertTrue(np.isnan(d).all())
        with self.assertRaises(ValueError):trace_flat_front_orthographic(CircularBevel(depth=.3),self.x,self.y,1.5,2)
        with self.assertRaises(ValueError):trace_flat_front_orthographic(self.s,self.x,self.y,1.5,.01)

    def test_concave_profile_and_plateau_C2(self):
        r=np.array([0,.599999,.6,.600001,1]);h,hr,hrr=bevel_profile(r,.6,.02,3)
        self.assertTrue((hr<=0).all());self.assertTrue((hrr<=0).all());self.assertEqual(h[-1],0)
        self.assertEqual(hr[2],0);self.assertEqual(hrr[2],0);self.assertLess(abs(hrr[3]),1e-5)

    def test_closed_ellipsoid_intersections_and_identity(self):
        s=ClosedCircularOval();x=np.linspace(-.97,.97,121);y=np.full_like(x,.03)
        q=trace_closed_orthographic(s,x,y,1.,2)
        self.assertTrue(q['complete'].all());self.assertLess(abs(q['X']-x).max(),1e-11)
        p=q['entry']-np.array(s.center);d=q['internal_direction'];a=np.array([1,1,1/s.depth**2])
        distance=-2*np.sum(p*a*d,axis=-1)/np.sum(d*a*d,axis=-1)
        self.assertLess(abs(q['exit']-(q['entry']+distance[:,None]*d)).max(),1e-10)
        self.assertLess(abs(s.level_gradient(q['entry'])[0]).max(),1e-12)
        self.assertLess(abs(s.level_gradient(q['exit'])[0]).max(),1e-9)

    def test_closed_surface_gradient_and_branch_status(self):
        s=ClosedCircularOval(kappa=1/3);p=np.array([[.2,.1,-.04],[.5,-.3,-.2]])
        f,g=s.level_gradient(p);e=1e-6
        fd=np.stack([(s.level_gradient(p+axis*e)[0]-s.level_gradient(p-axis*e)[0])/(2*e) for axis in np.eye(3)],-1)
        self.assertLess(abs(fd-g).max(),1e-8)
        x=np.linspace(0,.999999,201);q=trace_closed_orthographic(s,x,np.zeros_like(x),1.5,2)
        self.assertTrue(q['blocked'].any());self.assertTrue(np.isnan(q['X'][q['blocked']]).all())
        self.assertFalse(q['complete'][q['blocked']].any())
        tangent=trace_closed_orthographic(s,np.array([1.,1.1]),np.array([0.,0.]),1.5,2)
        self.assertFalse(tangent['complete'][0]);self.assertTrue(np.isnan(tangent['X'][0]))
        self.assertTrue(tangent['complete'][1]);self.assertEqual(tangent['X'][1],1.1)

if __name__=='__main__':unittest.main()
