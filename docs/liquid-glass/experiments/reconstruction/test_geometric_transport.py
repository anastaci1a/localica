"""Interface powers and next-boundary correctness; no scene image fits."""
import unittest
import numpy as np
from geometric_optics import fresnel_components,fresnel_unpolarized,reflect_direction,RadialCurvatureBack,CircularHeightSolid,trace_flat_front_orthographic,trace_closed_orthographic,next_convex_boundary
class TransportTests(unittest.TestCase):
 def test_fresnel_normal_brewster_and_equal_index(self):
  np.testing.assert_allclose(fresnel_components(np.array([1.]),1,1.5),[[.04,.04]],atol=1e-15)
  b=fresnel_components(np.cos(np.arctan(1/1.5)),1.5,1);self.assertLess(b[1],1e-28);self.assertGreater(b[0],0)
  np.testing.assert_array_equal(fresnel_components(np.array([0.,.5,1]),1,1),np.zeros((3,2)))
  self.assertEqual(fresnel_unpolarized(0.,1.5,1),1.)
 def test_fresnel_reverse_power_and_reflection_involution(self):
  ci=np.array([.8,.9,1]);ct=np.sqrt(1-(1/1.5)**2*(1-ci*ci));np.testing.assert_allclose(fresnel_components(ci,1,1.5),fresnel_components(ct,1.5,1),atol=1e-14)
  d=np.array([[.6,0,-.8],[0,0,-1.]]);n=np.array([[0.,0.,1.],[0.,0.,1.]])
  np.testing.assert_allclose(reflect_direction(reflect_direction(d,n),n),d,atol=1e-15)
 def test_height_solid_primary_agrees_with_exact_flat_tracer(self):
  back=RadialCurvatureBack([.5,.75,1],[0.,1.,2.]);solid=CircularHeightSolid(back);x=np.linspace(-.97,.97,33);y=np.full_like(x,.03)
  a=trace_flat_front_orthographic(back,x,y,1.5,2);b=trace_closed_orthographic(solid,x,y,1.5,2)
  self.assertTrue(b['complete'].all());np.testing.assert_allclose(a['X'],b['X'],atol=1e-10,rtol=0);np.testing.assert_allclose(a['Y'],b['Y'],atol=1e-10,rtol=0)
 def test_next_boundary_rejects_outward_and_nonunit_rays(self):
  solid=CircularHeightSolid(RadialCurvatureBack([.5,.75,1],[0.,1.,2.]))
  with self.assertRaises(ValueError):next_convex_boundary(solid,np.array([[0.,0.,0.]]),np.array([[0.,0.,1.]]))
  with self.assertRaises(ValueError):next_convex_boundary(solid,np.array([[0.,0.,0.]]),np.array([[0.,0.,-2.]]))
if __name__=='__main__':unittest.main()
