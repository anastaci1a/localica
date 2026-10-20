"""Refine a clean vector family on two frozen backgrounds; glass is unchanged.

python vector_refine.py /private/study/paired --pass-number 23
Use --evaluate dx dy note_scale pose_dx pose_dy pose_scale to replay a fit.
All geometry remains solid SVG. Parameters are in the SVG viewBox or the
reference pose; the recorded normalized form separates both coordinate spaces.
"""
from pathlib import Path
import argparse,copy,hashlib,json,time
import numpy as np
from extended_fit import Study
from paired_fit import permitted
from vector_foreground import fit_geometry
from vector_shapes import library_variant


def pose(layer,v):
    left,top,right,bottom=layer['vector_box']
    cx=(left+right)/2+v[3];cy=(top+bottom)/2+v[4]
    w=(right-left)*v[5];h=(bottom-top)*v[5]
    return [cx-w/2,cy-h/2,cx+w/2,cy+h/2]


def foreground_errors(studies,p,co):
    result=[]
    for s in studies:
        composite=s.apply_foreground(s.render(p,np.asarray(co)));x0,y0,_,_=s.crop
        for layer in s.case['foreground_layers']:
            if 'vector_asset' in layer and layer['mode']!='transmission' and layer.get('fit_style',True):
                a,b,c,d=layer['box']
                result.append(float(np.mean((composite[b-y0:d-y0,a-x0:c-x0]-s.target[b:d,a:c])**2))*255**2)
    return result


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path)
    ap.add_argument('--pass-number',type=int,required=True)
    ap.add_argument('--evaluate',type=float,nargs=6)
    args=ap.parse_args();r=args.folder;out=r/f'pass-{args.pass_number:02}.json'
    assert not out.exists(),'Do not overwrite a recorded pass'
    began=time.time();old=json.loads((r/'accepted.json').read_text());current=copy.deepcopy(old)
    studies=[Study(r/name) for name in ['a','b']]
    for s in studies:s.case['foreground_styles']=old['foreground_styles']
    p=old['parameters'];co=old['material'];before=foreground_errors(studies,p,co)
    assert np.max(abs(np.array(before)-old['foreground_style_scores']['after_mse']))<1e-3
    def variant(v,layer):return library_variant(*v[:3]),pose(layer,v)
    bounds=[(-8,8),(-8,8),(.8,1.2),(-1.5,1.5),(-1.5,1.5),(.985,1.015)]
    if args.evaluate is None:
        v,fit=fit_geometry(studies,p,co,'library.svg',variant,[0,0,1,0,0,1],bounds)
    else:
        v=np.asarray(args.evaluate);assert all(a<=x<=b for x,(a,b) in zip(v,bounds))
        fit=dict(evaluations=0,mode='Replay recorded clean-family parameters, no optimizer')
    raw=library_variant(*v[:3]);sha=hashlib.sha256(raw).hexdigest();changes=[]
    for s in studies:
        layer=next(l for l in s.case['foreground_layers'] if l.get('style_key',l.get('vector_asset'))=='library.svg')
        previous=copy.deepcopy(layer);layer.update(vector_asset='library-refined.svg',style_key='library.svg',vector_sha256=sha,vector_box=pose(layer,v))
        layer['vector_provenance']=dict(method='Clean symmetric frame trace; translate/uniformly scale the whole smooth note cutout, not individual screenshot pixels.',generator='vector_shapes.library_variant',
            svg_viewbox=[100,132],note_transform=dict(dx=float(v[0]),dy=float(v[1]),scale=float(v[2]),center=[46.45,86.35]),
            pose_delta_over_L=[float(v[3]/s.L),float(v[4]/s.L)],pose_scale=float(v[5]))
        (s.folder/'library-refined.svg').write_bytes(raw)
        changes.append(dict(previous=previous,current=copy.deepcopy(layer)))
    # Reload asset coverage and actual compositing before accepting anything.
    candidates=[]
    for s in studies:
        (s.folder/'case.json').write_text(json.dumps(s.case,indent=2)+'\n');candidates.append(Study(s.folder))
    after=foreground_errors(candidates,p,co)
    metrics=[s.scores(s.render(p,np.asarray(co))) for s in candidates]
    allowed=permitted(metrics,old['scores']) and bool(np.all(np.asarray(after)<=np.asarray(before)+1e-5))
    if allowed:
        current['scores']=metrics
        current['foreground_style_scores']=dict(old['foreground_style_scores'],before_mse=before,after_mse=after,
            optimizer_evaluations=fit['evaluations'],formula='Unchanged shared foreground responses; one common clean Library geometry family.')
    else:
        for s,change in zip(studies,changes):
            layer=next(l for l in s.case['foreground_layers'] if l.get('style_key',l.get('vector_asset'))=='library.svg')
            layer.clear();layer.update(change['previous']);(s.folder/'case.json').write_text(json.dumps(s.case,indent=2)+'\n')
    record=dict(pass_number=args.pass_number,experiment='Shared clean Library note geometry',accepted=allowed,previous=old,current=current,
        trials=[dict(parameters=v.tolist(),**fit,before_mse=before,after_mse=after,scores=metrics)],foreground_case_changes=changes,
        seconds=time.time()-began,gate='Both frozen glass losses/RMSE and every foreground role on both backgrounds retain or improve.',
        limits='A low-dimensional clean trace refinement; not recovered original Apple vector geometry or proof of universal optical behavior.')
    out.write_text(json.dumps(record,indent=2)+'\n');(r/'accepted.json').write_text(json.dumps(current,indent=2)+'\n')
    print(json.dumps(dict(accepted=allowed,parameters=v.tolist(),before_mse=before,after_mse=after)),flush=True)


if __name__=='__main__':main()
