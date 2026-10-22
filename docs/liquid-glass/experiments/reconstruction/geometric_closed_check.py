"""Fixed closed-surface geometry discriminator, without image inputs.
Usage: python geometric_closed_check.py NEW_OUTPUT
"""
import sys
import os,time,json,hashlib
from pathlib import Path
import numpy as np
from geometric_optics import ClosedCircularOval,trace_closed_orthographic,refract
os.umask(2);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=False);started=time.time();ray_count=0
protocol=dict(start_unix=started,deadline_unix=started+1200,ray_limit=30000,images=0,fits=0,depth=.15,kappas=[0,1/3],index=1.5,background_distance=2,tolerance=1e-11,iteration_limit=64)
(out/'protocol-FIRST.json').write_text(json.dumps(protocol,indent=2)+'\n')
def trace(s,x,y,n=1.5):
 global ray_count
 ray_count+=np.broadcast_arrays(x,y)[0].size
 assert ray_count<=30000 and time.time()-started<1100
 return trace_closed_orthographic(s,x,y,n,2.)
checks=[];rows=[];rng=np.random.default_rng(92)
for kap in [0,1/3]:
 s=ClosedCircularOval(.15,kap);points=rng.uniform([-1,-1,-.3],[1,1,0],(101,3));f,g=s.level_gradient(points);e=1e-6
 fd=np.stack([(s.level_gradient(points+np.eye(3)[i]*e)[0]-s.level_gradient(points-np.eye(3)[i]*e)[0])/(2*e) for i in range(3)],-1)
 assert abs(fd-g).max()<1e-8
 xx=np.linspace(-.98,.98,101);yy=np.full_like(xx,.07);identity=trace(s,xx,yy,1.)
 assert identity['complete'].all() and max(abs(identity['X']-xx).max(),abs(identity['Y']-yy).max())<1e-10
 checks.append(dict(kappa=kap,gradient_error=float(abs(fd-g).max()),identity_error=float(abs(identity['X']-xx).max())))
(out/'invariants-FIRST.json').write_text(json.dumps(checks,indent=2)+'\n')
for kap in [0,1/3]:
 s=ClosedCircularOval(.15,kap);axis=np.linspace(-1.03,1.03,71);x,y=np.meshgrid(axis,axis);q=trace(s,x,y);inside=q['inside'];valid=q['complete']&inside
 assert np.all(q['intersection_bracket'][inside]<=1e-11)
 assert abs(q['exit_level_residual'][inside]).max()<1e-8
 for t in [.1,.5,.9]:
  p=(1-t)*q['entry'][inside]+t*q['exit'][inside];ff,_=s.level_gradient(p);assert ff.max()<0
 # Reciprocity uses valid transmitted paths, not fabricated TIR substitutes.
 d,b,_=refract(-q['exit_direction'][valid],q['exit_normal'][valid],1.,1.5)
 assert not b.any() and abs(d+q['internal_direction'][valid]).max()<1e-10
 d,b,_=refract(-q['internal_direction'][inside],-q['entry_normal'][inside],1.5,1.)
 assert not b.any() and abs(d-[0,0,1]).max()<1e-9
 mirror=trace(s,-x,y);same=valid&mirror['complete']
 assert abs(q['X'][same]+mirror['X'][same]).max()<1e-10
 r=np.linspace(0,1-1e-9,4097);rq=trace(s,r,np.zeros_like(r));v=rq['complete'];jac=np.full_like(r,np.nan);jac[v]=np.gradient(rq['X'][v],r[v])
 seam=trace(s,np.array([1.]),np.array([0.]));assert not seam['complete'][0] and np.isnan(seam['X'][0])
 if kap==0:
  p=q['entry'][inside]-np.array(s.center);d=q['internal_direction'][inside];a=np.array([1,1,1/s.depth**2]);exact=-2*np.sum(p*a*d,axis=-1)/np.sum(d*a*d,axis=-1)
  err=np.max(abs(q['exit'][inside]-(q['entry'][inside]+exact[:,None]*d)));assert err<1e-10
 else:err=None
 row=dict(kappa=kap,whole_geometry_rays=int(x.size),inside=int(inside.sum()),valid_primary_transmission=int(valid.sum()),blocked=int((q['blocked']&inside).sum()),unavailable_primary=int((inside&~q['complete']).sum()),exit_residual=float(abs(q['exit_level_residual'][inside]).max()),max_intersection_bracket=float(q['intersection_bracket'][inside].max()),ellipsoid_exact_root_error=err,minimum_transmitted_discriminant=float(q['discriminant'][valid].min()),radial_transmitted_count=int(v.sum()),radial_unavailable=int((~v).sum()),minimum_transmitted_map_derivative=float(np.nanmin(jac)),fold_in_transmitted_region=bool(np.nanmin(jac)<0))
 rows.append(row);np.savez_compressed(out/f'geometry-k{kap:.6f}.npz',x=x,y=y,**q);np.savez_compressed(out/f'radial-k{kap:.6f}.npz',r=r,source_r=rq['X'],complete=rq['complete'],entry=rq['entry'],exit=rq['exit'],entry_normal=rq['entry_normal'],exit_normal=rq['exit_normal'],exit_direction=rq['exit_direction']);print(row,flush=True)
result=dict(classification='GEOMETRY_ONLY_DISCRIMINATOR_NOT_RENDERING_SUCCESS',protocol=protocol,checks=checks,rows=rows,geometry_rays=ray_count,wall_seconds=time.time()-started,images=0,fits=0,core_sha=hashlib.sha256(Path(__file__).with_name('geometric_optics.py').read_bytes()).hexdigest(),limitations='Primary transmission only. TIR/non-downward rays unresolved; no rendered pixels. Frozen optical prior, not inferred Apple geometry. No Fresnel radiance/device/visual transfer claim.')
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
