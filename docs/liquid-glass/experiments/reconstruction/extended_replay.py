"""Replay a frozen study using recovered inputs; does not run an optimizer.

python extended_replay.py /private/recovery /private/replay --study music-mini-expanded
"""
from pathlib import Path
import argparse,json,shutil
import numpy as np
from extended_fit import Study
from vector_foreground import copy_assets


def main():
    ap=argparse.ArgumentParser();ap.add_argument('inputs',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--study',required=True);ap.add_argument('--icons','--assets',dest='icons',type=Path);args=ap.parse_args()
    saved=Path(__file__).parent/'studies'/args.study
    case=json.loads((saved/'case.json').read_text());recipe=json.loads((saved/'accepted.json').read_text())
    args.out.mkdir(parents=True,exist_ok=True)
    for dest,name in case['input_names'].items():shutil.copyfile(args.inputs/name,args.out/dest)
    copy_assets(case,args.icons or args.inputs,args.out)
    (args.out/'case.json').write_text(json.dumps(case,indent=2)+'\n')
    L=min(case['half']);p=recipe['parameters'].copy();p['sigma']*=L;p['post_sigma']*=L
    accepted=dict(parameters=p,material=recipe['material'],scores=recipe['scores'])
    (args.out/'accepted.json').write_text(json.dumps(accepted,indent=2)+'\n')
    study=Study(args.out);material=study.render(p,np.array(recipe['material']));scores=study.scores(material)
    assert abs(scores['loss']-recipe['scores']['loss'])<1e-6,'Frozen replay mismatch'
    study.comparison(material,args.out/'comparison.png',p)
    (args.out/'scores.json').write_text(json.dumps(scores,indent=2)+'\n')
    print(args.study,'replays exactly',scores['loss'])


if __name__=='__main__':main()
