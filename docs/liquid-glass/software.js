/* CPU implementation of the lab's inverse texture maps. No GPU or DOM capture.
   Equations match lab.js; cross-renderer tests cover lookup and tone behavior. */
'use strict';
window.createSoftwareLens = function(canvas) {
  const W=960,H=400,ctx=canvas.getContext('2d'),clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
  let source, image, base, baseKey='', renders=0;
  const color=new Float64Array(3),tap=new Float64Array(3);
  const ramp=t=>{t=clamp(t,0,1);return t*t*t*(t*(t*6-15)+10);};
  function sample(x,y,out,coordinates) {
    if(coordinates){out[0]=clamp(x/W,0,1);out[1]=clamp(y/H,0,1);out[2]=.2;return;}
    // Texel centers and clamp-to-edge match LINEAR texture sampling in WebGL.
    x=clamp(x-.5,0,W-1);y=clamp(y-.5,0,H-1);
    const x0=Math.floor(x),y0=Math.floor(y),tx=x-x0,ty=y-y0;
    const a=(y0*W+x0)*4,b=(y0*W+Math.min(x0+1,W-1))*4;
    const c=(Math.min(y0+1,H-1)*W+x0)*4,d=(Math.min(y0+1,H-1)*W+Math.min(x0+1,W-1))*4;
    for(let k=0;k<3;k++)out[k]=((source[a+k]*(1-tx)+source[b+k]*tx)*(1-ty)+(source[c+k]*(1-tx)+source[d+k]*tx)*ty)/255;
  }
  function scatter(x,y,blur,coordinates) {
    sample(x,y,color,coordinates);
    if(blur<.01)return;
    for(let k=0;k<3;k++)color[k]*=.25;
    for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++){
      if(!dx&&!dy)continue;
      sample(x+dx*blur,y+dy*blur,tap,coordinates);
      const weight=dx&&dy?.0625:.125;
      for(let k=0;k<3;k++)color[k]+=tap[k]*weight;
    }
  }
  function draw({g,v,viewWidth,model,profile,coordinates,lightInk,sourceOnly,held}) {
    const width=Math.min(480,Math.max(1,Math.round(canvas.clientWidth))),height=Math.round(width*H/viewWidth);
    if(canvas.width!==width||canvas.height!==height){canvas.width=width;canvas.height=height;image=ctx.createImageData(width,height);baseKey='';}
    if(!image)image=ctx.createImageData(width,height);
    const left=(W-viewWidth)/2,dx=viewWidth/width,dy=H/height,key=[width,height,viewWidth,coordinates].join('/');
    if(key!==baseKey){
      base=new Uint8ClampedArray(width*height*4);
      for(let y=0;y<height;y++)for(let x=0;x<width;x++){
        sample(left+(x+.5)*dx,(y+.5)*dy,color,coordinates);
        const i=(y*width+x)*4;for(let k=0;k<3;k++)base[i+k]=color[k]*255;base[i+3]=255;
      }
      baseKey=key;
    }
    image.data.set(base);
    const identity=!v.depth&&!v.blur&&!v.tint&&!v.trim&&!v.shine&&!v.shadow;
    if(!sourceOnly&&!identity){
      const hw=g.w/2,hh=g.h/2,r=g.r,aa=Math.max(dx,dy),depth=v.depth*g.z;
      const band=Math.min(r,Math.min(hw,hh)*v.band)*(1-.25*held);
      function sd(x,y){x=Math.abs(x)-hw+r;y=Math.abs(y)-hh+r;return Math.hypot(Math.max(x,0),Math.max(y,0))+Math.min(Math.max(x,y),0)-r;}
      const capHeight=g.referenceLength*(model===1?v.thickness:.3)*depth,capGap=g.referenceLength*v.gap;
      // The static exterior is copied; expensive optical work is limited to the lens.
      // Shadow tails below half an 8-bit level need no per-frame evaluation.
      const pad=45,x0=clamp(Math.floor((g.x-hw-pad-left)/dx),0,width),x1=clamp(Math.ceil((g.x+hw+pad-left)/dx),0,width);
      const y0=clamp(Math.floor((g.y-hh-pad)/dy),0,height),y1=clamp(Math.ceil((g.y+hh+pad)/dy),0,height);
      for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){
        const px=left+(x+.5)*dx,py=(y+.5)*dy,lx=px-g.x,ly=py-g.y,d=sd(lx,ly);
        const t=clamp((d+aa*.5)/aa,0,1),cover=1-t*t*(3-2*t);
        const sh=Math.exp(-Math.pow(Math.max(sd(lx,ly-7),0)/13,2))*v.shadow*(1-cover),i=(y*width+x)*4;
        if(cover<=0){for(let k=0;k<3;k++)image.data[i+k]=base[i+k]*(1-sh);continue;}
        const u=clamp(-d/band,0,1),vv=1-u,qx=Math.abs(lx)-hw+r,qy=Math.abs(ly)-hh+r;
        const ax=Math.max(qx,0),ay=Math.max(qy,0),len=Math.hypot(ax,ay);
        const ex=len>0?ax/len*Math.sign(lx):qx>qy?Math.sign(lx):0;
        const ey=len>0?ay/len*Math.sign(ly):qx>qy?0:Math.sign(ly);
        let nx=0,ny=0,nz=1,z=0;
        if(capHeight&&(model===1||v.shine)){
          const f2=1-vv*vv*vv,F=Math.sqrt(f2),C=1-.036*(lx*lx/(hw*hw)+ly*ly/(hh*hh));
          nx=capHeight*(1.5*vv*vv/band*C*ex+f2*.072*lx/(hw*hw));
          ny=capHeight*(1.5*vv*vv/band*C*ey+f2*.072*ly/(hh*hh));
          const n=Math.hypot(nx,ny,F);nx/=n;ny/=n;nz=F/n;z=capHeight*F*C;
        }
        let ox=0,oy=0;
        if(depth){
          if(model===1){
            if(capHeight&&v.ior!==1){
              const eta=1/v.ior,k=1-eta*eta*(1-nz*nz),c=-eta*nz+Math.sqrt(k);
              const rx=-c*nx,ry=-c*ny,rz=-eta-c*nz,exitK=1-v.ior*v.ior*(1-rz*rz);
              const exitZ=-Math.sqrt(exitK),travel=z/-rz,travelGap=capGap/-exitZ;
              ox=rx*travel+v.ior*rx*travelGap;oy=ry*travel+v.ior*ry*travelGap;
            }
          }else{
            const R=vv*vv*vv*(1+3*u),H=256/27*u*vv*vv*vv;
            const shape=profile===0?16*u*u*vv*vv:profile===1?R:v.rim*R+(1-v.rim)*H;
            const shoulder=band*v.edge*depth*shape,core=v.core*depth*ramp(u);
            ox=-ex*shoulder-lx*core;oy=-ey*shoulder-ly*core;
          }
        }
        scatter(px+ox,py+oy,v.blur,coordinates);
        let lighting=0;
        if(v.shine){
          const ex=sd(lx+.3,ly)-sd(lx-.3,ly)+.000001,ey=sd(lx,ly+.3)-sd(lx,ly-.3)+.000001;
          const directional=Math.pow(Math.abs((ex+ey)/Math.hypot(ex,ey)/Math.SQRT2),5);
          const rim=Math.exp(-Math.pow(Math.max(0,-d)/1.6,2)),fresnel=.02+.98*Math.pow(1-nz,5);
          lighting=v.shine*(rim*(.32*directional-.065*(1-directional))+fresnel*.17);
        }
        for(let k=0;k<3;k++){
          let c=clamp(color[k]*(1-v.tint)+v.tint+lighting,0,1);
          c=lightInk?c-v.trim*Math.pow(c,v.weight):c+v.trim*Math.pow(1-c,v.weight);
          image.data[i+k]=base[i+k]*(1-sh)*(1-cover)+c*255*cover;
        }
      }
    }
    ctx.putImageData(image,0,0);renders++;
  }
  return {setSource(c){source=c.getContext('2d').getImageData(0,0,W,H).data;baseKey='';},draw,
    info:()=>({width:canvas.width,height:canvas.height,renders}),
    pixels(points,viewWidth){const left=(W-viewWidth)/2;return points.map(([x,y])=>{
      const i=4*(clamp(Math.floor(y/H*canvas.height),0,canvas.height-1)*canvas.width+clamp(Math.floor((x-left)/viewWidth*canvas.width),0,canvas.width-1));return [...image.data.slice(i,i+4)];
    });}};
};
