"""Uncovered-track regression: rasterization must not fake magnification."""
import numpy as np
from background import slider_source

page=np.empty((1080,1920,3),np.float32);page[:]=[.95,.96,.97]
target=page.copy();target[540:560,570:1380]=[.82,.82,.84];target[540:560,570:730]=[0,.5,1]
source,_=slider_source(target,page)
for x in [595,605,625]:
    def blue_span(image):
        c=image[:,x];return np.flatnonzero((c[:,2]-c[:,0]>.4)&(c[:,2]-c[:,1]>.15))
    expected=blue_span(target);actual=blue_span(source)
    assert np.array_equal(actual,expected),(x,actual.tolist(),expected.tolist())
    assert len(actual)==20 and actual.mean()==549.5
# A separate neutral track checks that source construction is consistent for
# low-contrast content too, rather than passing only a saturated-color threshold.
contrast=page[:,1000,0]-source[:,1000,0]
assert np.array_equal(np.flatnonzero(contrast>.065),np.arange(540,560))
assert np.max(np.abs(source[530:535,600]-page[530:535,600]))<1e-5
print('PASS source construction preserves exposed 20px blue/gray tracks and center alignment; no rasterizer stroke inflation')
