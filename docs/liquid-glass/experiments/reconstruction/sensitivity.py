"""Fixed-fit sensitivity to source reconstruction and local map diagnostics."""
import argparse,json
from pathlib import Path
import cv2,numpy as np
from reproduce import CASES,render,scores,points,masks
from render import lookup,case_geometry

ap=argparse.ArgumentParser();ap.add_argument('fits',type=Path);args=ap.parse_args()
results={}
for name,case in CASES.items():
    report=json.loads((args.fits/f'{name}-report.json').read_text())
    candidates=[m for m in ['field','cap','bounded','spline'] if m in report['models']]
    winner=min(candidates,key=lambda m:report['models'][m]['fit_loss']) if candidates else 'default';fit=report['models'][winner]
    source=cv2.imread(str(args.fits/f'{name}-source.png'))[:,:,::-1].astype(np.float32)/255
    x0,y0,x1,y1=case['crop'];target=cv2.imread(str(args.fits/f'{name}-target.png'))[y0:y1,x0:x1,::-1].astype(np.float32)/255
    trials={}
    for dx,dy in [(0,0),(-1,0),(1,0),(0,-1),(0,1)]:
        shifted=cv2.warpAffine(source,np.float32([[1,0,dx],[0,1,dy]]),(1920,1080),borderMode=cv2.BORDER_REPLICATE)
        trials[f'{dx},{dy}']=scores(render(shifted,case,fit).astype(np.float32),target,case)['all_glass']['rmse']
    xs,ys,x,y=points(case,2);sd,train,held,valid=masks(case,xs,ys,x,y)
    x=x[valid].astype(float);y=y[valid].astype(float);h=.001
    def M(a,b):return np.array(lookup(a,b,case['half'],case['radius'],fit['parameters'],'field' if winner in ('bounded','default') else winner,case_geometry(case,a,b),case.get('exponent',0)))
    dX=(M(x+h,y)-M(x-h,y))/(2*h);dY=(M(x,y+h)-M(x,y-h))/(2*h)
    det=dX[0]*dY[1]-dX[1]*dY[0];displacement=np.hypot(*(M(x,y)-[x,y]))
    results[name]={'model':winner,'source_shift_rmse_255':trials,'sampled_min_determinant':float(det.min()),'sampled_negative_determinant_fraction':float((det<0).mean()),'sampled_max_displacement_px':float(displacement.max()),'sample_count':len(x),'note':'Finite samples are not a global invertibility proof; ±1px source shifts are sensitivity probes, not confidence intervals. Source PNG quantization changes this baseline slightly.'}
dest=Path(__file__).parent/'sensitivity.json';dest.write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2))
