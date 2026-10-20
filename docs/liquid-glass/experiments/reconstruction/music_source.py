"""Recover scrolling Music content using masked feature-track pose constraints.

Private original-video processing. Backgrounds and confidence stay outside Git.
"""
from pathlib import Path
import argparse, json, hashlib
import cv2, numpy as np
from scipy.optimize import least_squares
from PIL import Image, ImageDraw

parser=argparse.ArgumentParser()
parser.add_argument('video',type=Path)
parser.add_argument('out',type=Path)
parser.add_argument('--seconds',nargs='+',type=float,default=[0.,2.5,4.5,6.])
args=parser.parse_args()
out=args.out
out.mkdir(parents=True,exist_ok=True)
manifest=json.loads((Path(__file__).parent.parent/'context-sources.json').read_text())
expected=next(s['sha256'] for s in manifest['sources'] if s['id']=='music')
assert hashlib.sha256(args.video.read_bytes()).hexdigest()==expected,'Unexpected Music video encoding'
cap = cv2.VideoCapture(str(args.video))
assert (int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)))==(1920,1080)
frames, times = [], []
for index in range(0, int(cap.get(cv2.CAP_PROP_FRAME_COUNT)), 3):
    cap.set(cv2.CAP_PROP_POS_FRAMES, index)
    ok, frame = cap.read()
    if not ok: break
    frames.append(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    times.append(cap.get(cv2.CAP_PROP_POS_MSEC)/1000)
cap.release()
mask = np.zeros(frames[0].shape[:2], np.uint8)
mask[:310, 290:1700] = 255
sift = cv2.SIFT_create(nfeatures=2500)
features = [sift.detectAndCompute(cv2.cvtColor(f,cv2.COLOR_RGB2GRAY),mask) for f in frames]
edges = []
matcher = cv2.BFMatcher()
for i in range(len(frames)):
    for j in range(i+1,len(frames)):
        if j-i not in [1,2,4,8,16,32] and i not in [0,len(frames)-1]:continue
        ka, da = features[i]; kb, db = features[j]
        if da is None or db is None:continue
        pairs = [m for m,n in matcher.knnMatch(da,db,k=2) if m.distance < .65*n.distance]
        if len(pairs)<8: continue
        delta = np.array([np.array(ka[m.queryIdx].pt)-kb[m.trainIdx].pt for m in pairs])
        # Content scroll is vertical. Reject phone-edge/motion changes rather
        # than manufacturing source alignment through a general deformation.
        median = np.median(delta,axis=0)
        inside = np.linalg.norm(delta-median,axis=1)<.9
        if inside.sum()<8:continue
        delta = np.median(delta[inside],axis=0)
        if abs(delta[0])>.8:continue
        edges.append((i,j,*delta,int(inside.sum())))

initial = np.zeros((len(frames),2))
for i in range(1,len(frames)):
    e = next((e for e in edges if e[0]==i-1 and e[1]==i),None)
    if e: initial[i]=initial[i-1]+e[2:4]
    else: initial[i]=initial[i-1]
def residual(v):
    pose = np.vstack([np.zeros((1,2)),v.reshape(-1,2)])
    return np.concatenate([(pose[j]-pose[i]-[dx,dy])*np.sqrt(min(n,80)) for i,j,dx,dy,n in edges])
fit = least_squares(residual,initial[1:].ravel(),loss='soft_l1',f_scale=.5,max_nfev=100)
pose = np.vstack([np.zeros((1,2)),fit.x.reshape(-1,2)])
for _ in range(3):
    edges=[e for e in edges if np.linalg.norm(pose[e[1]]-pose[e[0]]-e[2:4])<2]
    fit=least_squares(residual,pose[1:].ravel(),loss='soft_l1',f_scale=.5,max_nfev=100)
    pose=np.vstack([np.zeros((1,2)),fit.x.reshape(-1,2)])
print('Frames/edges',len(frames),len(edges),'pose range',pose.min(0),pose.max(0),flush=True)
print('pose samples',[(round(times[i],2),pose[i].tolist()) for i in range(0,len(frames),10)],flush=True)

# Independent overlapping-content residual; useful for detecting source drift.
diagnostic=[]
for i,j,dx,dy,n in edges:
    diagnostic.append(float(np.linalg.norm(pose[j]-pose[i]-[dx,dy])))
report = dict(method='Masked SIFT translation pose graph; feature tracks use top310px only, x290..1700. Mosaic uses disjoint uncovered top strips (310px or570px in compact state) and the lower780..835px strip. Lens-covered pixels are excluded.',
    video_sha256=hashlib.sha256((args.video).read_bytes()).hexdigest(),
    timestamps=times,poses=pose.tolist(),edges=edges,median_edge_residual_px=float(np.median(diagnostic)),max_edge_residual_px=float(max(diagnostic)))

for seconds in args.seconds:
    ti=int(np.argmin(np.abs(np.array(times)-seconds)))
    target=frames[ti]
    ys,xs=np.mgrid[:1080,:1920].astype(np.float32)
    total=np.zeros_like(target,dtype=np.float64);weight=np.zeros(target.shape[:2],np.float64)
    # Prefer the center of the trusted source strip. No extrapolated samples.
    for i,f in enumerate(frames):
        X=xs+pose[ti,0]-pose[i,0];Y=ys+pose[ti,1]-pose[i,1]
        limit=570 if 1.65<=times[i]<=4.75 else 310
        valid=(X>=290)&(X<1700)&(Y>=0)&(Y<limit)
        w=valid.astype(float)*np.maximum(0,1-np.abs(Y-limit/2)/(limit/2+10))
        bottom=(X>=400)&(X<1600)&(Y>780)&(Y<835)
        w+=bottom.astype(float)*np.maximum(0,1-np.abs(Y-807.5)/30)
        sample=cv2.remap(f.astype(np.float32),(X).astype(np.float32),(Y).astype(np.float32),cv2.INTER_LINEAR)
        total+=sample*w[...,None];weight+=w
    source=total/np.maximum(weight,1e-9)[...,None]
    known=weight>0
    # Unobserved pixels are visibly neutral in the diagnostic, never silently
    # counted as recovered. Later shape-specific crops constrain valid scoring.
    source[~known]=[255,0,255]
    Image.fromarray(np.uint8(np.rint(np.clip(source,0,255)))).save(out/f'source-{seconds:.1f}.png')
    Image.fromarray(target).save(out/f'target-{seconds:.1f}.png')
    Image.fromarray(np.uint8(known)*255).save(out/f'known-{seconds:.1f}.png')
    pair=Image.new('RGB',(1920,600),'#151821');pair.paste(Image.fromarray(target).resize((960,540)),(0,30));pair.paste(Image.fromarray(np.uint8(np.rint(np.clip(source,0,255)))).resize((960,540)),(960,30))
    d=ImageDraw.Draw(pair);d.text((10,8),'Apple',fill='white');d.text((970,8),'Recovered background; magenta = unknown',fill='white')
    pair.save(out/f'comparison-{seconds:.1f}.png')
(out/'recovery.json').write_text(json.dumps(report,indent=2)+'\n')
