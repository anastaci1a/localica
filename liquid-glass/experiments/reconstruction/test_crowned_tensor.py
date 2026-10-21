import copy,unittest
import numpy as np
from crowned_tensor import mapping,render,gaussian_coverage_shadow
from settled import RECIPES,mapping as original_mapping

class ToolbarTests(unittest.TestCase):
    def test_exponent_four_recovers_incumbent_map(self):
        p=copy.deepcopy(RECIPES['regular']);rng=np.random.default_rng(222)
        x=rng.uniform(-2.4,2.4,500);y=rng.uniform(-1.1,1.1,500)
        X,Y=original_mapping(x,y,p);p['tangent_exponent']=4
        A,B=mapping(x,y,p);np.testing.assert_allclose(A,X,atol=1e-14);np.testing.assert_allclose(B,Y,atol=1e-14)

    def test_color_blur_never_creates_dark_boundary_on_uniform_glass(self):
        p=copy.deepcopy(RECIPES['regular']);p['footprint_softness']=.08;p['post_sigma']=.5/170
        p['material']=[1,1,1]+[0]*17+[1,1,1]
        source=np.full((80,160,3),.4,dtype=np.float32)
        case={'center':[80,40],'half':[46.1176470588,20],'crop':[30,16,130,64]}
        rgb,alpha=render(source,case,p)
        np.testing.assert_allclose(rgb[alpha>.01],.4,atol=2e-7)

    def test_crown_map_mirror_symmetry(self):
        p=copy.deepcopy(RECIPES['regular']);p['footprint_softness']=.08
        x=np.linspace(-2.2,2.2,200);y=np.full_like(x,.8)
        X,Y=mapping(x,y,p);A,B=mapping(-x,y,p)
        np.testing.assert_allclose(A,-X,atol=1e-14);np.testing.assert_allclose(B,Y,atol=1e-14)

class ShadowTests(unittest.TestCase):
    def test_shadow_diagnostic_crop_does_not_clip_generating_shape(self):
        p=copy.deepcopy(RECIPES['regular']);p['footprint_softness']=.1
        p['shadow']={'opacity':.2,'sigma':.12,'offset':[.01,.04]}
        case={'center':[80,40],'half':[46.1176470588,20],'crop':[20,10,140,75]}
        full=gaussian_coverage_shadow(case,p)
        case['crop']=[50,20,110,60]
        cropped=gaussian_coverage_shadow(case,p)
        np.testing.assert_allclose(cropped,full[10:50,30:90],atol=1e-7)

    def test_zero_opacity_is_exact_identity(self):
        p=copy.deepcopy(RECIPES['regular']);p['shadow']={'opacity':0,'sigma':.12,'offset':[.01,.04]}
        case={'center':[80,40],'half':[46.1176470588,20],'crop':[20,10,140,75]}
        self.assertEqual(float(gaussian_coverage_shadow(case,p).max()),0.)

if __name__=='__main__':unittest.main()
