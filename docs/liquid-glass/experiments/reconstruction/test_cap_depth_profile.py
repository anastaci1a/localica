import copy,unittest
import numpy as np
from extended_fit import Study

class CapDepthTests(unittest.TestCase):
 def setUp(self):
  self.s=Study.__new__(Study);self.s.half=np.array([1.5,1.]);self.s.radius=1.;self.s.case={}
  self.p=dict(spline=True,core_x=-.2,core_y=-.2,tangent=.1,tangent_band=.8,tangent_exponent=4,cap_normal_gain=.3,cap_tangent_fade=0,coupling=0,rim_width=.1,thin_width=.02,crown_softness=.08)
  self.p.update({f'c{i}':c for i,c in enumerate([.5,1,.4,.2,.1,0,0])})
  self.y,self.x=np.mgrid[-.98:.98:31j,-1.48:1.48:43j]
 def field(self,p=None,x=None,y=None,s=None):return (s or self.s).geometry_field(self.x if x is None else x,self.y if y is None else y,p or self.p)
 def test_zero_profile_preserves_archived_map(self):
  a=self.field();b=self.field({**self.p,'cap_edge_profile':[0,0,0]})
  for aa,bb in zip(a,b):np.testing.assert_array_equal(aa,bb)
 def test_changes_only_normal_displacement_near_edge(self):
  a=self.field();b=self.field({**self.p,'cap_edge_profile':[.4,.2,-.1]});*_,(sd,nx,ny)=self.s.shape(self.x,self.y,self.p)
  dx=b[0]-a[0];dy=b[1]-a[1]
  np.testing.assert_allclose(dx*ny-dy*nx,0,atol=1e-14)
  np.testing.assert_array_equal(a[2],b[2]);np.testing.assert_array_equal(a[3],b[3])
  np.testing.assert_array_equal(dx[-sd>=.09],0);np.testing.assert_array_equal(dy[-sd>=.09],0)
  self.assertGreater(np.max(abs(dx)),.01)
 def test_mirror_symmetry(self):
  p={**self.p,'cap_edge_profile':[.4,.2,-.1]};a=self.field(p);b=self.field(p,x=-self.x)
  np.testing.assert_allclose(b[0],-a[0],atol=1e-14);np.testing.assert_allclose(b[1],a[1],atol=1e-14)
 def test_vertical_pill_uses_its_long_axis(self):
  p={**self.p,'cap_edge_profile':[.4,.2,-.1]};other=copy.copy(self.s);other.half=self.s.half[::-1]
  a=self.field(p);b=self.field(p,x=self.y,y=self.x,s=other)
  np.testing.assert_allclose(b[0],a[1],atol=1e-14);np.testing.assert_allclose(b[1],a[0],atol=1e-14)
 def test_invalid_profile_rejected(self):
  for q in [[float('nan')],[0]*8,[[1,2]]]:
   with self.assertRaises(ValueError):self.field({**self.p,'cap_edge_profile':q})
  with self.assertRaises(ValueError):self.field({**self.p,'spline':False,'amplitude':1,'band':.3,'power':2,'cap_edge_profile':[1]})

if __name__=='__main__':unittest.main()
