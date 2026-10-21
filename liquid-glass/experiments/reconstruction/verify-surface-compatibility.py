"""Independent analytic/finite-difference controls; no private image inputs."""
import json
import numpy as np
from surface_compatibility import forward_displacement, inverse_gradient, compatibility_terms, required_clearance


def must_reject(function, *args):
    try:
        function(*args)
    except ValueError:
        return
    raise AssertionError('Invalid branch was accepted')


x, y = np.meshgrid(np.linspace(-1.7, 1.7, 31), np.linspace(-.7, .7, 23))
h = .5 + .01*x*x + .02*y*y
gradient = np.stack([.02*x, .04*y], axis=-1)
clearance = 2.5-h
results = []
for index in [1.33, 1.5, 1.8]:
    def displacement(x, y):
        return forward_displacement(np.stack([.02*x, .04*y], axis=-1), 2-.01*x*x-.02*y*y, index)
    v = displacement(x, y)
    inverse_error = float(np.max(abs(inverse_gradient(v, clearance, index)-gradient)))
    assert inverse_error < 1e-14
    # Scalar angular Snell calculation is independent of the vector formula.
    slope = np.linalg.norm(gradient, axis=-1)
    incident = np.arctan(slope)
    outgoing = np.arcsin(index*np.sin(incident))
    scalar_length = clearance*np.tan(outgoing-incident)
    angular_error = float(np.max(abs(np.linalg.norm(v, axis=-1)-scalar_length)))
    assert angular_error < 1e-14
    step = 5e-5
    dx = (displacement(x+step, y)-displacement(x-step, y))/(2*step)
    dy = (displacement(x, y+step)-displacement(x, y-step))/(2*step)
    c, m, k = compatibility_terms(v, dx, dy)
    radius = np.sqrt(m+clearance**2)
    residual = (index*radius-clearance)*c-index*k/radius
    compatibility_error = float(np.max(abs(residual)))
    assert compatibility_error < 1e-9
    usable = abs(c)>1e-7
    recovered = required_clearance(k[usable]/c[usable], m[usable], index)
    clearance_error = float(np.max(abs(recovered-clearance[usable])))
    assert clearance_error < 2e-5
    assert np.max(abs(c))>1e-6  # A valid surface can have nonzero map curl.
    for scale in [.3, 2, 7]:
        assert np.max(abs(forward_displacement(gradient, clearance*scale, index)-v*scale)) < 1e-14
        assert np.max(abs(inverse_gradient(v*scale, clearance*scale, index)-gradient)) < 1e-14
    results.append(dict(index=index,gradient_error=inverse_error,angular_error=angular_error,compatibility_error=compatibility_error,clearance_error=clearance_error,nonzero_map_curl=float(np.max(abs(c)))))
assert np.array_equal(forward_displacement(np.zeros((3,2)), np.ones(3), 1.5), np.zeros((3,2)))
must_reject(inverse_gradient, np.array([1.,0.]), .1, 1.5)
must_reject(forward_displacement, np.array([2.,0.]), 1., 1.5)
must_reject(inverse_gradient, np.array([0.,0.]), 1., 1.)
# Rotational displacement has rho=M/2, incompatible with this transmitted family.
v=np.array([-.3,.4]);c,m,k=compatibility_terms(v,np.array([0.,1.]),np.array([-1.,0.]))
assert abs(k/c-m/2)<1e-15
must_reject(required_clearance,k/c,m,1.5)
print(json.dumps(dict(passed=True,controls=results,limits='Local geometric identities and branch rejection; no Apple fidelity, closed-body clearance or image-score claim.'),indent=2))
