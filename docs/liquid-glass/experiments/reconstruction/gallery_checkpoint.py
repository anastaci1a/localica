"""Attach current paired measurements and fresh4x comparisons to the guide.

This publishes a labeled progress snapshot; it does not fit parameters, count
an extra experiment, or replace final paired/source/media verification.
"""
from pathlib import Path
import argparse,copy,hashlib,json,re
import numpy as np
from extended_fit import Study
from source_support import coverage_report,supported


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('folder',type=Path,help='Private paired-study folder')
    ap.add_argument('--pass',dest='number',type=int,required=True)
    ap.add_argument('--id',required=True)
    ap.add_argument('--section',required=True)
    ap.add_argument('--score-label',required=True)
    ap.add_argument('--snapshot',required=True,help='Public JSON basename')
    ap.add_argument('--code-commit',required=True,help='Full host Git commit for the rendering implementation')
    args=ap.parse_args();guide=Path(__file__).resolve().parents[2]
    assert re.fullmatch(r'[a-z0-9-]+',args.id)
    assert Path(args.snapshot).name==args.snapshot and args.snapshot.endswith('.json')
    assert re.fullmatch(r'[0-9a-f]{40}',args.code_commit)
    raw=(args.folder/f'pass-{args.number:02}.json').read_bytes()
    state=json.loads(raw)['current'];p=state['parameters'];co=np.array(state['material'])
    singles=[json.loads(r) for r in (guide/'experiments/reconstruction/studies'/args.id/'passes.jsonl').read_text().splitlines() if r]
    samples=[];details=[]
    for index,name in enumerate(['a','b']):
        study=Study(args.folder/name);prediction=study.render(p,co);score=study.scores(prediction)
        assert abs(score['loss']-state['scores'][index]['loss'])<1e-6,'Snapshot changes frozen native loss'
        assert abs(score['glass']['rmse']-state['scores'][index]['glass']['rmse'])<1e-6
        footprint=coverage_report(study,p);assert supported(study,footprint)
        image=guide/'assets'/f'study-{args.id}-progress-{name}.png'
        study.comparison(study.render(p,co,4),image,p,4)
        if args.id=='active-switch':
            from PIL import Image
            comparison=Image.open(image);width=(comparison.width-2)//2
            bar=comparison.height-(study.crop[3]-study.crop[1])*4
            measured=json.loads((guide/'experiments/reconstruction/switch-rim-analysis.json').read_text())['samples'][index]
            for patch in measured['samples']:
                X,Y=patch['position'];left=(X-48-study.crop[0])*4;top=(Y-36-study.crop[1])*4+bar
                a=comparison.crop((left,top,left+384,top+288))
                b=comparison.crop((left+width+2,top,left+width+386,top+288))
                details.append((f"{name.upper()} · {patch['region'].replace('_',' ')}",a,b))
        samples.append(dict(reference=study.case['reference'],native=score,source_support=footprint,
                            background_estimate=study.case.get('background_estimate')))
    case=json.loads((args.folder/'a/case.json').read_text())
    L=case.get('parameter_length_reference',min(case['half']))
    recipe=copy.deepcopy(state);recipe['parameters']['sigma']/=L;recipe['parameters']['post_sigma']/=L
    report=dict(status='In progress, not a final paired study',rendering_code_commit=args.code_commit,
                gallery_script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                state_record_sha256=hashlib.sha256(raw).hexdigest(),single_passes=len(singles),paired_passes=args.number,
                output_scale=4,recipe=recipe,
                units='Geometry and source/output kernel lengths normalized by each pose L; kernel records use the common declared reference length.',
                samples=samples,limits='Progress snapshot only; final cross-reference/source/media verification remains separate.')
    (guide/'experiments/reconstruction'/args.snapshot).write_text(json.dumps(report,indent=2)+'\n')
    file=guide/'reconstruction.md';text=file.read_text()
    start=text.index('## '+args.section+'\n');end=text.find('\n## ',start+1)
    if end<0:end=len(text)
    section=text[start:end]
    section,n=re.subn(r'\bpaired pass \d+',f'paired pass {args.number}',section)
    assert n==1,'Keep one current pass label beside the figures'
    section=re.sub(r'\[Frozen pass/model evidence at [^]]+\]\([^\n]+?\)',
                   f'[Current recipe and pass records](experiments/reconstruction/studies/{args.id}/paired/accepted.json)',section)
    if details:
        from PIL import Image,ImageDraw,ImageFont
        canvas=Image.new('RGB',(770,len(details)*324),'#14171e');draw=ImageDraw.Draw(canvas)
        font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',18)
        for row,(label,a,b) in enumerate(details):
            y=row*324;draw.text((8,y+6),label+' · Apple',font=font,fill='#f0f2f7')
            draw.text((394,y+6),f'Reconstruction · pass {args.number}',font=font,fill='#f0f2f7')
            canvas.paste(a,(0,y+36));canvas.paste(b,(386,y+36))
        canvas.save(guide/'assets/switch-rim-detail.png')
        section=re.sub(r'snapshot \d+ on the right',f'snapshot {args.number} on the right',section)
    text=text[:start]+section+text[end:]
    a,b=[s['native'] for s in samples]
    row=f"| {args.score_label}, pass {args.number} | {a['loss']:.3f} / {b['loss']:.3f} | {a['glass']['rmse']:.3f} / {b['glass']['rmse']:.3f} | {len(singles)} / {args.number} | In progress |"
    text,n=re.subn(r'^\| '+re.escape(args.score_label)+r', pass \d+ \|.*$',lambda _:row,text,flags=re.M)
    assert n==1,'Keep one current measurement row per element'
    file.write_text(text)
    print(json.dumps(dict(study=args.id,paired_pass=args.number,native_losses=[a['loss'],b['loss']],output_scale=4)),flush=True)


if __name__=='__main__':main()
