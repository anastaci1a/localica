"""Verify frozen per-pass gates and basic normalized field invariants."""
from pathlib import Path
from collections import OrderedDict
import argparse,json
import numpy as np
from extended_fit import Study,initial


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--study');ap.add_argument('--minimum',type=int,default=10);args=ap.parse_args()
    root=Path(__file__).parent/'studies'
    folders=[root/args.study] if args.study else sorted(p for p in root.iterdir() if p.is_dir())
    for folder in folders:
        rows=[json.loads(line) for line in (folder/'passes.jsonl').read_text().splitlines() if line]
        assert len(rows)>=args.minimum,(folder.name,len(rows))
        assert [r['pass'] for r in rows]==list(range(1,len(rows)+1))
        for row in rows:
            a,b=row['previous'],row['retained']
            assert b['loss']<=a['loss']+1e-8,(folder.name,row['pass'],'overall regression')
            assert b['glass']['rmse']<=a['glass']['rmse']+1e-8,(folder.name,row['pass'],'glass regression')
            expected=.7*b['glass']['rmse']**2+.3*b['rim']['rmse']**2
            assert abs(expected-b['loss'])<1e-8
            assert len(row['material'])==23 and np.isfinite(row['material']).all()
            if not row['accepted']:assert b==a
            if 'exterior' in a and 'exterior' in b:
                assert b['exterior']['rmse']<=a['exterior']['rmse']+1e-8
        final=json.loads((folder/'accepted.json').read_text())
        assert final['scores']==rows[-1]['retained']
        assert final['parameters']==rows[-1]['retained_parameters']
        case=json.loads((folder/'case.json').read_text())
        assert min(final['shape']['half'])==1
        assert all(len(h)==64 for h in case['source_sha256'].values())
        print('PASS',folder.name,len(rows),'non-regressing pass records',rows[0]['previous']['loss'],'->',rows[-1]['retained']['loss'])

    # A zero-optics material must sample the unwarped analytic backdrop,
    # independently of where the shape origin/half-extents are measured.
    obj=Study.__new__(Study);obj.L=64;obj.half=np.array([3.,1.]);obj.radius=1.
    obj.case=dict(center=[192,96]);Y,X=np.mgrid[:192,:384]
    obj.source=np.stack([X/384,Y/192,np.full_like(X,.5,dtype=float)],-1).astype(np.float32)
    obj.cache=OrderedDict();obj.backdrop_shift=[0.,0.]
    p=initial();p.update(shape_dx=.03,shape_dy=-.02,half_y_delta=.04)
    x=np.array([-.7,0,.8]);y=np.array([.2,.3,-.5])
    color,light=obj.field(x,y,p)
    expected=np.stack([(192+64*x)/384,(96+64*y)/192,np.full_like(x,.5)],-1)
    assert np.max(abs(color-expected))<2e-4
    assert light.shape==(3,15) and np.isfinite(light).all()
    print('PASS zero-optics registration despite shape corrections; finite normalized lighting')


if __name__=='__main__':main()
