"""Refit fixed-map materials fairly, then record refinement/color diagnostics."""
from pathlib import Path
import argparse,json,copy
import cv2,numpy as np
from reproduce import CASES,prepare,training_samples,optical_inputs,model_light,render,scores
from render import case_geometry,fit_material,shade,lookup


def refit(source,target,case,f):
    xs,ys,x,y,indices,weights=training_samples(case)
    px=x.ravel()[indices,None];py=y.ravel()[indices,None]
    # 2x2 subpixel material fit; final rendering still integrates 4x4.
    px=px+np.array([-.25,.25,-.25,.25]);py=py+np.array([-.25,-.25,.25,.25])
    boundary=case_geometry(case,px,py)
    blur=cv2.GaussianBlur(source,(0,0),f['sigma']) if f['sigma'] else source
    # Match render_grid's baseline aliases before sampling.
    sampler=copy.deepcopy(f)
    if f['model'] in ('default','bounded'):sampler['model']='field'
    sample,light,_=optical_inputs(source,blur,case,sampler,px,py,boundary)
    expected=target[ys.flat[indices].astype(int),xs.flat[indices].astype(int)]
    co,_=fit_material(sample,light,expected,weights)
    f=copy.deepcopy(f);f['material']=co.tolist();f['material_refit_subpixels']=2
    f['material_lighting_bound']=4
    f['material_refit_loss']=float(np.mean(((shade(sample,light,co).mean(1)-expected)*weights[:,None])**2))
    return f


def main():
    ap=argparse.ArgumentParser();ap.add_argument('sources',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--refit',action='store_true');args=ap.parse_args()
    dest=Path(__file__).parent/'refinement.json'
    previous=json.loads(dest.read_text()) if dest.exists() else {}
    original=json.loads((Path(__file__).parent/'results.json').read_text())
    baseline=previous.get('baseline') or {n:{'model':r['displayed_model'],'fit':r['models'][r['displayed_model']],'case':r['case']} for n,r in original.items()}
    diagnostics={'scope':'Same five scenes; masks/source/geometry fixed. Historical trials are development evidence, not independent holdouts.','baseline_commit':'b1636bb56893eedc437f4800108fccedb01ffc6d','baseline':baseline,'iterations':[],'cases':{}}
    for name,case in CASES.items():
        source,target,_,_=prepare(args.sources,name,case)
        report=json.loads((args.out/f'{name}-report.json').read_text());x0,y0,x1,y1=case['crop']
        report['case']=case
        for model,f in report['models'].items():
            if args.refit:
                f=refit(source,target,case,f);report['models'][model]=f
                output=render(source,case,f).astype(np.float32);f['scores']=scores(output,target[y0:y1,x0:x1],case)
                (args.out/f'{name}-{model}.json').write_text(json.dumps(f,indent=2))
                cv2.imwrite(str(args.out/f'{name}-{model}.png'),np.uint8(np.clip(output,0,1)*255)[:,:,::-1])
            # Keep fixed-map scores and selected candidate separate from search loss.
        candidates=[m for m in report['models'] if m not in ('identity','default')]
        winner=min(candidates,key=lambda m:report['models'][m]['scores']['selection_loss']) if candidates else 'default'
        selected=report['models'][winner];diagnostics['cases'][name]={'selected_model':winner}
        if name=='regular':
            # Effective horizontal source scale on the straight top segment.
            d=np.array([1.,5.,10.,20.,40.,80.]);x=np.full_like(d,80.);y=-170+d;e=.001
            F=lambda a,b:np.array(lookup(a,b,case['half'],case['radius'],selected['parameters'],winner,case_geometry(case,a,b)))
            Jx=(F(x+e,y)-F(x-e,y))/(2*e)
            diagnostics['cases'][name]['tangent_scale_probe']={'inward_px':d.tolist(),'source_Jxx':Jx[0].tolist(),'displayed_horizontal_scale':(1/Jx[0]).tolist()}
            # Isolate the old inner highlight on a uniform field: it does not
            # require an optical map or source texture to exist.
            old=baseline[name]['fit']
            old=copy.deepcopy(old);old['model']='spline'
            d=np.arange(0.,81.);x=np.full_like(d,60.);y=170-d
            L=model_light(x,y,case,old,case_geometry(case,x,y));sample=np.full((len(d),3),.84)
            full=shade(sample,L,np.array(old['material'])).mean(1)*255
            L0=L.copy();L0[:,:9]=0
            bare=shade(sample,L0,np.array(old['material'])).mean(1)*255
            diagnostics['cases'][name]['old_uniform_field_lighting_probe']={'inward_px':d[::5].tolist(),'with_rim_terms':full[::5].tolist(),'without_rim_terms':bare[::5].tolist(),'note':'Removing all rim terms is diagnostic, not the proposed material.'}
        if name=='slider':
            # A colored feature over a nearly neutral page requires more than
            # common-coordinate lookup plus neutral lighting. It does not by
            # itself distinguish dispersion from colored reflection/adaptation.
            rim=target[491:497,720:801];back=source[491:497,720:801]
            diagnostics['cases'][name]['upper_rim_color']={'region':[720,491,801,497],'target_green_minus_blue_range_255':[float((rim[...,1]-rim[...,2]).min()*255),float((rim[...,1]-rim[...,2]).max()*255)],'source_green_minus_blue_range_255':[float((back[...,1]-back[...,2]).min()*255),float((back[...,1]-back[...,2]).max()*255)],'video_pixel_format':'yuv420p','interpretation':'Localized color is observed; unknown lighting, adaptive processing and chroma subsampling prevent unique attribution.'}
        (args.out/f'{name}-report.json').write_text(json.dumps(report,indent=2))
    for path in sorted(args.out.glob('*-*.json')):
        f=json.loads(path.read_text())
        if 'seed' not in f or not path.stem.endswith(str(f['seed'])):continue
        diagnostics['iterations'].append({'file':path.name,**f})
    if not diagnostics['iterations']:diagnostics['iterations']=previous.get('iterations',[])
    dest.write_text(json.dumps(diagnostics,indent=2)+'\n')
    print('Recorded',len(diagnostics['iterations']),'search restarts and five-case diagnostics')


if __name__=='__main__':main()
