"""Report observed-source support for a frozen map, without changing its score.

An identity ramp recovers the exact renderer's sampled coordinates. Gaussian
support is reported to3sigma; the mathematical Gaussian has infinite support.
"""
from pathlib import Path
import argparse,json
import cv2,numpy as np
from extended_fit import Study
from source_support import coordinates,coverage_report


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);args=ap.parse_args()
    study=Study(args.folder);p=json.loads((args.folder/'accepted.json').read_text())['parameters']
    X,Y=coordinates(study,p)
    dxdy,dxdx=np.gradient(X);dydy,dydx=np.gradient(Y)
    determinant=dxdx*dydy-dxdy*dydx;mask=study.valid
    report=dict(
        glass_pixels=int(mask.sum()),
        **coverage_report(study,p,X,Y),
        source_coordinate_range=[[float(X[mask].min()),float(Y[mask].min())],[float(X[mask].max()),float(Y[mask].max())]],
        inverted_local_mapping_fraction=float((determinant[mask]<0).mean()),
        background_estimate=study.case.get('background_estimate'),
        limits='Native-grid finite differences. Defined source may include explicitly declared estimates; observed support excludes those pixels. Negative Jacobian does not establish a physical caustic.3sigma omits Gaussian tails; output scattering is not propagated.')
    (args.folder/'diagnostics.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':main()
