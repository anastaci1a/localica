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


def main():
    ap=argparse.ArgumentParser();ap.add_argument('study');ap.add_argument('inputs',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--motion',action='store_true');ap.add_argument('--icons',type=Path);ap.add_argument('--minimum',type=int,default=20);args=ap.parse_args()
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
    cases=[json.loads((root/(name+'.json')).read_text()) for name in ['a','b']]
    assert cases[0]['source_sha256']['source.png']!=cases[1]['source_sha256']['source.png'],'Same backdrop counted twice'
    assert cases[0]['reference']!=cases[1]['reference'],'Need another observed instance'
    args.out.mkdir(parents=True,exist_ok=True);metrics=[];studies=[]
    for index,(name,case) in enumerate(zip(['a','b'],cases)):
        folder=args.out/name;folder.mkdir(exist_ok=True)
        for dest,input_name in case['input_names'].items():shutil.copyfile(args.inputs/input_name,folder/dest)
        for layer in case.get('foreground_layers',[]):
            if 'vector_asset' in layer:
                source=(args.icons or args.inputs)/layer['vector_asset']
                assert source.is_file(),f'Missing private solid-vector input: {source}'
                shutil.copyfile(source,folder/layer['vector_asset'])
        if 'foreground_response' in state:case['foreground_response']=state['foreground_response']
        (folder/'case.json').write_text(json.dumps(case,indent=2)+'\n')
        p=state['parameters'].copy();p['sigma']*=min(case['half']);p['post_sigma']*=min(case['half'])
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
        assert diagnostic['source_observed_fraction']>=.999,'Unobserved source under a scored lookup'
        assert diagnostic['gaussian_3sigma_observed_fraction']>=.999,'Source-scattering footprint crosses a recovery gap'
        if args.motion:subprocess.run([sys.executable,str(Path(__file__).with_name('extended_media.py')),str(folder),'--frames','180','--travel','45'],check=True)
    if 'foreground_response' in state:
        assert abs(studies[0].L-studies[1].L)<1e-12
        errors=foreground_errors(studies,p,state['material'],state['foreground_response'])
        stored=state['foreground_scores']['after_mse'];assert np.max(abs(np.array(errors)-stored))<1e-5
    else:errors=None
    report=dict(paired_passes=valid_count,record_count=len(rows),excluded_passes=[r['pass_number'] for r in rows if not r.get('valid_experiment',True)],scores=metrics,foreground_mse=errors,
        gate='Both references retain non-increasing native overall loss and whole-glass RMSE; exact shared normalized recipe replay.',
        scope='Same video, different observed backdrops. Does not establish generality across devices, apps or lighting.')
    (args.out/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PASS paired replay',args.study,json.dumps(report))


if __name__=='__main__':main()
