"""Sample the necessary flat-front/single-back-surface compatibility condition.

python map_compatibility_audit.py INPUTS RECIPE.json OUTPUT.json
INPUTS contains hashed Study cases in subdirectories. This does not fit images,
change their masks, certify a physical solid, or bound attainable image error.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from surface_compatibility import compatibility_terms


def sample_map(field, x, y, inside, steps=(2e-4, 1e-4, 5e-5)):
    """Finite-step evidence, not interval-certified derivative bounds.

    field returns inverse-map displacement F(x,y)-(x,y), in common length units.
    Near-zero and step-sensitive quantities remain unresolved, never failures.
    """
    if len(steps) < 3 or any(not np.isfinite(h) or h <= 0 for h in steps):
        raise ValueError('Require at least three positive finite derivative steps')
    x, y = np.broadcast_arrays(np.asarray(x, float), np.asarray(y, float))
    inside = np.asarray(inside, bool)
    if inside.shape != x.shape or not np.isfinite(x).all() or not np.isfinite(y).all():
        raise ValueError('Finite coordinates and matching interior mask required')
    def checked(a, b):
        result = np.asarray(field(a, b), float)
        if result.shape != (*x.shape, 2) or not np.isfinite(result).all():
            raise ValueError('Map must return finite two-component displacements')
        return result
    v = checked(x, y)
    values = []
    for h in steps:
        dx = (checked(x+h, y)-checked(x-h, y))/(2*h)
        dy = (checked(x, y+h)-checked(x, y-h))/(2*h)
        curl, magnitude, k = compatibility_terms(v, dx, dy)
        determinant = (1+dx[..., 0])*(1+dy[..., 1])-dy[..., 0]*dx[..., 1]
        values.append((curl, k-magnitude*curl, determinant))
    curl, q, determinant = values[-1]
    dc = np.maximum.reduce([abs(a[0]-b[0]) for a, b in zip(values, values[1:])])
    dq = np.maximum.reduce([abs(a[1]-b[1]) for a, b in zip(values, values[1:])])
    reliable = inside & (abs(curl) > np.maximum(1e-7, 100*dc)) & (abs(q) > np.maximum(1e-8, 100*dq))
    violation = reliable & (q*curl < 0)
    if not inside.any():
        raise ValueError('No interior samples')
    return dict(sampled_inside=int(inside.sum()),
                reliable_nonzero_curl=int(reliable.sum()),
                necessary_branch_violation=int(violation.sum()),
                necessary_branch_admitted=int((reliable & (q*curl > 0)).sum()),
                unresolved=int((inside & ~reliable).sum()),
                violation_fraction_of_all_inside=float(violation.sum()/inside.sum()),
                negative_map_determinant_fraction=float(np.mean(determinant[inside] < 0)),
                max_curl_derivative_step_difference=float(dc[inside].max()),
                max_Q_derivative_step_difference=float(dq[inside].max()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['inputs', 'recipe', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    from extended_fit import Study
    p = json.loads(args.recipe.read_text())['parameters']
    rows = []
    for folder in sorted(args.inputs.iterdir()):
        if not (folder/'case.json').is_file():
            continue
        study = Study(folder)
        hx, hy = study.half
        x, y = np.meshgrid(np.linspace(-hx+.003, hx-.003, 181),
                           np.linspace(-hy+.003, hy-.003, 121))
        *_, (sd, _, _) = study.shape(x, y, p)

        def field(x, y):
            X, Y, _, _ = study.geometry_field(x, y, p)
            return np.stack([X-x, Y-y], -1)

        rows.append(dict(observation=folder.name.upper(),
                         case_sha256=hashlib.sha256((folder/'case.json').read_bytes()).hexdigest(),
                         **sample_map(field, x, y, sd < -.002)))
    if not rows:
        raise ValueError('No frozen Study cases')
    report = dict(recipe_sha256=hashlib.sha256(args.recipe.read_bytes()).hexdigest(),
                  grid=[181, 121], steps=[.0002, .0001, .00005], observations=rows,
                  criterion='For reliable nonzero curl c, rho=K/c must exceed M; equivalently (K-Mc)*c>0. Necessary for every n>1 on the strictly transmitted branch.',
                  limits='Sampled unshifted map only. Finite-step stability is not a rigorous derivative error bound. Admitted means one necessary condition, not a recovered solid. Unresolved is not impossible. Folds are not total internal reflection. Other cameras/front surfaces and approximate image agreement are outside this test.')
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(json.dumps(rows), flush=True)


if __name__ == '__main__':
    main()
