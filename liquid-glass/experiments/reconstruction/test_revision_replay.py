import unittest
import numpy as np
from revision_replay import clean_composite, transmission_signature


class CleanCompositeTests(unittest.TestCase):
    def study(self):
        class Fixture:
            crop=[0,0,8,6]
            source=np.full((6,8,3),.8,np.float32)
            target_crop=np.zeros((6,8,3),np.float32)
            exterior_source=None
            foreground_layers=[]
            transmission_layers=[]
            L=2
            case={'center':[4,3]}
            def shape_coverage(self,p,scale):
                alpha=np.zeros((6*scale,8*scale),np.float32)
                alpha[2*scale:4*scale,3*scale:5*scale]=1
                return alpha
        return Fixture()

    def test_reference_context_cannot_leak_into_reconstruction(self):
        s=self.study();material=np.full((6,8,3),.2,np.float32)
        first=clean_composite(s,{},material)
        s.target_crop[:]=[1,0,1]
        np.testing.assert_array_equal(clean_composite(s,{},material),first)
        np.testing.assert_allclose(first[0],.8)
        np.testing.assert_allclose(first[2:4,3:5],.2)

    def test_photographic_foreground_is_rejected(self):
        s=self.study();s.foreground_layers=[object()]
        with self.assertRaises(AssertionError):clean_composite(s,{},s.source)

    def test_ray_proof_is_invalidated_by_optical_source_or_grid_change(self):
        s=self.study();p={'rim_width':.1,'thin_width':.02,'sigma':2,'core_x':.1}
        first=transmission_signature(s,p,4)
        self.assertEqual(first,transmission_signature(s,{**p,'rim_width':.2},4))
        self.assertNotEqual(first,transmission_signature(s,{**p,'sigma':3},4))
        self.assertNotEqual(first,transmission_signature(s,{**p,'core_x':.2},4))
        self.assertNotEqual(first,transmission_signature(s,p,2))
        s.case={**s.case,'source_sha256':{'source.png':'changed'}}
        self.assertNotEqual(first,transmission_signature(s,p,4))


if __name__=='__main__':unittest.main()
