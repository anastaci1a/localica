"""Verify frozen per-pass gates and basic normalized field invariants."""
from pathlib import Path
from collections import OrderedDict
import argparse,json
import numpy as np
from extended_fit import Study,initial,STAGES,RADIAL_STAGES,RADIAL_FOLLOWUP_STAGES


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

    wide=Study.__new__(Study);wide.crop=[0,0,1400,12];wide.L=32.;wide.case=dict(center=[700.,6.])
    next_row=0;sample_count=0
    for row,bottom,width,x,y in wide.raster_tiles(4):
        assert row==next_row and x.size<=131072
        expected_x=(np.arange(width*2)/8-.4375-700)/32
        expected_y=(row/4+np.arange((bottom-row)*2)/8-.4375-6)/32
        assert np.array_equal(x,np.broadcast_to(expected_x,x.shape))
        assert np.array_equal(y,np.broadcast_to(expected_y[:,None],y.shape))
        next_row=bottom;sample_count+=x.size
    assert next_row==48 and sample_count==1400*12*4**2*4
    print('PASS wide export strips bound working samples without gaps or coordinate changes')

    layered=Study.__new__(Study);layered.crop=[0,0,8,8]
    parent=[0,0,8,8];child=[2,2,6,6]
    layered.foreground_layers=[(parent,np.full((8,8),.2),np.full((8,8,3),.1)),(child,np.full((4,4),.75),np.full((4,4,3),.9))]
    layered.vector_layers={};layered.artwork_layers={};layered.transmission_layers=[];layered.case={}
    source=np.linspace(0,1,8*8*3).reshape(8,8,3)
    background=source*.8+.1*.2
    assert np.max(abs(layered.apply_foreground(source.copy(),stop_before=child)-background))<1e-12
    expected=background.copy();expected[2:6,2:6]=expected[2:6,2:6]*.25+.9*.75
    assert np.max(abs(layered.apply_foreground(source.copy())-expected))<1e-12
    print('PASS foreground calibration sees the fixed backing before a nested symbol')

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
    forbidden={'tangent','coupling','core_y','half_y_delta','cap_normal_gain','cap_tangent_fade','cap_join_width'}
    for schedule in [{**STAGES,**RADIAL_STAGES},RADIAL_FOLLOWUP_STAGES]:
        assert not any(forbidden.intersection(keys) for _,keys in schedule.values()), 'Radial experiment introduces a preferred axis'
    print('PASS radial initial/follow-up schedules preserve rotational symmetry')
    assert not {'amplitude','band','power'}.intersection(STAGES[20][1])
    assert set(RADIAL_STAGES[13][1])=={'amplitude','band','power','sigma','core_x'}
    print('PASS late nonradial refinement uses live spline parameters; radial restart stays independent')

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
    from foreground_response import affine_response
    style=dict(color=[.9,.2,.4],opacity=.75)
    assert np.max(abs(response(B,affine_response(style))-(B*.25+np.array(style['color'])*.75)))<1e-12
    assert np.max(abs(response(B,affine_response(dict(color=[0,0,0],opacity=1)))))==0
    print('PASS color/opacity embeds exactly in the monotone response, including black ink')

    from paired_fit import permitted
    def score(loss,rmse):return dict(loss=loss,glass=dict(rmse=rmse))
    baseline=[score(10,3),score(20,4)]
    assert not permitted([score(8,2.9),score(21,4)],baseline)
    assert not permitted([score(8,2.9),score(19,4.1)],baseline)
    assert permitted([score(9,2.9),score(20,4)],baseline)
    print('PASS joint-average gains cannot hide either sample loss/glass regression')

    # An independent affine/clip/filter fixture checks the sufficient-statistic
    # proposal model. Clipping is fixed within these small perturbations; the
    # full native renderer still owns acceptance when a proposal crosses it.
    from types import SimpleNamespace
    from paired_fit import Cached
    from material_local import statistics
    rng=np.random.default_rng(222);H,W=24,32
    cache=Cached.__new__(Cached);cache.H=H;cache.W=W
    cache.C=rng.choice([.1,.4,.6,.9],(H*2,W*2,3)).astype(np.float32)
    cache.J=rng.uniform(-.01,.01,(H*2,W*2,15));cache.J[...,-1]=rng.uniform(0,1,(H*2,W*2))
    yy,xx=np.mgrid[:H,:W];valid=np.ones((H,W),bool);rim=(xx<5)|(xx>=W-5)|(yy<5)|(yy>=H-5)
    cache.study=SimpleNamespace(target_crop=rng.uniform(.1,.9,(H,W,3)).astype(np.float32),valid=valid,rim=rim)
    maximum=0
    for clipping in [False,True]:
        co=np.zeros(23);co[:3]=co[-3:]=1.4 if clipping else .4;co[3:6]=-.2 if clipping else .3
        for sigma in [0.,.75]:
            cache.p=dict(post_sigma=sigma);base=cache.render(co);forms=statistics(cache,co)
            delta=rng.normal(0,.0001,23);actual=cache.render(co+delta)
            for name,mask in [('glass',valid),('rim',rim)]:
                Q,g=forms[name];target=cache.study.target_crop[mask]
                before=float(np.mean((base[mask].astype(float)-target)**2))*255**2
                after=float(np.mean((actual[mask].astype(float)-target)**2))*255**2
                difference=abs(after-(before+2*g@delta+delta@Q@delta));maximum=max(maximum,difference)
                assert difference<.005,(clipping,sigma,name,difference)
    print('PASS clipped/unclipped subpixel/filter quadratic proposals; max MSE difference',maximum)

    from profile_cache import ProfileCache
    fixture=Study.__new__(Study);fixture.L=16.;fixture.half=np.array([2.,1.]);fixture.radius=1.
    fixture.case=dict(center=[48.,24.],radial=False);fixture.crop=[12,4,84,44]
    y,x=np.mgrid[:48,:96];fixture.source=np.stack([x/96,y/48,(x/96+y/48)/2],-1).astype(np.float32)
    fixture.cache=OrderedDict();fixture.backdrop_shift=[0.,0.]
    p=initial();p.update(spline=True,sigma=1.5,post_sigma=.4,tangent=.05,coupling=.2,cap_normal_gain=.2,cap_tangent_fade=.1,cap_join_width=.1,shape_dx=.01,half_x_delta=.02)
    coefficients=np.array([.6,.4,.3,.2,.1,.02,0.]);p.update({f'c{i}':float(v) for i,v in enumerate(coefficients)})
    co=np.zeros(23);co[:3]=co[-3:]=.6;co[3:6]=.1
    fast=ProfileCache(fixture,p,co)
    for delta in [np.zeros(7),rng.normal(0,.02,7)]:
        q={**p,**{f'c{i}':float(v) for i,v in enumerate(coefficients+delta)}}
        assert np.max(abs(fast.render(coefficients+delta)-fixture.render(q,co)))<2e-6
    print('PASS prepared profile lookup with coupling, orientation gain, shape correction, smooth caps and output blur')


if __name__=='__main__':main()
