"""Control classification, independently of private photographs or scores."""
import json
import numpy as np
from map_compatibility_audit import sample_map
from surface_compatibility import forward_displacement

x, y = np.meshgrid(np.linspace(-1.7, 1.7, 31), np.linspace(-.7, .7, 23))
inside = np.ones(x.shape, bool)
zero = sample_map(lambda x, y: np.stack([x*0, y*0], -1), x, y, inside)
assert zero['unresolved'] == inside.size
assert zero['necessary_branch_violation'] == 0
rotation = sample_map(lambda x, y: np.stack([-y, x], -1), x, y, inside)
assert rotation['necessary_branch_violation'] == inside.size-1
assert rotation['unresolved'] == 1
assert rotation['negative_map_determinant_fraction'] == 0

def physical(x, y):
    # A known back height .5+.01x²+.02y², backdrop distance2.5, index1.5.
    return forward_displacement(np.stack([.02*x, .04*y], -1), 2-.01*x*x-.02*y*y, 1.5)

valid = sample_map(physical, x, y, inside)
assert valid['necessary_branch_violation'] == 0
assert valid['necessary_branch_admitted'] > inside.size/2
try:
    sample_map(lambda x, y: np.full((*x.shape, 2), np.nan), x, y, inside)
except ValueError:
    pass
else:
    raise AssertionError('Nonfinite map silently classified as unresolved')
print(json.dumps(dict(passed=True,zero=zero,rotation=rotation,known_surface=valid), indent=2))
