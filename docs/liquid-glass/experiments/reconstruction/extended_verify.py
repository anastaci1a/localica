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

    # Radial lookup must commute with a quarter-turn. Material lighting is
    # deliberately excluded: an environment may have a preferred direction.
    obj.half=np.ones(2);obj.radius=1.;obj.case['radial']=True
    p=initial();p.update(amplitude=.42,core_x=.012)
    x=np.array([-.7,.3,.5]);y=np.array([.2,.4,-.6])
    a,_=obj.field(x,y,p);b,_=obj.field(-y,x,p)
    def coordinates(c):return np.stack([(c[:,0]*384-192)/64,(c[:,1]*192-96)/64],-1)
    a=coordinates(a);b=coordinates(b)
    assert np.max(abs(b-np.stack([-a[:,1],a[:,0]],-1)))<.0011
    print('PASS circular optical field has no preferred x/y axis')

    from foreground_response import response,calibrate
    Y,X=np.mgrid[:40,:40]/39
    B=np.stack([X,Y,(X+Y)/2],-1)
    params=np.array([[1.,.9,1.6],[.2,.7,4.],[.5,.4,2.5]])
    seed=np.zeros((40,40),bool);seed[5:35,5:35]=True
    T=np.where(seed[...,None],response(B,params),B)
    fit,alpha,report=calibrate(B,T,seed)
    probe=np.repeat(np.linspace(0,1,101)[:,None],3,axis=1)
    actual=response(probe,fit)
    assert report['rmse']<.001
    assert np.max(abs(actual-response(probe,params)))<.001
    assert np.all(np.diff(actual,axis=0)>=-1e-8)
    assert actual.min()>=0 and actual.max()<=1
    assert np.all(alpha[8:32,8:32]==1)
    print('PASS foreground-response recovery, held-range monotonicity, gamut and opaque interior')

    from paired_fit import permitted
    def score(loss,rmse):return dict(loss=loss,glass=dict(rmse=rmse))
    baseline=[score(10,3),score(20,4)]
    assert not permitted([score(8,2.9),score(21,4)],baseline)
    assert not permitted([score(8,2.9),score(19,4.1)],baseline)
    assert permitted([score(9,2.9),score(20,4)],baseline)
    print('PASS joint-average gains cannot hide either sample loss/glass regression')


if __name__=='__main__':main()
