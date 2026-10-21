"""Clean glass/shadow compositing for studies with solid SVG foregrounds.

Unlike the historical context compositor, this never retains a reference shadow
or screenshot-derived foreground. Material/ray evaluation remains Study.render.
"""
import cv2,numpy as np


def composite(study,p,material,scale=1,background_source=None):
    assert not study.transmission_layers,'Nonlinear foreground layers need an explicit replay contract'
    assert all('vector_asset' in layer and layer['mode']!='artwork' for layer in study.case.get('foreground_layers',[])),'Only declared vector glyphs are supported'
    a,b,c,d=study.crop;H,W=material.shape[:2]
    assert (H,W)==((d-b)*scale,(c-a)*scale)
    yy,xx=np.mgrid[:H,:W].astype(np.float32);X=a-.5+(xx+.5)/scale;Y=b-.5+(yy+.5)/scale
    exterior=study.source if study.exterior_source is None else study.exterior_source
    if background_source is not None:
        assert background_source.shape==exterior.shape
        exterior=background_source
    background=cv2.remap(exterior,X,Y,cv2.INTER_LINEAR)
    if 'shadow' in p:
        q=p['shadow'];x=(X-study.case['center'][0])/study.L;y=(Y-study.case['center'][1])/study.L
        *_,(sd,_,_)=study.shape(x-q['offset'][0],y-q['offset'][1],p)
        background*=1-q['opacity']*np.exp(-.5*(np.maximum(sd,0)/q['sigma'])**2)[...,None]
    alpha=study.shape_coverage(p,scale)
    return study.apply_foreground(material*alpha[...,None]+background*(1-alpha[...,None]),scale)


def replay(folder,record,observation,output):
    """Replay frozen inputs; completion affects unscored exterior only."""
    import hashlib,json
    from pathlib import Path
    from PIL import Image
    from extended_fit import Study
    from source_support import masks
    from motion_support import complete
    from preview_size import dimensions
    from publish import pair
    study=Study(folder)
    fingerprint=hashlib.sha256(json.dumps(study.case,sort_keys=True).encode()).hexdigest()
    assert fingerprint==record['case_sha256'][observation], 'Frozen case mismatch'
    for name,digest in record['vector_sha256'][observation].items():
        assert hashlib.sha256((Path(folder)/name).read_bytes()).hexdigest()==digest
    p=record['parameters'];material=np.asarray(record['material'])
    x0,y0,x1,y1=study.crop
    w,h=dimensions(x1-x0,y1-y0,large=record.get('large',False))
    scale=2 if w>x1-x0 else 1
    M=study.render(p,material,scale)
    defined,_=masks(study)
    background=complete(study,defined)
    assert study.exterior_source is None,'Separate exterior input requires its own completion contract'
    rgb=np.uint8(np.rint(np.clip(composite(study,p,M,scale,background),0,1)*255))
    rgb=cv2.resize(rgb,(w,h),interpolation=cv2.INTER_AREA)
    target=cv2.resize(np.uint8(np.rint(study.target_crop*255)),(w,h),interpolation=cv2.INTER_LANCZOS4)
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    Image.fromarray(rgb).save(output/(observation+'-render.png'))
    pair(target,rgb,('Apple source','Reconstruction'),font_size=24).save(output/(observation+'-comparison.png'))
    return M


if __name__=='__main__':
    import argparse,json
    from pathlib import Path
    ap=argparse.ArgumentParser(description='Replay solid-vector studies from frozen private inputs.')
    ap.add_argument('inputs',type=Path);ap.add_argument('record',type=Path);ap.add_argument('output',type=Path)
    args=ap.parse_args();record=json.loads(args.record.read_text())
    for name in record['case_sha256']:
        replay(args.inputs/name,record,name,args.output)
