const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),M=require('../model.js');
const near=(a,b,e=1e-8)=>assert(Math.abs(a-b)<=e,`${a} != ${b} (tolerance ${e})`);
let seed=222;const random=()=>((seed=(Math.imul(1664525,seed)+1013904223)>>>0)/2**32);
const report={groups:[],measurements:{}};const pass=s=>{report.groups.push(s);console.log('PASS',s);};
const h=[98,46],r=46,b=r*.55;
for(let k=0;k<3;k++){near(M.profile(1,k)[0],0);near(M.profile(1,k)[1],0);}
near(M.profile(0,0)[0],0);near(M.profile(0,1)[0],1);near(M.profile(0,2,.35)[0],.35);
near(M.profile(0,2,.35)[1],(1-.35)*256/27);
const edgeDistances=[0,.25,.5,1,2,4,8];
report.measurements.edgeOffsets=edgeDistances.map(d=>({distance:d,...Object.fromEntries([0,1,2].map(kind=>[kind,M.field([0,-46+d],h,r,{kind,edge:kind===0?.1:.4,core:0}).displacement[1]]))}));
assert(report.measurements.edgeOffsets[3][0]<.1);assert(report.measurements.edgeOffsets[0][2]>3);
pass('Boundary limits: legacy field starts at zero; finite-rim compression has no identity band');
let maxDerivativeError=0,minDefaultDet=Infinity,folds=0;
for(let n=0;n<12000;n++){
  const half=[40+random()*160,30+random()*100],radius=Math.min(...half)*(.4+.6*random()),p=half.map(x=>(random()*2-1)*x);
  const shape=M.boundary(p,half,radius);if(shape.sd>-.01||Math.abs(Math.abs(p[0])-half[0]+radius)<.001||Math.abs(Math.abs(p[1])-half[1]+radius)<.001)continue;
  const kind=n%3,options={kind,band:.3+random()*.7,edge:random()*.6,core:random()*.05,power:.5+random(),rim:random()};
  const exact=M.field(p,half,radius,options),epsilon=1e-4;
  for(let axis=0;axis<2;axis++){
    const pa=p.slice(),pb=p.slice();pa[axis]+=epsilon;pb[axis]-=epsilon;
    const a=M.field(pa,half,radius,options).map,c=M.field(pb,half,radius,options).map;
    for(let row=0;row<2;row++)maxDerivativeError=Math.max(maxDerivativeError,Math.abs((a[row]-c[row])/(2*epsilon)-exact.J[row*2+axis]));
  }
  const base=M.field(p,half,radius);minDefaultDet=Math.min(minDefaultDet,base.det);
  if(M.field(p,half,radius,{power:3,edge:1.5,kind:2}).det<0)folds++;
  const scaled=M.field(p.map(x=>x*3),half.map(x=>x*3),radius*3,options);for(let i=0;i<2;i++)near(scaled.map[i],exact.map[i]*3,1e-8);
  const mirrored=M.field(p.map(x=>-x),half,radius,options);for(let i=0;i<2;i++)near(mirrored.map[i],-exact.map[i]);
}
assert(maxDerivativeError<1e-5);assert(minDefaultDet>0);assert(folds>0);
report.measurements.jacobian={maxDerivativeError,minDefaultDet,extremeFoldSamples:folds};
const straight=M.field([0,-46],h,r,{kind:2,core:0});assert(straight.normalScale>3);near(straight.tangentScale,1);
assert(M.field([0,-46+b/3],h,r,{kind:1,core:0}).normalScale<1);
pass('Analytic Jacobian agrees with independent finite differences; compression, magnification and folds distinguished');
for(let i=0;i<5000;i++){
  const theta=random()*Math.PI/2,ratio=.5+random()*.5,I=[Math.sin(theta),0,-Math.cos(theta)],N=[0,0,1],T=M.refract(I,N,ratio);
  near(M.norm(T),1);near(T[0],ratio*I[0]);assert(T[2]<=0);
  const reverse=M.refract(T.map(x=>-x),N.map(x=>-x),1/ratio);assert(reverse);for(let j=0;j<3;j++)near(reverse[j],-I[j],1e-7);
}
assert.equal(M.refract([Math.sin(Math.PI/3),0,-Math.cos(Math.PI/3)],[0,0,1],1.5),null);
pass('Snell normalization, tangential law, reversible transmission and explicit total-internal-reflection rejection');
let minCapExit=1,maxCapOffset=0;
for(let i=0;i<6000;i++){
  const half=i%3===0?[155,90]:i%3===1?[98,46]:[53,53],radius=i%3===0?30:Math.min(...half),p=half.map(x=>(random()*2-1)*x);if(M.boundary(p,half,radius).sd>0)continue;
  const H=(.05+random()*1.45)*Math.min(...half),G=random()*Math.min(...half),ior=1+random()*.4;
  const a=M.cap(p,half,radius,{height:H,gap:G,ior});assert(a.valid,JSON.stringify({p,H,G,ior,a}));
  // Normal checked against an independently differenced scalar height.
  const scalar=q=>{const u=M.clamp(-M.boundary(q,half,radius).sd/Math.min(radius,Math.min(...half)*.55),0,1);return H*Math.sqrt(1-(1-u)**3)*(1-.036*(q[0]**2/half[0]**2+q[1]**2/half[1]**2));};
  if(M.boundary(p,half,radius).sd<-.01){
    const e=1e-5,g=[0,1].map(j=>{const pa=p.slice(),pb=p.slice();pa[j]+=e;pb[j]-=e;return (scalar(pa)-scalar(pb))/(2*e);});
    const numeric=M.unit([-g[0],-g[1],1]);for(let j=0;j<3;j++)near(numeric[j],a.N[j],1e-5);
    for(let step=1;step<10;step++){const z=a.height*step/10,xy=p.map((x,j)=>x+a.inside[j]*(a.height-z)/-a.inside[2]);assert(M.boundary(xy,half,radius).sd<=0);assert(z<=scalar(xy)+1e-8,'Ray must stay inside the assumed cap');}
  }
  near(M.norm(a.N),1);near(M.norm(a.inside),1);near(M.norm(a.outside),1);minCapExit=Math.min(minCapExit,-a.outside[2]);
  maxCapOffset=Math.max(maxCapOffset,M.norm(a.map.map((v,j)=>v-p[j])));
  const q=M.cap(p.map(x=>x*2),half.map(x=>x*2),radius*2,{height:H*2,gap:G*2,ior});for(let j=0;j<2;j++)near(q.map[j],2*a.map[j],1e-7);
}
const atRim=M.cap([0,-46],h,r);assert(atRim.valid);near(atRim.N[2],0);assert(atRim.map[1]>-40);
near(M.cap([0,-46],h,r,{gap:0}).map[1],-46);
report.measurements.cap={minCapExit,maxCapOffset,outlineLookup:atRim.map};
pass('Analytic cap rim normal, gap-dependent rim refraction, scale covariance and valid plane exits across sampled domain');
for(let i=0;i<5000;i++){
  const t=random()*.5,p=1+random(),x=random(),e=1e-5;
  assert(M.tone(x,t,p,true)<=1-t+1e-12);assert(M.tone(x,t,p,false)>=t-1e-12);
  assert(M.tone(x+e,t,p,true)>=M.tone(x,t,p,true));near(M.tone(x,t,p,false),1-M.tone(1-x,t,p,true));
  const s=M.scales(random()*.4-.2,random()*.7-.35,random()*.3-.15,random());near(s[0]*s[1]*s[2],1);
}
assert(M.scales(-.18,-.3,.18,.5)[2]>M.scales(-.18,-.3,.18,0)[2]);
pass('Tone order/endpoints/inverse symmetry and determinant-one strain for arbitrary directions');
let maxSpringError=0;
for(const c of [0,8,2*Math.sqrt(220),40])for(let i=0;i<100;i++){
  const x=random(),v=random()*4-2,target=random(),dt=random()*.3;
  const a=M.spring(x,v,target,220,c,1,dt),b=M.spring(...M.spring(x,v,target,220,c,1,dt/2),target,220,c,1,dt/2);
  for(let j=0;j<2;j++)maxSpringError=Math.max(maxSpringError,Math.abs(a[j]-b[j]));
  const E=([p,v])=>.5*v*v+110*(p-target)**2;assert(E(a)<=E([x,v])+1e-10);
}
assert(maxSpringError<1e-10);report.measurements.spring={maxCompositionError:maxSpringError};
pass('Closed-form springs compose across frame rates and do not gain energy at a fixed target');
for(const kind of [0,1,2])for(const rim of [0,.35,1])for(let i=0;i<10000;i++){
 const u=i/10000,[p,dp]=M.profile(u,kind,rim);
 const D=kind===0?16*Math.sqrt(3)/9:kind===1?16/9:rim*16/9+(1-rim)*64/27;
 const T=kind===0?64/27:kind===1?256/243:rim*256/243+(1-rim)*1024/729;
 assert(-dp<=D+1e-12);assert(p/(1-u)<=T+1e-12);
}
pass('Conservative nonfolding derivative/curvature bounds cover all profiles and endpoint mixes');
for(const c of require('./model-fixtures.json').cases){
 const a=M.field(c.p,c.half,c.r,c.fieldOptions),b=M.cap(c.p,c.half,c.r,c.capOptions);
 for(let i=0;i<2;i++){near(a.map[i],c.field.map[i]);near(b.map[i],c.cap.map[i]);}
 for(let i=0;i<4;i++)near(a.J[i],c.field.J[i]);for(let i=0;i<3;i++)near(b.N[i],c.cap.N[i]);near(a.det,c.field.det);assert.equal(b.valid,c.cap.valid);
}
pass('Published portability fixtures retain reference coordinates, normals and derivatives');
const out=process.env.LAB_EVIDENCE;if(out){fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'equations-results.json'),JSON.stringify(report,null,2));}
console.log(JSON.stringify(report.measurements,null,2));
