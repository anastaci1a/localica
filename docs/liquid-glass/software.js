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
      const band=Math.max(2,Math.min(r,Math.min(hw,hh)*v.band)*(1-.25*held));
      function sd(x,y){x=Math.abs(x)-hw+r;y=Math.abs(y)-hh+r;return Math.hypot(Math.max(x,0),Math.max(y,0))+Math.min(Math.max(x,y),0)-r;}
      function heightAt(x,y){return 14*depth*ramp(-sd(x,y)/band)*(.92+.08*(1-.45*(x*x/(hw*hw)+y*y/(hh*hh))));}
      // The static exterior is copied; expensive optical work is limited to the lens.
      // Shadow tails below half an 8-bit level need no per-frame evaluation.
      const pad=45,x0=clamp(Math.floor((g.x-hw-pad-left)/dx),0,width),x1=clamp(Math.ceil((g.x+hw+pad-left)/dx),0,width);
      const y0=clamp(Math.floor((g.y-hh-pad)/dy),0,height),y1=clamp(Math.ceil((g.y+hh+pad)/dy),0,height);
      for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){
        const px=left+(x+.5)*dx,py=(y+.5)*dy,lx=px-g.x,ly=py-g.y,d=sd(lx,ly);
        const t=clamp((d+aa*.5)/aa,0,1),cover=1-t*t*(3-2*t);
        const sh=Math.exp(-Math.pow(Math.max(sd(lx,ly-7),0)/13,2))*v.shadow*(1-cover),i=(y*width+x)*4;
        if(cover<=0){for(let k=0;k<3;k++)image.data[i+k]=base[i+k]*(1-sh);continue;}
        let nx=0,ny=0,nz=1;
        if(depth&&(model===1||v.shine)){
          nx=-(heightAt(lx+.25,ly)-heightAt(lx-.25,ly))/.5;
          ny=-(heightAt(lx,ly+.25)-heightAt(lx,ly-.25))/.5;
          const n=Math.hypot(nx,ny,1);nx/=n;ny/=n;nz=1/n;
        }
        let ox=0,oy=0;
        if(depth){
          if(model===1){
            const eta=1/1.33,k=1-eta*eta*(1-nz*nz),c=-eta*nz+Math.sqrt(Math.max(0,k));
            const rx=-c*nx,ry=-c*ny,rz=-eta-c*nz,exitK=1-1.33*1.33*(1-rz*rz);
            const exitZ=-Math.sqrt(Math.max(0,exitK)),travel=heightAt(lx,ly)/Math.max(.08,-rz);
            const gap=exitK<0?0:5*depth/Math.max(.08,-exitZ);
            ox=rx*travel+1.33*rx*gap;oy=ry*travel+1.33*ry*gap;
          }else{
            const u=clamp(-d/band,0,1),qx=Math.abs(lx)-hw+r,qy=Math.abs(ly)-hh+r;
            const ax=Math.max(qx,0),ay=Math.max(qy,0),len=Math.hypot(ax,ay);
            const ex=len>.0001?ax/len*Math.sign(lx):qx>qy?Math.sign(lx):0;
            const ey=len>.0001?ay/len*Math.sign(ly):qx>qy?0:Math.sign(ly);
            const shape=profile===0?16*u*u*(1-u)*(1-u):Math.pow(1-u,3)*(1+3*u);
            const shoulder=band*v.edge*depth*shape,core=v.core*depth*ramp(-d/band);
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
