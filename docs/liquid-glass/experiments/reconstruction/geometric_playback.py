"""Unblurred geometric playback diagnostic; no fitting.
python geometric_playback.py INPUTS RECORD HISTORICAL_M SURFACE_JSON OUTPUT
Historical M is reused only to freeze the existing calculated icon color.
"""
import argparse,hashlib,json
from pathlib import Path
import cv2,numpy as np
from PIL import Image
from geometric_optics import CircularBevel,trace_flat_front_orthographic
from reproduce import CASES,scores
from settled import icon_color


def render_rows(source,case,p,surface,index,distance,start,stop):
    """4x4 finite pixel integration with coupled lens/background coverage."""
    x0,y0,x1,y1=case['crop'];L=min(case['half'])
    yy,xx=np.mgrid[y0+start:y0+stop:1/4,x0:x1:1/4]
    x=(xx-.375-case['center'][0])/L;y=(yy-.375-case['center'][1])/L
    q=trace_flat_front_orthographic(surface,x,y,index,distance)
    if not q['complete'].all():raise ValueError('Unresolved primary path; no pixel fallback')
    X=q['X']*L+case['center'][0];Y=q['Y']*L+case['center'][1]
    if X.min()<0 or Y.min()<0 or X.max()>=source.shape[1]-1 or Y.max()>=source.shape[0]-1:raise ValueError('Undefined bilinear source footprint')
    C=cv2.remap(source,X.astype(np.float32),Y.astype(np.float32),cv2.INTER_LINEAR)
    fixed=C*np.asarray(p['material'][:3])+np.asarray(p['material'][3:6])
    C=np.where(q['inside'][...,None],fixed,C)
    return C.reshape(stop-start,4,x1-x0,4,3).mean((1,3)).astype(np.float32),[float(X.min()),float(Y.min()),float(X.max()),float(Y.max())]


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for n in ['inputs','record','historical_material','surface','output']:ap.add_argument(n,type=Path)
    a=ap.parse_args();cv2.setNumThreads(1)
    if a.output.exists():raise ValueError('Preserve existing output')
    record=json.loads(a.record.read_text());p=record['recipe'];spec=json.loads(a.surface.read_text());s=CircularBevel(**spec['surface']);case=CASES['media'];x0,y0,x1,y1=case['crop'];L=min(case['half'])
    for name,digest in record['input_sha256'].items():
        if hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()!=digest:raise ValueError('Input changed: '+name)
    source=np.asarray(Image.open(a.inputs/'media-source.png'),float)/255;target=np.asarray(Image.open(a.inputs/'media-target.png'),np.float32)/255
    old=np.load(a.historical_material);C=np.empty_like(old);bounds=[]
    for row in range(0,y1-y0,24):C[row:min(row+24,y1-y0)],b=render_rows(source,case,p,s,spec['index'],spec['background_distance'],row,min(row+24,y1-y0));bounds.append(b)
    oldicon=icon_color(old,p['icon'],L);ia,ib,ic,id=[int(round(v*L+case['center'][i%2])) for i,v in enumerate(p['icon']['box_over_L'])]
    mask=np.asarray(Image.open(Path(__file__).parent/p['icon']['coverage']),float)/255;sl=np.s_[ib-y0:id-y0,ia-x0:ic-x0];C[sl]=C[sl]*(1-mask[...,None])+oldicon[sl]*mask[...,None]
    a.output.mkdir(parents=True);np.save(a.output/'complete.npy',C);Image.fromarray(np.uint8(np.rint(np.clip(C,0,1)*255))).save(a.output/'render.png')
    report=dict(surface=spec,source_query_bounds_by_tile=bounds,visible_composite_scores=scores(C,target[y0:y1,x0:x1],case),optical_blur=0,pixel_samples_per_axis=4,frozen_foreground='Historical calculated color/coverage, including its old icon-only filtering; not a new unified icon model',material='Frozen historical constant RGB gain/bias only; no old spatial light field',scope='Restricted primary transmission, not full Fresnel radiance, finite camera, or performance proof')
    (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
