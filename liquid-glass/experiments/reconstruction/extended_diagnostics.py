"""Report observed-source support for a frozen map, without changing its score.

An identity ramp recovers the exact renderer's sampled coordinates. Gaussian
support is reported to3sigma; the mathematical Gaussian has infinite support.
"""
from pathlib import Path
import argparse,json
import cv2,numpy as np
from extended_fit import Study


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);args=ap.parse_args()
    study=Study(args.folder);p=json.loads((args.folder/'accepted.json').read_text())['parameters']
    H,W=study.source.shape[:2];Y,X=np.mgrid[:H,:W]
    study.source=np.stack([(X+1)/(W+2),(Y+1)/(H+2),np.ones_like(X)],-1).astype(np.float32)
    study.cache.clear()
    q={**p,'sigma':0.,'gamma':1.}
    color,_=study.field(study.x,study.y,q)
    X=(color[...,0]*(W+2)-1).astype(np.float32)
    Y=(color[...,1]*(H+2)-1).astype(np.float32)
    known=cv2.imread(str(args.folder/'known.png'),cv2.IMREAD_GRAYSCALE).astype(np.float32)/255
    support=cv2.remap(known,X,Y,cv2.INTER_LINEAR)
    radius=int(np.ceil(3*round(p['sigma']*4)/4))
    safe=cv2.erode(known,np.ones((2*radius+1,2*radius+1),np.uint8)) if radius else known
    safe=cv2.remap(safe,X,Y,cv2.INTER_LINEAR)
    dxdy,dxdx=np.gradient(X);dydy,dydx=np.gradient(Y)
    determinant=dxdx*dydy-dxdy*dydx;mask=study.valid
    report=dict(
        glass_pixels=int(mask.sum()),
        source_observed_fraction=float((support[mask]>.999).mean()),
        gaussian_3sigma_observed_fraction=float((safe[mask]>.999).mean()),
        source_gaussian_radius_native_px=radius,
        source_coordinate_range=[[float(X[mask].min()),float(Y[mask].min())],[float(X[mask].max()),float(Y[mask].max())]],
        inverted_local_mapping_fraction=float((determinant[mask]<0).mean()),
        limits='Native-grid finite-difference diagnostics. A negative lookup Jacobian indicates local orientation reversal; a fold is a singular locus. Neither establishes a physical caustic. Source-known pixels may retain video/recovery error;3sigma omits a Gaussian tail and output scattering is not propagated.')
    (args.folder/'diagnostics.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':main()
