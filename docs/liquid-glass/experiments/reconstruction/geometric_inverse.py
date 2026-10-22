"""Geometry-only inversion of a diagnostic radial map; not an image renderer.
Usage: python geometric_inverse.py RECORD.json NEW_OUTPUT
"""
import os,time,json,hashlib
from pathlib import Path
import numpy as np
from scipy.integrate import solve_ivp
from geometric_optics import trace_flat_front_orthographic,RadialCurvatureBack
import argparse
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('record');ap.add_argument('output');ap.add_argument('--distance',type=float,default=2.);ap.add_argument('--index',type=float,default=1.5);ap.add_argument('--seconds',type=float,default=900.);cfg=ap.parse_args()
if cfg.index<=1 or cfg.seconds<=0:raise ValueError('Index>1 and positive wall budget required')
os.umask(2);D=Path(cfg.output);D.mkdir(parents=True,exist_ok=False);start=time.time();deadline=start+cfg.seconds;calls=0
record=json.loads(Path(cfg.record).read_text());v=record['recipe']['parameters'];A,b,p,k,pow_end=[v[n] for n in ['amplitude','band','power','width','inner_power']];core=1-(1+k)*b/2;index=cfg.index;background=cfg.distance;critical_s=1/np.sqrt(index*index-1);gap_bound=background-critical_s*(1-core);q_bound=A/gap_bound;assert gap_bound>0 and q_bound<np.sqrt(index*index-1);assert p/b>critical_s/gap_bound
(D/'protocol-FIRST.json').write_text(json.dumps(dict(start=start,deadline=deadline,source_recipe_sha256=hashlib.sha256(json.dumps(record,sort_keys=True).encode()).hexdigest(),index=index,background_distance=background,core=core,rhs_limit=3000,geometry_ray_limit=50000,resolutions=[257,513,1025],image_renders=0,fits=0,global_branch=dict(gap_lower_bound=gap_bound,q_upper_bound=q_bound,physical_q_critical=float(np.sqrt(index*index-1)),concavity_sufficient_log_a_slope=p/b,opposing_slope_upper=critical_s/gap_bound)),indent=2)+'\n')
def displacement(r):
 r=np.asarray(r,float);d=1-r;t=np.clip((d-b/2)/(k*b/2),0,1-1e-14);u=np.maximum(1-d/b,0);initial=A*u**p;initial_d=A*p/b*u**(p-1)
 tail=A*.5**p*np.exp(pow_end*np.log1p(-t)+(pow_end-p*k)*t+.5*(pow_end-p*k*k)*t*t);tail_d=tail*(pow_end/(1-t)-(pow_end-p*k)-(pow_end-p*k*k)*t)/(k*b/2)
 return np.where(r<=core,0,np.where(d<=b/2,initial,tail)),np.where(r<=core,0,np.where(d<=b/2,initial_d,tail_d))
def rhs(r,h):
 global calls;calls+=1
 if calls>3000 or time.time()>deadline:raise RuntimeError('Declared budget reached')
 a,_=displacement(r);q=a/(background-h[0]);assert q<np.sqrt(index*index-1) and background>h[0];return [-float(q/(index*np.sqrt(1+q*q)-1))]
sol=[]
for tol in [1e-9,1e-11]:
 z=solve_ivp(rhs,[1,core],[0.],rtol=tol,atol=tol*.01,dense_output=True);assert z.success;sol.append(z)
probe=np.linspace(core,1,1025);ode_error=float(abs(sol[0].sol(probe)-sol[1].sol(probe)).max());assert ode_error<1e-8
(D/'ode-checkpoint.json').write_text(json.dumps(dict(rhs_calls=calls,ode_success=True,two_tolerance_height_difference=ode_error,budget_remaining_seconds=deadline-time.time()),indent=2)+'\n')
rows=[];previous=None;ray_count=0
for count in [257,513,1025]:
 if time.time()>deadline:raise RuntimeError('Budget reached')
 nodes=np.linspace(core,1,count);h=sol[1].sol(nodes)[0];a,ap=displacement(nodes);g=background-h;q=a/g;s=q/(index*np.sqrt(1+q*q)-1);qp=ap/g-a*s/(g*g);ds=(index/np.sqrt(1+q*q)-1)/(index*np.sqrt(1+q*q)-1)**2;curvature=ds*qp;assert curvature.min()>=0
 surface=RadialCurvatureBack(nodes,curvature);assert surface.slope_bound<critical_s and background>surface.maximum_height
 rr=np.linspace(0,1-1e-9,16385);rays=trace_flat_front_orthographic(surface,rr,np.zeros_like(rr),index,background);ray_count+=len(rr);assert ray_count<=50000 and rays['complete'].all();desired=rr-displacement(rr)[0];err=float(abs(rays['X']-desired).max());jac=np.gradient(rays['X'],rr)
 change=None if previous is None else float(abs(rays['X']-previous).max());row=dict(nodes=count,max_map_error_L=err,change_from_previous_L=change,actual_height_max=surface.maximum_height,actual_slope_bound=surface.slope_bound,curvature_min=float(curvature.min()),minimum_map_jacobian=float(jac.min()),fold_present=bool((jac<0).any()),minimum_Snell_discriminant=float(rays['discriminant'].min()),ray_paths_complete=True)
 np.savez_compressed(D/f'geometry-{count}.npz',nodes=nodes,curvature=curvature,r=rr,source_r=rays['X'],desired_source_r=desired,height=surface.height_gradient(rr,np.zeros_like(rr))[0],outward_normal=rays['outward_normal'],direction=rays['exit_direction']);rows.append(row);print(json.dumps(row),flush=True)
 if change is not None and err<1e-5 and change<5e-5:break
 previous=rays['X'].copy()
(D/'result.json').write_text(json.dumps(dict(classification='USEFUL_GEOMETRIC_REALIZABILITY_DIAGNOSTIC_NOT_APPLE_FIDELITY',rows=rows,rhs_calls=calls,geometry_rays=ray_count,wall_seconds=time.time()-start,fits=0,image_renders=0,conclusion='A globally concave back height can realize the historical folded radial map with subcritical transmitted paths. Copying it into geometry preserves the original fold pattern; accuracy requires reference-specific comparison. This neither establishes Apple geometry nor rules out other flat-front shapes/cameras.',surface_definition='Nonnegative continuous piecewise-linear curvature integrated twice; one C2 concave height and its exact polynomial derivative define every normal and path.',limits='ODE/convergence are numerical; indices/background depth fixed hypotheses. Orthographic primary transmission only. No full radiance/dispersion/device-performance claim.'),indent=2)+'\n')
