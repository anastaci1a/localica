"""Report observed-source support for a frozen map, without changing its score.

An identity ramp recovers the exact renderer's sampled coordinates. Gaussian
support is reported to3sigma; the mathematical Gaussian has infinite support.
"""
from pathlib import Path
import argparse,json
import cv2,numpy as np
from extended_fit import Study
from source_support import coordinates,masks,kernel_safe,fraction


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);args=ap.parse_args()
    study=Study(args.folder);p=json.loads((args.folder/'accepted.json').read_text())['parameters']
    X,Y=coordinates(study,p);known,observed=masks(study)
    safe,radius=kernel_safe(known,study,p);observed_safe,_=kernel_safe(observed,study,p)
    dxdy,dxdx=np.gradient(X);dydy,dydx=np.gradient(Y)
    determinant=dxdx*dydy-dxdy*dydx;mask=study.valid
    report=dict(
        glass_pixels=int(mask.sum()),
        source_defined_fraction=fraction(known,X,Y,mask),
        gaussian_3sigma_defined_fraction=fraction(safe,X,Y,mask),
        source_observed_fraction=fraction(observed,X,Y,mask),
        gaussian_3sigma_observed_fraction=fraction(observed_safe,X,Y,mask),
        source_gaussian_radius_native_px=radius,
        source_coordinate_range=[[float(X[mask].min()),float(Y[mask].min())],[float(X[mask].max()),float(Y[mask].max())]],
        inverted_local_mapping_fraction=float((determinant[mask]<0).mean()),
        background_estimate=study.case.get('background_estimate'),
        limits='Native-grid finite differences. Defined source may include explicitly declared estimates; observed support excludes those pixels. Negative Jacobian does not establish a physical caustic.3sigma omits Gaussian tails; output scattering is not propagated.')
    (args.folder/'diagnostics.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':main()
