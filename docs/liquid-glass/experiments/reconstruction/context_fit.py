"""One bounded shared context experiment after an immutable paired checkpoint."""
from pathlib import Path
import argparse,copy,hashlib,json,time
import numpy as np
from extended_fit import Study
from paired_fit import Cached,permitted
from source_support import coverage_report,supported
from material_context import matrix


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('--pass',dest='number',type=int,required=True);args=ap.parse_args()
    start=time.time();folder=args.folder;record_path=folder/f'pass-{args.number:02}.json'
    assert not record_path.exists(),'Keep experiment records immutable'
    old=json.loads((folder/'accepted.json').read_text());current=copy.deepcopy(old)
    studies=[Study(folder/name) for name in ['a','b']];trials=[];evaluations=0
    for sensitivity in [5.,10.,20.,40.]:
        for floor in [0.,.1,.25]:
            p={**old['parameters'],'material_chroma_sensitivity':sensitivity,'material_chroma_floor':floor}
            metrics=[s.scores(s.render(p,np.asarray(old['material']))) for s in studies];evaluations+=1
            allow=permitted(metrics,current['scores'])
            trials.append(dict(sensitivity=sensitivity,floor=floor,scores=metrics,permitted=allow))
            if allow and sum(m['loss'] for m in metrics)<sum(m['loss'] for m in current['scores']):current.update(parameters=p,scores=metrics)
    p=current['parameters'];co=np.asarray(current['material'])
    for s in studies:
        assert np.max(abs(Cached(s,p).render(co)-s.render(p,co)))<2e-6
    footprints=[coverage_report(s,p) for s in studies]
    assert permitted(current['scores'],old['scores']) and all(supported(s,r) for s,r in zip(studies,footprints))
    record=dict(pass_number=args.number,experiment='Source-derived neutral-background material contraction',
        execution_script=Path(__file__).name,execution_code_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        accepted=current!=old,previous=old,current=current,trials=trials,evaluations=evaluations,seconds=time.time()-start,
        proposed_source_support=footprints,context_transforms=[matrix(s,p).tolist() for s in studies],
        gate='Both native loss and whole-glass RMSE hold for each background; one shared source-context function and23 material coefficients.',
        limits='Encoded-RGB material adaptation is empirical. It does not reconstruct the missing spectral rim or establish Apple internals.')
    record_path.write_text(json.dumps(record,indent=2)+'\n');(folder/'accepted.json').write_text(json.dumps(current,indent=2)+'\n')
    for name,s in zip(['a','b'],studies):s.comparison(s.render(p,co),folder/f'pass-{args.number:02}-{name}.png',p)
    print(json.dumps(dict(pass_number=args.number,accepted=record['accepted'],evaluations=evaluations,scores=current['scores'])),flush=True)


if __name__=='__main__':main()
