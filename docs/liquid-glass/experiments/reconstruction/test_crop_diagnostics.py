import unittest
import numpy as np
from crop_diagnostics import diagnostics,edge_summary


class CropTests(unittest.TestCase):
    def test_identical_crops_and_constant_edges(self):
        im=np.full((30,40,3),.5);r=diagnostics(im,im,{'whole':[0,0,1,1]})['whole']
        self.assertEqual(r['rgb_rmse_255'],0)
        self.assertEqual(r['target_edges']['x']['centroid_rgb'],[None]*3)

    def test_separated_channels_report_direction(self):
        im=np.zeros((40,40,3));im[:,12:,0]=1;im[:,20:,1]=1;im[:,28:,2]=1
        s=edge_summary(im)
        np.testing.assert_allclose(s['x']['centroid_rgb'],[.3,.5,.7])
        self.assertEqual(s['y']['centroid_rgb'],[None]*3)

    def test_blur_width_and_chromaticity_are_distinct(self):
        sharp=np.zeros((40,40,3));sharp[:,20:]=1
        broad=sharp.copy();broad[:,16:24]=np.linspace(0,1,8)[None,:,None]
        self.assertGreater(edge_summary(broad)['x']['effective_width_rgb'][0],edge_summary(sharp)['x']['effective_width_rgb'][0])
        d=diagnostics(sharp,broad,{'edge':[0,0,1,1]})['edge']
        self.assertAlmostEqual(d['chroma_rmse_255'],0)
        self.assertGreater(d['luma_rmse_255'],0)

    def test_no_implicit_resize_or_invalid_bounds(self):
        im=np.zeros((10,10,3))
        with self.assertRaises(ValueError):diagnostics(im,im[:9],{'c':[0,0,1,1]})
        with self.assertRaises(ValueError):diagnostics(im,im,{'c':[.8,0,.3,1]})


if __name__=='__main__':unittest.main()
