"""Replay a proposed revision against frozen Study inputs, without fitting.

python revision_replay.py INPUTS PREVIOUS.json CANDIDATE.json OUTPUT --scale 4
INPUTS contains a/, b/ (case.json and its hashed private input files).
No Apple exterior/shadow or photographic glyph is reused in comparisons.
Human visual acceptance is deliberately separate from this command.
"""
from pathlib import Path
import argparse, hashlib, json
import cv2
import numpy as np
from PIL import Image
from extended_fit import Study
from progress_gate import evaluate
from publish import pair
from source_support import coverage_report, supported, masks, kernel_safe, confidence_at
from chromatic_transmission import rays, enabled, weight


def clean_composite(study, p, material, scale=1):
    """Glass over the recovered source and a generated normalized shadow."""
    a,b,c,d=study.crop;h,w=(d-b)*scale,(c-a)*scale
    yy,xx=np.mgrid[:h,:w].astype(np.float32)
    X=a-.5+(xx+.5)/scale;Y=b-.5+(yy+.5)/scale
    exterior=study.source if study.exterior_source is None else study.exterior_source
    background=cv2.remap(exterior,X,Y,cv2.INTER_LINEAR)
    if 'shadow' in p:
        q=p['shadow'];x=(X-study.case['center'][0])/study.L;y=(Y-study.case['center'][1])/study.L
        *_,(sd,_,_)=study.shape(x-q['offset'][0],y-q['offset'][1],p)
        background*=1-q['opacity']*np.exp(-.5*(np.maximum(sd,0)/q['sigma'])**2)[...,None]
    alpha=study.shape_coverage(p,scale)
    # Reject screenshot-derived foregrounds rather than silently copying them.
    assert not study.foreground_layers and not study.transmission_layers, 'Add explicit vector-only foreground support before using this replay for glyph studies'
    return material*alpha[...,None]+background*(1-alpha[...,None])


def subpixel_support(study,p,scale):
    """Check every emitted in-footprint transmission ray, including RGB splits.

    Source Gaussian is protected by its three-sigma footprint. Reflection and
    material-context support remain separately reported by coverage_report.
    This is discrete emitted-ray coverage, not a continuous surface proof.
    """
    defined,_=masks(study);safe,radius=kernel_safe(defined,study,p)
    minimum=1.;count=0
    for _,_,_,x,y in study.raster_tiles(scale):
        *_,(sd,_,_)=study.shape(x,y,p);active=sd<=0
        X,Y,_,_=study.geometry_field(x,y,p)
        mappings=[(X,Y,active)]
        if enabled(p):
            mappings.extend((X,Y,active&(weight(study,p,x)>1e-8)) for _,X,Y,_ in rays(study,p,x,y))
        for X,Y,mask in mappings:
            if not mask.any():continue
            values=confidence_at(safe,X*study.L+study.case['center'][0],Y*study.L+study.case['center'][1])[mask]
            minimum=min(minimum,float(values.min()));count+=int(mask.sum())
    return dict(scale=scale,emitted_transmission_rays=count,minimum_defined_confidence=minimum,
                gaussian_radius_native_px=radius,passed=minimum>=.999)


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ['inputs','previous','candidate','out']:ap.add_argument(name,type=Path)
    ap.add_argument('--scale',type=int,default=4);args=ap.parse_args()
    assert 1<=args.scale<=4
    cv2.setNumThreads(1);args.out.mkdir(parents=True,exist_ok=True)
    models=[json.loads(path.read_text()) for path in [args.previous,args.candidate]]
    studies=[Study(path) for path in sorted(args.inputs.iterdir()) if (path/'case.json').is_file()]
    assert studies,'No frozen Study cases found'
    protocol=dict(cases=[s.case for s in studies],metric='E²=.7glassRMSE²+.3rimRMSE²; original native2x2 glass/rim masks. New exterior0<originalsd<.35L, complete clean source composite.')
    epoch=hashlib.sha256(json.dumps(protocol,sort_keys=True).encode()).hexdigest()
    states=[dict(epoch=epoch,observations={}) for _ in models];supports=[]
    for s in studies:
        label=s.folder.name.upper();ext=(s.sd>0)&(s.sd<.35)
        for state,model in zip(states,models):
            p=model['parameters'];pred=s.render(p,np.asarray(model['material']))
            whole=clean_composite(s,p,pred);score=s.scores(pred)
            score['shadow']=dict(rmse=float(np.sqrt(np.mean(((whole-s.target_crop)[ext]*255)**2))),pixels=int(ext.sum()))
            state['observations'][label]=score
        p=models[1]['parameters'];support=coverage_report(s,p)
        sub=subpixel_support(s,p,args.scale)
        supports.append(dict(observation=label,center=support,subpixels=sub,passed=supported(s,support) and sub['passed']))
        hi=s.render(p,np.asarray(models[1]['material']),args.scale)
        whole=clean_composite(s,p,hi,args.scale)
        rgb=np.uint8(np.rint(np.clip(whole,0,1)*255))
        Image.fromarray(rgb).save(args.out/f'{label.lower()}-render.png')
        original=cv2.resize(np.uint8(np.rint(s.target_crop*255)),(rgb.shape[1],rgb.shape[0]),interpolation=cv2.INTER_LANCZOS4)
        pair(original,rgb,('Apple source',f'Reconstruction ({args.scale}×)'),font_size=16*args.scale).save(args.out/f'{label.lower()}-comparison.png')
        print(label,states[1]['observations'][label]['loss'],sub,flush=True)
    report=dict(previous=states[0],candidate=states[1],protocol=protocol,support=supports,
                gate=evaluate(*states,visual_review=False,replay_verified=True,source_support_verified=all(s['passed'] for s in supports)),
                limits='Independent emitted-ray replay; human visual review is not inferred from a score. Estimated backgrounds and archived material approximations remain explicitly conditional.')
    (args.out/'verification.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':main()
