"""Measure the existing slider's upper rim; no new reference or generated image."""
from pathlib import Path
import argparse,json
import numpy as np
from scipy.optimize import least_squares
from reproduce import frame
from shapes import oval_minimum_radius


def main():
    ap=argparse.ArgumentParser();ap.add_argument('sources',type=Path);args=ap.parse_args()
    image,meta=frame(args.sources,'284',1092);luma=image.mean(2)
    xs=np.arange(660,821,2);observed=[]
    for x in xs:
        line=luma[482:534,x];k=np.argmin(line)
        assert 0<k<len(line)-1
        delta=.5*(line[k-1]-line[k+1])/(line[k-1]-2*line[k]+line[k+1])
        observed.append(482+k+np.clip(delta,-.5,.5))
    observed=np.asarray(observed);u=(xs-763.5)/118.5
    def curve(v):return 548.5+v[2]-56.5*np.sqrt(1-u*u)*(1+v[0]*u*u+v[1]*u**4)
    train=np.arange(len(xs))%3!=0
    f=least_squares(lambda v:(curve(v)-observed)[train],[.45,-.2,0],bounds=([0,-.45,-1],[.5,0,1]),loss='soft_l1',f_scale=.4)
    error=curve(f.x)-observed
    old=548.5-56.5*(1-np.abs(u)**2.5)**.4-observed
    coeff=f.x[:2].tolist();radius=oval_minimum_radius([118.5,56.5],coeff)
    result={'frame':meta,'method':'Subpixel parabolic minimum of mean RGB on native upper-rim columns; robust fit on 2/3 columns. The ridge is a silhouette proxy, not proven physical boundary. Bottom/right symmetry remains assumed.',
      'sample_x':xs.tolist(),'ridge_y':observed.tolist(),'train':train.tolist(),
      'geometry':{'center':[763.5,float(548.5+f.x[2])],'half':[118.5,56.5],'oval':coeff,'radius':float(np.floor(radius*100)/100)},
      'sampled_minimum_radius':radius,
      'rmse_px':{key:float(np.sqrt(np.mean(error[mask]**2))) for key,mask in [('train',train),('held_columns',~train),('all',np.ones_like(train))]},
      'old_n2_5_rmse_px':float(np.sqrt(np.mean(old*old)))}
    dest=Path(__file__).parent/'contour.json';dest.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k not in ('sample_x','ridge_y','train')},indent=2))


if __name__=='__main__':main()
