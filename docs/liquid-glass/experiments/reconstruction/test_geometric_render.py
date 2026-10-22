"""Native AA phase and required-source support, independent of target images."""
import unittest
import numpy as np
from geometric_optics import CircularBevel,trace_flat_front_orthographic
from geometric_render import render_rows,coverage_blur
class GeometricRenderTest(unittest.TestCase):
 def setUp(self):
  y,x=np.mgrid[:100,:100];self.source=np.stack([x/100,y/100,(x+y)/200],-1);self.surface=CircularBevel(core=.6,depth=.02);self.case=dict(center=[50,50],half=[10,10],crop=[39,45,64,48])
 def test_two_sample_phase_matches_direct_rays(self):
  got,_=render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,samples_per_axis=2)
  yy,xx=np.mgrid[45:48:.5,39:64:.5];q=trace_flat_front_orthographic(self.surface,(xx-.25-50)/10,(yy-.25-50)/10,1.5,2);X=q['X']*10+50;Y=q['Y']*10+50
  # Linear image has an exact closed-form bilinear sample.
  direct=np.stack([X/100,Y/100,(X+Y)/200],-1).reshape(3,2,25,2,3).mean((1,3)).astype(np.float32)
  np.testing.assert_array_equal(got,direct)
 def test_returned_layers_reconstruct_pixel_integration(self):
  C,_,layers=render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,return_layers=True)
  rebuilt=(layers['coverage'][...,None]*layers['lens_color']+layers['clear_premultiplied']).astype(np.float32)
  np.testing.assert_array_equal(C,rebuilt)
 def test_default_four_is_explicit_four(self):
  a,_=render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True);b,_=render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,samples_per_axis=4);np.testing.assert_array_equal(a,b)
 def test_unknown_clear_exterior_nan_but_lens_protected(self):
  known=np.ones((100,100));known[:,62:]=0
  got,_,support=render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,source_known=known,required_pixels=np.zeros((3,25),bool),return_support=True)
  self.assertTrue(np.isnan(got[:,23:]).all());self.assertFalse(support[:,23:].any());self.assertTrue(np.isfinite(got[:,:20]).all())
  with self.assertRaises(ValueError):render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,source_known=known)
  known[:,49:51]=0
  with self.assertRaises(ValueError):render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,source_known=known,required_pixels=np.zeros((3,25),bool))
 def test_required_clear_exterior_rejects_unknown(self):
  known=np.ones((100,100));known[:,62:]=0;required=np.zeros((3,25),bool);required[:,-1]=1
  with self.assertRaises(ValueError):render_rows(self.source,self.case,{},self.surface,1.5,2,0,3,True,True,source_known=known,required_pixels=required)
class CoverageFilterTest(unittest.TestCase):
 def test_uniform_color_coverage_and_zero(self):
  cov=np.zeros((40,40));cov[10:30,10:30]=1.;cov[9,10:30]=.25;before=cov.copy();M=np.full((40,40,3),np.nan);M[cov>0]=[.2,.4,.7]
  B=coverage_blur(M,cov,1.7);np.testing.assert_allclose(B[cov>0],np.broadcast_to([.2,.4,.7],B[cov>0].shape),atol=1e-15);np.testing.assert_array_equal(cov,before);np.testing.assert_array_equal(coverage_blur(M,cov,0),M)
 def test_bad_lens_color_and_bad_sigma_rejected(self):
  cov=np.ones((10,10));M=np.ones((10,10,3));M[4,4]=np.nan
  with self.assertRaises(ValueError):coverage_blur(M,cov,1.)
  with self.assertRaises(ValueError):coverage_blur(np.zeros((10,10,3)),cov,-1.)
if __name__=='__main__':unittest.main()
