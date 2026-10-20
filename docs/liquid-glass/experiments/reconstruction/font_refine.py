"""Compare original font outlines on both backgrounds, without photo mattes.

python font_refine.py /private/study/paired /private/fonts --pass-number 24
Candidates are whole original glyphs with normal AAT tracking at13pt. Fit only
uniform size, translation and shared constant color. No per-letter deformation.
"""
from pathlib import Path
import argparse,copy,hashlib,json,time
import numpy as np
from scipy.optimize import minimize
from extended_fit import Study
from font_vector import export
from vector_foreground import _coverage
from vector_refine import foreground_errors
from paired_fit import permitted


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('fonts',type=Path)
    ap.add_argument('--pass-number',type=int,required=True);args=ap.parse_args();r=args.folder
    out=r/f'pass-{args.pass_number:02}.json';assert not out.exists()
    began=time.time();old=json.loads((r/'accepted.json').read_text());current=copy.deepcopy(old)
    studies=[Study(r/name) for name in ['a','b']];p=old['parameters'];co=np.array(old['material'])
    for s in studies:s.case['foreground_styles']=copy.deepcopy(old['foreground_styles'])
    before=foreground_errors(studies,p,co);assert np.max(abs(np.array(before)-old['foreground_style_scores']['after_mse']))<1e-3
    records=[];changes=[];evaluations=0
    for key in [k for k in old['foreground_styles'] if k.endswith('-label.svg')]:
        samples=[];layers=[]
        for s in studies:
            layer=next(l for l in s.case['foreground_layers'] if l.get('style_key',l.get('vector_asset'))==key)
            layers.append(layer);a,b,c,d=layer['box'];x0,y0,_,_=s.crop
            B=s.apply_foreground(s.render(p,co),stop_before=layer['box'])[b-y0:d-y0,a-x0:c-x0]
            samples.append((B,s.target[b:d,a:c],layer))
        text=layers[0]['vector_provenance']['text'];style=old['foreground_styles'][key]
        assert style['opacity']==1 and 'response' not in style
        baseline=np.array([before[i*len(old['foreground_styles'])+list(old['foreground_styles']).index(key)] for i in range(2)])
        best=None;best_error=baseline.copy();trials=[]
        for font in sorted(args.fonts.glob('SF-Pro-*.otf')):
            raw,metadata=export(font,text=text,size_pt=13);raw=raw.encode();b=metadata['bounds'];ar=(b[2]-b[0])/(b[3]-b[1])
            def placement(v,layer):
                left,top,right,bottom=layer['vector_box'];cx=(left+right)/2+v[0];cy=(top+bottom)/2+v[1]
                h=(bottom-top)*v[2];w=h*ar
                return [cx-w/2,cy-h/2,cx+w/2,cy+h/2]
            def evaluate(v,details=False):
                arrays=[];numer=np.zeros(3);denom=0.
                for B,T,layer in samples:
                    A=_coverage(raw,tuple(layer['box']),tuple(placement(v,layer)),1)
                    numer+=np.sum(A[...,None]*(T-B*(1-A[...,None])),axis=(0,1));denom+=np.sum(A*A)
                    arrays.append((B,T,A))
                C=np.clip(numer/max(1e-12,denom),0,1)
                errors=np.array([float(np.mean((B*(1-A[...,None])+C*A[...,None]-T)**2))*255**2 for B,T,A in arrays])
                return (errors,C) if details else float(errors.mean())
            fit=minimize(evaluate,[0,0,1],method='Powell',bounds=[(-2,2),(-2,2),(.93,1.07)],options=dict(maxfev=300,xtol=.005,ftol=1e-7))
            errors,C=evaluate(fit.x,True);evaluations+=int(fit.nfev)
            allowed=bool(np.all(errors<=baseline+1e-8))
            trials.append(dict(font=font.name,font_sha256=metadata['font_sha256'],parameters=fit.x.tolist(),mse=errors.tolist(),accepted_by_gate=allowed,evaluations=int(fit.nfev)))
            if allowed and errors.sum()<best_error.sum():
                best=dict(raw=raw,metadata=metadata,color=C.tolist(),placement=[placement(fit.x,l) for l in layers],font=font.name);best_error=errors
        if best is not None:
            filename=key.replace('.svg','-refined.svg');sha=hashlib.sha256(best['raw']).hexdigest()
            for i,(s,layer) in enumerate(zip(studies,layers)):
                previous=copy.deepcopy(layer);(s.folder/filename).write_bytes(best['raw'])
                layer.update(vector_asset=filename,style_key=key,vector_sha256=sha,vector_box=best['placement'][i],vector_provenance=dict(best['metadata'],source_url='https://devimages-cdn.apple.com/design/resources/download/SF-Symbols-7.dmg',method='Original font outlines; family/weight/pose inferred by two-reference fit, not read from Apple app code.'))
                changes.append(dict(scene=i,previous=previous,current=copy.deepcopy(layer)))
                s.case['foreground_styles'][key]=dict(color=best['color'],opacity=1.)
                current['foreground_styles'][key]=dict(color=best['color'],opacity=1.)
        records.append(dict(style_key=key,before_mse=baseline.tolist(),after_mse=best_error.tolist(),selected=best['font'] if best else None,trials=trials))
        print(key,records[-1]['selected'],baseline.tolist(),'->',best_error.tolist(),flush=True)
    after=foreground_errors(studies,p,co);metrics=[s.scores(s.render(p,co)) for s in studies]
    assert permitted(metrics,old['scores']) and np.all(np.asarray(after)<=np.asarray(before)+1e-5)
    current['scores']=metrics;current['foreground_style_scores']=dict(old['foreground_style_scores'],before_mse=before,after_mse=after,optimizer_evaluations=evaluations,
        formula='Whole original font outlines with shared color and uniform pose; fixed glass and masks.')
    record=dict(pass_number=args.pass_number,experiment='Shared original font family and uniform label pose',accepted=bool(changes),previous=old,current=current,trials=records,foreground_case_changes=changes,
        evaluations=evaluations,execution_code_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),seconds=time.time()-began,
        gate='Both frozen native glass losses/RMSE and every foreground role on both backgrounds retain or improve.',
        limits='Finite original font candidates; matching contours do not prove the original Apple app font configuration. No per-letter fitting.')
    for s in studies:(s.folder/'case.json').write_text(json.dumps(s.case,indent=2)+'\n')
    out.write_text(json.dumps(record,indent=2)+'\n');(r/'accepted.json').write_text(json.dumps(current,indent=2)+'\n')


if __name__=='__main__':main()
