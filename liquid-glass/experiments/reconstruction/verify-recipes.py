"""Frozen-model invariants without Apple inputs, a browser or a GPU."""
import numpy as np
from settled import RECIPES, mapping, lighting, radial, icon_color
from render import lookup, illumination, case_geometry
from reproduce import CASES

rng = np.random.default_rng(222)
for name, p in RECIPES.items():
    case = CASES[name]
    L = min(case['half'])
    x = rng.uniform(-.5, .5, 200)
    y = rng.uniform(-.5, .5, 200)
    light = illumination(x*L, y*L, case['half'], case['radius'], p['rim_width']*L,
                         case_geometry(case, x*L, y*L))
    assert np.max(np.abs(light-lighting(x, y, p))) < 1e-6, name
    if name != 'media':
        expected = np.array(lookup(x*L, y*L, case['half'], case['radius'],
                                  p['parameters'], p['model'], oval=case.get('oval')))/L
        actual = np.array(mapping(x, y, p))
        assert np.max(np.abs(expected-actual)) < 1e-6, name
    mirrored = np.array(mapping(-x, y, p))
    actual = np.array(mapping(x, y, p))
    assert np.max(np.abs(mirrored-actual*np.array([[-1], [1]]))) < 1e-6, name
    assert np.isfinite(light).all() and np.isfinite(actual).all(), name

p = RECIPES['media']['parameters']
start = p['band']/2
end = start*(1+p['width'])
assert end < 1
assert np.all(radial(np.array([end, end+.1, 1]), p) == 0)
h = 1e-6
def derivative(d):
    return (radial(d+h, p)-radial(d-h, p))/(2*h)
assert abs(derivative(start-h)-derivative(start+h)) < .01
d = np.linspace(0, 1, 30001)
eigenvalue = 1+np.gradient(radial(d, p), d)
assert np.count_nonzero(np.diff(np.sign(eigenvalue))) == 1

for L in [20, 56.5, 245, 980]:
    # The independent scalar expression tests the color chain. Constant color
    # must stay constant through its final Gaussian filter at every length.
    C = np.full((21, 23, 3), [.3, .6, .8])
    p = RECIPES['media']['icon']
    q = 1-np.array(p['strength'])*(1-C[0, 0])**np.array(p['gamma'])
    lum = q @ np.array([.2126, .7152, .0722])
    q = np.clip(lum+(1+.2*np.clip((1-lum)/.15, 0, 1)**2)*(q-lum), 0, 1)
    q -= .5*np.clip((q-.9)/.1, 0, 1)**3*(1-q)
    assert np.max(np.abs(icon_color(C, p, L)-q)) < 1e-12
print('PASS five normalized maps/materials vs reference-coordinate equations; symmetry; finite outputs')
print('PASS playback tail join/support/fold count; icon-color response at four scales')
