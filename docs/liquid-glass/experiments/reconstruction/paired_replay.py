"""Verify/replay both references of a frozen shared-model study; no optimizer.

python paired_replay.py music-home /private/recovered-inputs /private/replay
Add --motion to encode separate motion previews for both observed backgrounds.
"""
from pathlib import Path
import argparse,json,shutil,subprocess,sys
import numpy as np
from extended_fit import Study
from paired_fit import permitted,foreground_errors
from paired_fit import Cached
from vector_foreground import copy_assets


def main():
    ap=argparse.ArgumentParser();ap.add_argument('study');ap.add_argument('inputs',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--motion',action='store_true');ap.add_argument('--icons','--assets',dest='icons',type=Path);ap.add_argument('--minimum',type=int,default=20);args=ap.parse_args()
    root=Path(__file__).parent/'studies'/args.study/'paired'
    rows=[json.loads(line) for line in (root/'passes.jsonl').read_text().splitlines() if line]
    assert len(rows)>=args.minimum and [r['pass_number'] for r in rows]==list(range(1,len(rows)+1))
    valid_count=sum(r.get('valid_experiment',True) for r in rows)
    assert valid_count>=args.minimum,'Invalid experiments do not count toward the minimum'
    previous=None
    for row in rows:
        old=row['previous'];new=row['current']
        assert permitted(new['scores'],old['scores']),('Per-sample regression',row['pass_number'])
        assert len(new['material'])==23 and np.isfinite(new['material']).all()
        if previous is not None:assert old==previous,'Broken paired chain'
        previous=new
    state=json.loads((root/'accepted.json').read_text());assert state==previous
    from foreground_response import response
    for style in state.get('foreground_styles',{}).values():
        if 'response' not in style:continue
        q=np.asarray(style['response']);assert q.shape==(3,3) and np.isfinite(q).all()
        assert np.all((q[:,:2]>=0)&(q[:,:2]<=1)) and np.all(q[:,2]>0)
        values=response(np.repeat(np.linspace(0,1,257)[:,None],3,axis=1),q)
        assert np.all(np.diff(values,axis=0)>=-1e-10) and values.min()>=0 and values.max()<=1
    cases=[json.loads((root/(name+'.json')).read_text()) for name in ['a','b']]
    assert cases[0]['source_sha256']['source.png']!=cases[1]['source_sha256']['source.png'],'Same backdrop counted twice'
    assert cases[0]['reference']!=cases[1]['reference'],'Need another observed instance'
    args.out.mkdir(parents=True,exist_ok=True);metrics=[];studies=[]
    for index,(name,case) in enumerate(zip(['a','b'],cases)):
        folder=args.out/name;folder.mkdir(exist_ok=True)
        for dest,input_name in case['input_names'].items():shutil.copyfile(args.inputs/input_name,folder/dest)
        copy_assets(case,args.icons or args.inputs,folder)
        if 'foreground_response' in state:case['foreground_response']=state['foreground_response']
        if 'foreground_styles' in state:case['foreground_styles']=state['foreground_styles']
        (folder/'case.json').write_text(json.dumps(case,indent=2)+'\n')
        length=case.get('parameter_length_reference',min(case['half']))
        p=state['parameters'].copy();p['sigma']*=length;p['post_sigma']*=length
        accepted=dict(parameters=p,material=state['material'],scores=state['scores'][index])
        (folder/'accepted.json').write_text(json.dumps(accepted,indent=2)+'\n')
        s=Study(folder);pred=s.render(p,np.array(state['material']));score=s.scores(pred)
        assert abs(score['loss']-state['scores'][index]['loss'])<1e-6,'Native paired replay mismatch'
        assert abs(score['glass']['rmse']-state['scores'][index]['glass']['rmse'])<1e-6
        cache=Cached(s,p)
        for co in [np.array(state['material']),np.array(state['material'])+.08]:
            assert np.max(abs(cache.render(co)-s.render(p,co)))<2e-6,'Cache changes native integration/clipping'
        s.comparison(pred,folder/'comparison.png',p);metrics.append(score);studies.append(s)
        subprocess.run([sys.executable,str(Path(__file__).with_name('extended_diagnostics.py')),str(folder)],check=True)
        diagnostic=json.loads((folder/'diagnostics.json').read_text())
        assert diagnostic['source_defined_fraction']>=.999,'Undefined source under a scored lookup'
        assert diagnostic['gaussian_3sigma_defined_fraction']>=.999,'Source-scattering footprint crosses an undefined gap'
        if not case.get('allow_estimated_background'):
            assert diagnostic['source_observed_fraction']>=.999,'Unobserved source under a scored lookup'
            assert diagnostic['gaussian_3sigma_observed_fraction']>=.999,'Source-scattering footprint crosses a recovery gap'
        else:
            assert case.get('source_observation_mask') and diagnostic['background_estimate'],'Explicit estimation provenance is required'
        if args.motion:subprocess.run([sys.executable,str(Path(__file__).with_name('extended_media.py')),str(folder),'--frames','180','--travel','45'],check=True)
    if 'foreground_response' in state:
        errors=foreground_errors(studies,p,state['material'],state['foreground_response'])
        stored=state['foreground_scores']['after_mse'];assert np.max(abs(np.array(errors)-stored))<1e-5
    else:errors=None
    if 'foreground_style_scores' in state:
        # Replay the actual compositor, not the optimizer's linear surrogate.
        actual=[]
        for s in studies:
            pred=s.render(p,np.array(state['material']));composite=s.apply_foreground(pred.copy());x0,y0,_,_=s.crop
            for layer in s.case['foreground_layers']:
                if 'vector_asset' in layer and layer['mode']!='transmission' and layer.get('fit_style',True):
                    a,b,c,d=layer['box'];actual.append(float(np.mean((composite[b-y0:d-y0,a-x0:c-x0]-s.target[b:d,a:c])**2))*255**2)
        assert np.max(abs(np.array(actual)-state['foreground_style_scores']['after_mse']))<1e-3,'Static foreground replay mismatch'
    report=dict(paired_passes=valid_count,record_count=len(rows),excluded_passes=[r['pass_number'] for r in rows if not r.get('valid_experiment',True)],scores=metrics,foreground_mse=errors,foreground_style_scores=state.get('foreground_style_scores'),
        gate='Both references retain non-increasing native overall loss and whole-glass RMSE; exact shared normalized recipe replay.',
        background_estimates=[case.get('background_estimate') for case in cases],
        scope='Two observed instances; diagnostics distinguish direct source evidence from any explicit source estimate. Does not establish generality across devices, apps or lighting.')
    (args.out/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PASS paired replay',args.study,json.dumps(report))


if __name__=='__main__':main()
