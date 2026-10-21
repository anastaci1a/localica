"""Two independent paths through resize/registration must agree."""
import json
import numpy as np
from raster_input import pixel_center_scale,rescale_affine
rng=np.random.default_rng(21);p=np.c_[rng.uniform(-1000,2000,(1024,2)),np.ones(1024)].T
matrix=np.array([[1.1,.2,4],[-.2,1.1,-3],[0,0,1.]])
rows=[]
for source,destination in [(.5,.5),(2.,2.),(3.1,2.),(1.,1.)]:
    S=pixel_center_scale(source);D=pixel_center_scale(destination);new=rescale_affine(matrix,source,destination)
    old_path=D@matrix@p;new_path=new@S@p;error=float(abs(old_path-new_path).max());assert error<2e-11
    short=rescale_affine(matrix[:2],source,destination);assert np.array_equal(short,new[:2])
    rows.append(dict(source_scale=source,destination_scale=destination,path_max_error=error))
assert np.array_equal(rescale_affine(matrix,1,1),matrix)
assert np.array_equal(pixel_center_scale(2)@np.array([0.,0.,1]),[.5,.5,1])
expected=2*matrix[:2,2]+.5*(np.ones(2)-matrix[:2,:2]@np.ones(2))
assert np.allclose(rescale_affine(matrix,2,2)[:2,2],expected,rtol=0,atol=1e-15)
for invalid in [0.,-1.,float('inf'),float('nan')]:
    try:pixel_center_scale(invalid)
    except ValueError:pass
    else:raise AssertionError('Invalid scale accepted')
print(json.dumps(dict(passed=True,controls=rows,scope='Coordinate/registration convention only; no content registration or optical scoring.'),indent=2))
