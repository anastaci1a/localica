"""Publish a freshly evaluated4x single-reference research checkpoint."""
from pathlib import Path
import argparse,copy,hashlib,json,re
import cv2,numpy as np
from extended_fit import Study
from source_support import coverage_report,supported
from motion_support import complete

ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('--id',required=True);ap.add_argument('--pass',dest='number',type=int,required=True);ap.add_argument('--code-commit',required=True);a=ap.parse_args()
assert re.fullmatch('[a-z0-9-]+',a.id) and re.fullmatch('[a-f0-9]{40}',a.code_commit)
guide=Path(__file__).resolve().parents[2];state=json.loads((a.folder/'accepted.json').read_text());p=state['parameters'];co=np.array(state['material'])
s=Study(a.folder);native=s.render(p,co);score=s.scores(native);assert abs(score['loss']-state['scores']['loss'])<1e-6
support=coverage_report(s,p);assert supported(s,support)
known=cv2.imread(str(a.folder/'known.png'),0).astype(np.float32)/255;s.source=complete(s,known);s.cache.clear()
presentation=s.render(p,co);delta=(presentation-native)[s.valid]*255
asset=f'study-{a.id}-progress-a.png';s.comparison(s.render(p,co,4),guide/'assets'/asset,p,4,context_completed=True)
recipe=copy.deepcopy(state);L=s.case.get('parameter_length_reference',s.L)
for key in ['sigma','post_sigma']:recipe['parameters'][key]/=L
report=dict(status='Single-reference progress; paired verification and final motion pending',study=a.id,pass_number=a.number,rendering_code_commit=a.code_commit,script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),output_scale=4,native=score,recipe=recipe,source_support=support,presentation_completion_delta=dict(rmse=float(np.sqrt(np.mean(delta**2))),max_abs_rgb=float(np.max(abs(delta)))),background_estimate=s.case.get('background_estimate'),limits='Native masks and input hashes stay frozen. Higher output resolution creates no additional photographic evidence; input reconstruction assumptions remain separate from rendering accuracy.')
(guide/'experiments/reconstruction'/f'{a.id}-progress.json').write_text(json.dumps(report,indent=2)+'\n')
file=guide/'reconstruction.md';text=file.read_text();start=text.index('## Active segmented selector\n');end=text.index('\n## Scores\n',start)
section=text[start:end];section,n=re.subn(r'Single-reference pass \d+',f'Single-reference pass {a.number}',section);assert n==1
section=re.sub(r'Weighted loss [\d.]+; glass RMSE [\d.]+',f"Weighted loss {score['loss']:.3f}; glass RMSE {score['glass']['rmse']:.3f}",section)
file.write_text(text[:start]+section+text[end:]);print(json.dumps(dict(study=a.id,pass_number=a.number,loss=score['loss'],glass=score['glass']['rmse'],output_scale=4)))
