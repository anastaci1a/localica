"""Replay a shared neutral normal-dependent rim response; no fitting.
python neutral_rim.py INPUTS BASE_RECORD RIM_RECORD NEW_OUTPUT
Use a single process/thread: the offline adapter temporarily extends settled's
light vector, restores it on exit, and never changes source sampling or geometry.
The method-independent response is documented in recreation-models.md.
"""
from pathlib import Path
import argparse,json,hashlib
import cv2,numpy as np
import settled
from extended_fit import Study
from vector_revision import composite


def response(sd,nx,ny,width):
    """Normal-dependent response in a declared normalized boundary coordinate."""
    assert width>0
    d=np.maximum(-sd,0)
    return np.exp(-(d/width)**2)*(.5*(nx+ny)**2)


def field(x,y,p,width):
    return response(*settled.case_geometry(p['shape'],x,y),width)


def render(source,case,p,amplitude,width,samples=2,audit=None):
    assert np.isfinite(amplitude) and amplitude>=0 and np.isfinite(width) and width>0
    if amplitude==0:return settled.render(source,case,p,samples=samples)
    old_light,old_shade=settled.lighting,settled.shade
    def lighting(x,y,p,boundary=None):
        old=old_light(x,y,p,boundary)
        return np.concatenate([old,field(x,y,p,width)[...,None]],axis=-1)
    def shade(sample,light,co):
        raw=old_shade(sample,light[...,:-1],co)+amplitude*light[...,-1,None]
        if audit is not None:
            audit['minimum']=min(audit.get('minimum',float('inf')),float(raw.min()))
            audit['maximum']=max(audit.get('maximum',float('-inf')),float(raw.max()))
            audit['clipped_channels']=audit.get('clipped_channels',0)+int(np.count_nonzero((raw<0)|(raw>1)))
        return np.clip(raw,0,1)
    settled.lighting,settled.shade=lighting,shade
    try:return settled.render(source,case,p,samples=samples)
    finally:settled.lighting,settled.shade=old_light,old_shade


def render_study(study,p,co,amplitude,width,scale=1,audit=None):
    """Same response with Study's existing crowned/implicit footprint and map."""
    assert np.isfinite(amplitude) and amplitude>=0 and np.isfinite(width) and width>0
    if amplitude==0:return study.render(p,co,scale)
    from render import shade
    if 'material_chroma_sensitivity' in p or 'ambient_rim_response' in p:
        from material_context import effective
        co=effective(study,p,co)
    x0,y0,x1,y1=study.crop
    result=np.empty(((y1-y0)*scale,(x1-x0)*scale,3),np.float32)
    for row,bottom,W,x,y in study.raster_tiles(scale):
        C,J=study.field(x,y,p);C=shade(C,J,co)
        h=response(*study.shape(x,y,p)[-1],width)
        raw=C+amplitude*h[...,None]
        if audit is not None:
            audit['minimum']=min(audit.get('minimum',float('inf')),float(raw.min()))
            audit['maximum']=max(audit.get('maximum',float('-inf')),float(raw.max()))
            audit['clipped_channels']=audit.get('clipped_channels',0)+int(np.count_nonzero((raw<0)|(raw>1)))
        result[row:bottom]=np.clip(raw,0,1).reshape(bottom-row,2,W,2,3).mean((1,3))
    sigma=study.kernel_sigma(p,'post_sigma')*scale
    if sigma>.001:result=cv2.GaussianBlur(result,(0,0),sigma)
    from grazing_reflection import apply
    return apply(study,p,result,scale)


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    for n in ['inputs','base_record','rim_record','output']:ap.add_argument(n,type=Path)
    a=ap.parse_args();assert not a.output.exists();a.output.mkdir(parents=True);cv2.setNumThreads(1)
    s=Study(a.inputs);b=json.loads(a.base_record.read_text());r=json.loads(a.rim_record.read_text())
    assert hashlib.sha256(json.dumps(s.case,sort_keys=True).encode()).hexdigest()==next(iter(b['case_sha256'].values()))
    for name,digest in s.case['source_sha256'].items():assert hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()==digest
    for glyphs in b['vector_sha256'].values():
        for name,digest in glyphs.items():assert hashlib.sha256((a.inputs/name).read_bytes()).hexdigest()==digest
    if 'preset' in b:
        p=b['preset'];M=render(s.source,s.case,p,r['amplitude'],r['width'],samples=b['samples'])
    else:
        p=b['parameters'];M=render_study(s,p,np.asarray(b['material']),r['amplitude'],r['width'])
    C=composite(s,p,M)
    np.savez_compressed(a.output/'arrays.npz',material=M,complete=C)
    q=s.scores(M);q['E']=float(np.sqrt(q['loss']));(a.output/'scores.json').write_text(json.dumps(q,indent=2)+'\n')
