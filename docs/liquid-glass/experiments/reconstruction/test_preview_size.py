"""Preview caps preserve aspect/large originals without changing measurements."""
import unittest
from preview_size import dimensions


class PreviewSizeTests(unittest.TestCase):
    def test_small_crop_doubles_until_720_width(self):
        self.assertEqual(dimensions(240,235),(480,470))
        self.assertEqual(dimensions(500,300),(720,432))

    def test_bars_use_1080_width(self):
        self.assertEqual(dimensions(700,200,large=True),(1080,309))
        self.assertEqual(dimensions(1080,335,large=True),(1080,335))

    def test_larger_original_is_not_downscaled_or_enlarged(self):
        self.assertEqual(dimensions(1500,800),(1500,800))
        self.assertEqual(dimensions(1500,800,large=True),(1500,800))

    def test_high_quality_always_has_1080_width(self):
        self.assertEqual(dimensions(240,235,high_quality=True),(1080,1058))
        self.assertEqual(dimensions(2720,2620,high_quality=True),(1080,1040))

    def test_invalid_source_size(self):
        for width,height in [(0,1),(-1,2),(1,True),(2.5,3)]:
            with self.assertRaises(ValueError):dimensions(width,height)


if __name__=='__main__':unittest.main()
