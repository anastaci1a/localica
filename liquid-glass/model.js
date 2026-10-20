'use strict';
(() => {
/* Executable double-precision reference for model.md. No DOM/GPU dependencies.
   This is a portability oracle, not an Apple shader or production renderer. */
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const S=u=>{u=clamp(u,0,1);return u*u*u*(u*(6*u-15)+10);};
const Sp=u=>u>0&&u<1?30*u*u*(1-u)*(1-u):0;
const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0),norm=v=>Math.hypot(...v),unit=v=>v.map(x=>x/norm(v));
function boundary(p,h,r){
  const q=p.map((v,i)=>Math.abs(v)-h[i]+r),a=q.map(v=>Math.max(v,0)),len=norm(a);
  const sd=len+Math.min(Math.max(...q),0)-r;
  const n=len>0?a.map((v,i)=>v/len*Math.sign(p[i])):q[0]>q[1]?[Math.sign(p[0]),0]:[0,Math.sign(p[1])];
  // Curvature of the parallel distance contour, not the original outline.
  return {sd,n,k:len>0&&(q[0]>0||h[0]===r)&&(q[1]>0||h[1]===r)?1/len:0};
}
function profile(u,kind,rim=.35){
  u=clamp(u,0,1);const v=1-u;
  if(kind===0)return [16*u*u*v*v,32*u*v*(1-2*u)];
  const R=v*v*v*(1+3*u),Rp=-12*u*v*v;
  if(kind===1)return [R,Rp];
  const H=256/27*u*v*v*v,Hp=256/27*v*v*(1-4*u);
  return [rim*R+(1-rim)*H,rim*Rp+(1-rim)*Hp];
}
function field(p,h,r,{band=.55,edge=.4,core=.012,power=1,kind=2,rim=.35}={}){
  const b=Math.min(r,Math.min(...h)*band),{sd,n,k}=boundary(p,h,r),d=Math.max(0,-sd),u=clamp(d/b,0,1);
  if(sd>0)return {map:p.slice(),J:[1,0,0,1],det:1};
  const [v,vp]=profile(u,kind,rim),a=b*edge*power*v,ap=edge*power*vp,c=core*power,sm=S(u),sp=Sp(u)/b;
  const t=[-n[1],n[0]],map=p.map((x,i)=>(1-c*sm)*x-a*n[i]);
  const J=[];for(let i=0;i<2;i++)for(let j=0;j<2;j++)J.push((i===j?1-c*sm:0)+c*sp*p[i]*n[j]+ap*n[i]*n[j]-a*k*t[i]*t[j]);
  return {map,J,det:J[0]*J[3]-J[1]*J[2],normalScale:1-c*sm+c*sp*dot(p,n)+ap,tangentScale:1-c*sm-a*k,displacement:map.map((x,i)=>x-p[i])};
}
function refract(I,N,ratio){
  const c=-dot(I,N),k=1-ratio*ratio*(1-c*c);
  if(k<0)return null;
  return I.map((v,i)=>ratio*v+(ratio*c-Math.sqrt(k))*N[i]);
}
function cap(p,h,r,{band=.55,height=.3*Math.min(...h),gap=.11*Math.min(...h),ior=1.33}={}){
  const b=Math.min(r,Math.min(...h)*band),{sd,n}=boundary(p,h,r),u=clamp(-sd/b,0,1),v=1-u;
  if(sd>0||height===0||ior===1)return {map:p.slice(),N:[0,0,1],valid:true};
  const f2=1-v*v*v,F=Math.sqrt(f2),C=1-.036*(p[0]*p[0]/h[0]**2+p[1]*p[1]/h[1]**2);
  const gradC=p.map((x,i)=>-.072*x/h[i]**2),z=height*F*C;
  // Multiply (-grad h,1) by F to evaluate the vertical rim analytically.
  const N=unit([0,1].map(i=>height*(1.5*v*v/b*C*n[i]-f2*gradC[i])).concat(F));
  const inside=refract([0,0,-1],N,1/ior),outside=inside&&refract(inside,[0,0,1],ior);
  if(!outside)return {valid:false,reason:'total internal reflection',N,inside};
  const atBase=p.map((x,i)=>x+inside[i]*z/-inside[2]);
  if(boundary(atBase,h,r).sd>1e-9)return {valid:false,reason:'plane exit outside body',N,inside};
  return {map:atBase.map((x,i)=>x+outside[i]*gap/-outside[2]),N,inside,outside,height:z,valid:true};
}
function tone(x,t,p,light=true){return light?x-t*x**p:x+t*(1-x)**p;}
function scales(q,z,p,f){return [Math.exp(q-z/2),Math.exp(-q-z/2-f*p),Math.exp(z+f*p)];}
function spring(x,v,target,k,c,m,dt){
  const y=x-target,a=c/(2*m),w2=k/m,disc=w2-a*a;
  let Y,V;
  if(Math.abs(disc)<1e-10){const b=v+a*y,e=Math.exp(-a*dt);Y=(y+b*dt)*e;V=(v-a*b*dt)*e;}
  else if(disc>0){const w=Math.sqrt(disc),C=Math.cos(w*dt),T=Math.sin(w*dt)/w,e=Math.exp(-a*dt);Y=e*(y*C+(v+a*y)*T);V=e*(v*C-(a*v+w2*y)*T);}
  else{const w=Math.sqrt(-disc),r1=-a+w,r2=-a-w,A=(v-r2*y)/(r1-r2),B=y-A;Y=A*Math.exp(r1*dt)+B*Math.exp(r2*dt);V=r1*A*Math.exp(r1*dt)+r2*B*Math.exp(r2*dt);}
  return [target+Y,V];
}
const api={clamp,S,Sp,dot,norm,unit,boundary,profile,field,refract,cap,tone,scales,spring};
if(typeof module!=='undefined'&&module.exports)module.exports=api;else globalThis.GlassEquations=api;
})();
