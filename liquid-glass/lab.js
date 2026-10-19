/* Localica optical experiment. See optics.md; no production/iOS parity implied. */
'use strict';
(() => {
  const $ = id => document.getElementById(id), W = 960, H = 400;
  const canvas = $('scene'), fallback = $('fallback'), foreground = $('foreground');
  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
  const mix = (a, b, t) => a + (b - a) * t;
  const defaults = {depth:1, band:.55, blur:0, tint:.02, trim:.12, weight:1.6, shine:.35, shadow:.1, damping:8};
  const values = () => Object.fromEntries(Object.keys(defaults).map(k => [k, +$(k).value]));
  const textureCanvas = document.createElement('canvas');
  textureCanvas.width = W; textureCanvas.height = H;
  const bg = textureCanvas.getContext('2d');
  const media = matchMedia('(prefers-reduced-motion: reduce)');
  $('reduce').checked = media.matches;
  let gl, program, texture, vao, contextControl, uniforms = {}, lost = false, disposed = false;
  let frame = 0, last = 0, visible = true, frames = 0, uploads = 0, drawTimes = [];
  let state = {x:480, v:0, target:480, m:0, mv:0, strain:0, sv:0, z:0, zv:0};
  let expanded = false, pointer = null, keyHeld = false, pressure = 0, previousV = 0;
  let lastGeometry, wall = 0, viewWidth = W;
  const viewLeft = () => (W-viewWidth)/2;
  const bounds = width => [viewLeft()+32+width/2, W-viewLeft()-32-width/2];
  const vertex = `#version 300 es
  in vec2 position;
  void main(){gl_Position=vec4(position,0.,1.);}`;
  const fragment = `#version 300 es
  precision highp float;
  uniform sampler2D backdrop;
  uniform vec2 resolution, center, halfSize;
  uniform float viewWidth;
  uniform float radius, depth, band, blur, tint, trim, weight, shine, shadow, held;
  uniform int model, coordinateMode, lightInk, sourceOnly;
  out vec4 frag;
  const vec2 extent=vec2(960.,400.);
  float sd(vec2 p){
    vec2 q=abs(p)-halfSize+radius;
    return length(max(q,0.))+min(max(q.x,q.y),0.)-radius;
  }
  float ramp(float t){t=clamp(t,0.,1.);return t*t*t*(t*(t*6.-15.)+10.);}
  float width(){return max(2., min(radius,min(halfSize.x,halfSize.y)*band)*(1.-.25*held));}
  float heightAt(vec2 p){
    float s=ramp(-sd(p)/width());
    vec2 q=p/halfSize;
    return 14.*depth*s*(.92+.08*(1.-.45*dot(q,q)));
  }
  vec3 normalAt(vec2 p){
    float e=.25;
    vec2 grad=vec2(heightAt(p+vec2(e,0))-heightAt(p-vec2(e,0)),
                   heightAt(p+vec2(0,e))-heightAt(p-vec2(0,e)))/(2.*e);
    return normalize(vec3(-grad,1.));
  }
  vec2 displacement(vec2 p,vec3 n){
    if(depth==0.)return vec2(0.);
    if(model==1){
      vec3 ray=refract(vec3(0,0,-1),n,1./1.33);
      vec3 outRay=refract(ray,vec3(0,0,1),1.33);
      return ray.xy*heightAt(p)/max(.08,-ray.z)+outRay.xy*(5.*depth)/max(.08,-outRay.z);
    }
    float d=max(0.,-sd(p)),b=width(),u=clamp(d/b,0.,1.);
    vec2 q=abs(p)-halfSize+radius;
    // Exact boundary normal is used only in a band before the SDF medial axis.
    vec2 outside=max(q,0.);
    vec2 normal=length(outside)>.0001 ? normalize(outside)*sign(p) :
      (q.x>q.y ? vec2(sign(p.x),0) : vec2(0,sign(p.y)));
    float shoulder=16.*u*u*(1.-u)*(1.-u);
    return -normal*(b*.10*depth*shoulder)-p*(.012*depth*ramp(d/b));
  }
  vec3 source(vec2 p){
    if(coordinateMode==1)return vec3(clamp(p/extent,0.,1.),.2);
    return texture(backdrop,vec2(p.x/extent.x,1.-p.y/extent.y)).rgb;
  }
  vec3 scattering(vec2 p){
    if(blur<.01)return source(p);
    // Fixed 9-tap positive kernel; a study radius, not a Gaussian sigma.
    vec3 c=source(p)*.25;
    c+=(source(p+vec2(blur,0))+source(p-vec2(blur,0))+
        source(p+vec2(0,blur))+source(p-vec2(0,blur)))*.125;
    c+=(source(p+vec2(blur))+source(p-vec2(blur))+
        source(p+vec2(blur,-blur))+source(p+vec2(-blur,blur)))*.0625;
    return c;
  }
  vec3 tone(vec3 c){
    return lightInk==1 ? c-trim*pow(c,vec3(weight)) :
      c+trim*pow(1.-c,vec3(weight));
  }
  void main(){
    vec2 p=vec2(gl_FragCoord.x/resolution.x,1.-gl_FragCoord.y/resolution.y)*extent;
    p.x=(p.x-480.)*viewWidth/960.+480.;
    vec3 original=source(p);
    if(sourceOnly==1){frag=vec4(original,1);return;}
    vec2 local=p-center; float d=sd(local);
    float aa=max(viewWidth/resolution.x,extent.y/resolution.y);
    float cover=1.-smoothstep(-aa*.5,aa*.5,d);
    float sh=exp(-pow(max(sd(local-vec2(0,7)),0.)/13.,2.))*shadow*(1.-cover);
    vec3 outside=original*(1.-sh);
    if(cover<=0.){frag=vec4(outside,1);return;}
    vec3 n=normalAt(local);
    vec3 c=mix(scattering(p+displacement(local,n)),vec3(1),tint);
    // Synthetic two-sided studio illumination, no claimed recovered environment.
    vec2 e=vec2(.3,0);
    vec2 edgeN=normalize(vec2(sd(local+e)-sd(local-e),
                             sd(local+e.yx)-sd(local-e.yx))+vec2(.000001));
    float directional=pow(abs(dot(edgeN,normalize(vec2(1,1)))),5.);
    float rim=exp(-pow(max(0.,-d)/1.6,2.));
    float fresnel=.02+.98*pow(1.-n.z,5.);
    float lighting=shine*(rim*(.32*directional-.065*(1.-directional))+fresnel*.17);
    c=clamp(c+lighting,0.,1.);
    // Compress AFTER illumination: reflected material obeys the chosen endpoint.
    c=tone(c);
    frag=vec4(mix(outside,c,cover),1);
  }`;

  function compile(type, source) {
    const shader=gl.createShader(type); gl.shaderSource(shader,source);gl.compileShader(shader);
    if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS)){
      const log=gl.getShaderInfoLog(shader);gl.deleteShader(shader);throw Error(log);
    }
    return shader;
  }
  function initGL() {
    if(new URLSearchParams(location.search).has('fallback'))return false;
    try {
      gl=canvas.getContext('webgl2',{alpha:false,antialias:false,depth:false,stencil:false,preserveDrawingBuffer:false,powerPreference:'low-power'});
      if(!gl)return false;
      contextControl=gl.getExtension('WEBGL_lose_context');
      const vs=compile(gl.VERTEX_SHADER,vertex), fs=compile(gl.FRAGMENT_SHADER,fragment);
      program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
      gl.deleteShader(vs);gl.deleteShader(fs);
      if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program));
      gl.useProgram(program);vao=gl.createVertexArray();gl.bindVertexArray(vao);
      const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
      // One oversized triangle: no interpolated per-triangle optical fields/seams.
      gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,3,-1,-1,3]),gl.STATIC_DRAW);
      const a=gl.getAttribLocation(program,'position');gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,2,gl.FLOAT,false,0,0);
      for(const name of ['backdrop','resolution','viewWidth','center','halfSize','radius','depth','band','blur','tint','trim','weight','shine','shadow','held','model','coordinateMode','lightInk','sourceOnly'])uniforms[name]=gl.getUniformLocation(program,name);
      texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
      lost=false;fallback.hidden=true;$('status').textContent='WebGL2 · background refraction';return true;
    }catch(error){console.warn('Glass lab fallback:',error.message);return false;}
  }
  let gpu=initGL();
  function upload(){if(gpu&&!lost){gl.bindTexture(gl.TEXTURE_2D,texture);gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,textureCanvas);uploads++;}}
  function background(){
    bg.clearRect(0,0,W,H);const mode=$('background').value;
    if(mode==='lines'){
      // Original static artwork; generated only when the backdrop is selected.
      const g=bg.createLinearGradient(0,0,W,H*.3);
      [[0,'#172a48'],[.22,'#22676f'],[.45,'#62679e'],[.67,'#ac777d'],[.85,'#bd936b'],[1,'#4d567e']].forEach(([p,c])=>g.addColorStop(p,c));
      bg.fillStyle=g;bg.fillRect(0,0,W,H);
      const shade=bg.createLinearGradient(0,0,0,H);shade.addColorStop(0,'#080e2600');shade.addColorStop(1,'#080e2670');bg.fillStyle=shade;bg.fillRect(0,0,W,H);
      // Fine vertical lines expose horizontal refraction without an animated scene.
      for(let x=12;x<W;x+=18){bg.fillStyle='#f2e5ff38';bg.fillRect(x,0,1.5,H);bg.fillStyle='#08142a28';bg.fillRect(x+2,0,1,H);}
    }else if(mode==='grid'||mode==='coordinates'){
      const colors=['#ee9a8b','#eacb79','#9acbbb','#89a7cd','#c4a4cf','#f1c9b2'];
      for(let y=0;y<H;y+=40)for(let x=0;x<W;x+=40){bg.fillStyle=colors[((x/40)+(y/40)*3)%colors.length];bg.fillRect(x,y,40,40);}
      bg.strokeStyle='#24354740';bg.lineWidth=1;for(let x=0;x<=W;x+=40){bg.beginPath();bg.moveTo(x,0);bg.lineTo(x,H);bg.stroke();}for(let y=0;y<=H;y+=40){bg.beginPath();bg.moveTo(0,y);bg.lineTo(W,y);bg.stroke();}
      bg.font='11px ui-monospace,monospace';bg.fillStyle='#243547a0';for(let x=20;x<W;x+=80)bg.fillText(String(x),x,24);
    }else if(mode==='text'){
      bg.fillStyle='#f5ede3';bg.fillRect(0,0,W,H);bg.fillStyle='#273648';
      for(let y=20;y<H;y+=40){bg.fillRect(0,y,W,2);bg.font='18px system-ui';bg.fillText('BACKGROUND  /  0123456789  /  Shape bends; type should stay readable.',22,y+24);}
    }else{
      const g=bg.createLinearGradient(0,0,W,H);g.addColorStop(0,'#273a65');g.addColorStop(.35,'#d19b9f');g.addColorStop(.65,'#efd39c');g.addColorStop(1,'#77aeac');bg.fillStyle=g;bg.fillRect(0,0,W,H);
      bg.strokeStyle='#ffffee80';bg.lineWidth=18;for(let y=-100;y<500;y+=65){bg.beginPath();bg.moveTo(0,y);bg.bezierCurveTo(320,y+240,650,y-180,960,y+70);bg.stroke();}
    }
    upload();wake();
  }
  function dimensions(){
    const shape=$('shape').value;
    const base=shape==='circle'?[106,106]:shape==='panel'?[310,180]:[196,92];
    return [mix(base[0],390,state.m),mix(base[1],238,state.m)];
  }
  function geometry(){
    const [w,h]=dimensions();
    const z=clamp(state.z,-.5,.35),q=clamp(state.strain,-.22,.18);
    const sx=Math.exp(q-z/2),sy=Math.exp(-q-z/2),width=w*sx,height=h*sy;
    const [lo,hi]=bounds(width);
    const cx=wall<0?lo:wall>0?hi:clamp(state.x,lo,hi);
    const corner=mix($('shape').value==='panel'?30:h/2,32,state.m)*Math.min(sx,sy);
    return {x:cx,y:200,w:width,h:height,r:Math.min(corner,width/2,height/2),z:Math.exp(z),volume:sx*sy*Math.exp(z)};
  }
  function drawFallback(g){
    fallback.hidden=false;fallback.width=Math.round(viewWidth);fallback.height=H;const ctx=fallback.getContext('2d');ctx.translate(-viewLeft(),0);ctx.drawImage(textureCanvas,0,0,W,H);
    if(!$('source').checked){ctx.save();ctx.fillStyle=$('ink').value==='light'?'#17213bc9':'#ffffffb0';ctx.strokeStyle='#ffffff';ctx.lineWidth=1.5;ctx.beginPath();ctx.roundRect(g.x-g.w/2,g.y-g.h/2,g.w,g.h,g.r);ctx.fill();ctx.stroke();ctx.restore();}
    $('status').textContent='2D fallback · motion only, no refraction';
  }
  function draw(){
    if(disposed)return;
    const start=performance.now(),g=geometry(),v=values();lastGeometry=g;
    if(gpu&&!lost){
      // Bound fragment work independently of high-DPI screens and window size.
      const scale=Math.min(devicePixelRatio||1,1.5),width=Math.min(viewWidth,Math.max(1,Math.round(canvas.clientWidth*scale))),height=Math.round(width*H/viewWidth);
      if(canvas.width!==width||canvas.height!==height){canvas.width=width;canvas.height=height;}
      gl.viewport(0,0,canvas.width,canvas.height);gl.useProgram(program);gl.bindVertexArray(vao);gl.bindTexture(gl.TEXTURE_2D,texture);
      gl.uniform1f(uniforms.viewWidth,viewWidth);gl.uniform1i(uniforms.backdrop,0);gl.uniform2f(uniforms.resolution,canvas.width,canvas.height);
      gl.uniform2f(uniforms.center,g.x,g.y);gl.uniform2f(uniforms.halfSize,g.w/2,g.h/2);
      gl.uniform1f(uniforms.radius,g.r);
      for(const k of ['band','blur','tint','trim','weight','shine','shadow'])gl.uniform1f(uniforms[k],v[k]);
      gl.uniform1f(uniforms.depth,v.depth*g.z);gl.uniform1f(uniforms.held,clamp(-state.z/.3,0,1));
      gl.uniform1i(uniforms.model,+$('model').value);gl.uniform1i(uniforms.coordinateMode,$('background').value==='coordinates'?1:0);
      gl.uniform1i(uniforms.lightInk,$('ink').value==='light'?1:0);gl.uniform1i(uniforms.sourceOnly,$('source').checked?1:0);
      gl.drawArrays(gl.TRIANGLES,0,3);
    }else drawFallback(g);
    const show=!$('hideLabel').checked&&!$('source').checked;
    foreground.style.visibility=show?'visible':'hidden';foreground.style.left=`${(g.x-viewLeft())/viewWidth*100}%`;foreground.style.top=`${g.y/H*100}%`;
    foreground.style.color=$('ink').value==='light'?'#fff':'#182330';
    $('compactLabel').style.opacity=1-state.m;$('panelLabel').style.opacity=state.m;
    canvas.setAttribute('aria-valuenow',String(Math.round(clamp((g.x-viewLeft()-g.w/2-32)/(viewWidth-64-g.w)*100,0,100))));
    $('readout').value=`depth ×${g.z.toFixed(2)} · volume ${g.volume.toFixed(3)} · ${pointer||keyHeld?'held':unsettled()?'settling':'rest'}`;
    frames++;drawTimes.push(performance.now()-start);if(drawTimes.length>240)drawTimes.shift();
  }
  function wake(){if(!frame&&visible&&!document.hidden&&!disposed){last=performance.now();frame=requestAnimationFrame(tick);}}
  function step(dt){
    const held=!!pointer||keyHeld;
    if($('reduce').checked){state.x=state.target;state.v=state.mv=state.sv=state.zv=0;state.m=expanded?1:0;state.strain=state.z=0;return;}
    while(dt>0){
      const d=Math.min(dt,1/240);dt-=d;
      state.mv+=(145*((expanded?1:0)-state.m)-24.1*state.mv)*d;state.m=clamp(state.m+state.mv*d,0,1);
      if(!pointer){state.v+=(260*(state.target-state.x)-32.3*state.v)*d;state.x+=state.v*d;}
      const braking=clamp((Math.abs(state.v)-Math.abs(previousV))*.0004,-.12,.12);previousV=state.v;
      state.sv+=braking;
      const damping=held?2*Math.sqrt(220):+$('damping').value;
      state.sv+=(220*(pressure-state.strain)-damping*state.sv)*d;state.strain+=state.sv*d;
      state.zv+=(220*((held?-.3:0)-state.z)-damping*state.zv)*d;state.z+=state.zv*d;
    }
  }
  function unsettled(){return !$('reduce').checked&&(Math.abs(state.x-state.target)>.02||Math.abs(state.v)>.03||Math.abs(state.m-(expanded?1:0))>.0001||Math.abs(state.mv)>.001||Math.abs(state.strain-pressure)>.0001||Math.abs(state.sv)>.001||Math.abs(state.z-((pointer||keyHeld)?-.3:0))>.0001||Math.abs(state.zv)>.001);}
  function tick(now){frame=0;step(Math.min((now-last)/1000,.04));last=now;draw();if(unsettled())wake();}
  function point(e){const r=canvas.getBoundingClientRect();return {x:viewLeft()+(e.clientX-r.left)*viewWidth/r.width,y:(e.clientY-r.top)*H/r.height};}
  function hit(p,g){const x=Math.abs(p.x-g.x)-g.w/2+g.r,y=Math.abs(p.y-g.y)-g.h/2+g.r;return Math.hypot(Math.max(x,0),Math.max(y,0))+Math.min(Math.max(x,y),0)<=g.r+8;}
  canvas.addEventListener('pointerdown',e=>{
    if(pointer||e.button!==0)return;const p=point(e),g=geometry();if(!hit(p,g))return;
    canvas.focus({preventScroll:true});canvas.setPointerCapture(e.pointerId);
    pointer={id:e.pointerId,offset:p.x-g.x,lastTime:e.timeStamp,origin:g.x};wall=0;state.x=g.x;state.target=g.x;state.v=0;previousV=0;wake();
  });
  canvas.addEventListener('pointermove',e=>{
    if(!pointer||e.pointerId!==pointer.id)return;
    const p=point(e),g=geometry(),raw=p.x-pointer.offset,[lo,hi]=bounds(g.w);
    const next=clamp(raw,lo,hi);state.v=clamp((next-state.x)/Math.max(.008,(e.timeStamp-pointer.lastTime)/1000),-1800,1800);
    wall=raw<=lo?-1:raw>=hi?1:0;state.x=state.target=next;pressure=-.18*(1-Math.exp(-Math.abs(raw-next)/65));pointer.lastTime=e.timeStamp;wake();
  });
  function release(cancel=false){
    if(!pointer&&!keyHeld)return;
    const g=geometry(),origin=pointer?.origin;pointer=null;keyHeld=false;pressure=0;
    if(cancel){wall=0;state.target=origin??state.x;state.v=0;}else state.target=clamp(state.x+state.v/10,...bounds(g.w));
    wake();
  }
  canvas.addEventListener('pointerup',()=>release());canvas.addEventListener('pointercancel',()=>release(true));canvas.addEventListener('lostpointercapture',()=>release(true));
  canvas.addEventListener('keydown',e=>{
    if(e.code==='Space'){e.preventDefault();keyHeld=true;wake();return;}
    if(e.key==='Escape'){release(true);return;}
    const [w]=dimensions(),targets={ArrowLeft:state.target-60,ArrowRight:state.target+60,Home:bounds(w)[0],End:bounds(w)[1]};
    if(e.key in targets){e.preventDefault();state.x=geometry().x;wall=0;state.target=clamp(targets[e.key],...bounds(w));wake();}
  });
  canvas.addEventListener('keyup',e=>{if(e.code==='Space'){e.preventDefault();release();}});
  canvas.addEventListener('blur',()=>{if(keyHeld)release(true);});
  function move(side){const [w]=dimensions();state.x=geometry().x;wall=0;state.target=side==='left'?bounds(w)[0]:bounds(w)[1];wake();}
  $('left').onclick=()=>move('left');$('right').onclick=()=>move('right');
  $('menu').onclick=()=>{expanded=!expanded;$('menu').setAttribute('aria-pressed',String(expanded));$('menu').textContent=expanded?'Collapse surface':'Expand surface';wake();};
  function syncControls(){for(const k of Object.keys(defaults))$(k+'Value').value=Number($(k).value).toFixed(k==='damping'?1:2);}
  for(const k of Object.keys(defaults))$(k).addEventListener('input',()=>{syncControls();wake();});
  for(const k of ['shape','model','ink','reduce','hideLabel','source'])$(k).addEventListener('change',()=>wake());
  $('background').onchange=background;
  $('optics').onclick=()=>{for(const k of ['blur','tint','trim','shine','shadow'])$(k).value=0;syncControls();wake();};
  $('regular').onclick=()=>{$('blur').value=3;$('tint').value=.08;$('trim').value=.2;syncControls();wake();};
  $('reset').onclick=()=>{
    release(true);expanded=false;wall=0;state={x:480,v:0,target:480,m:0,mv:0,strain:0,sv:0,z:0,zv:0};previousV=0;
    for(const[k,v]of Object.entries(defaults))$(k).value=v;
    $('model').value='0';$('shape').value='pill';$('background').value='lines';$('ink').value='light';$('reduce').checked=media.matches;$('hideLabel').checked=$('source').checked=false;
    $('menu').textContent='Expand surface';$('menu').setAttribute('aria-pressed','false');syncControls();background();
  };
  $('save').onclick=()=>{
    draw();const image=document.createElement('canvas');image.width=Math.round(viewWidth);image.height=400;const c=image.getContext('2d');
    c.drawImage(gpu&&!lost?canvas:fallback,0,0,image.width,400);
    if(!$('hideLabel').checked&&!$('source').checked){const g=geometry();c.textAlign='center';c.textBaseline='middle';c.fillStyle=$('ink').value==='light'?'white':'#182330';c.font='600 16px system-ui';c.fillText(expanded?'Expanded panel':'Drag lens',g.x-viewLeft(),g.y);}
    image.toBlob(blob=>{if(!blob)return;const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='localica-glass-study.png';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);});
  };
  media.addEventListener('change',e=>{$('reduce').checked=e.matches;wake();});
  function resize(){
    viewWidth=canvas.clientWidth<520?520:W;
    canvas.style.aspectRatio=viewWidth+'/'+H;
    fallback.style.aspectRatio=viewWidth+'/'+H;
    state.target=clamp(state.target,...bounds(geometry().w));wake();
  }
  new ResizeObserver(resize).observe(canvas);resize();
  new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;if(visible)wake();else if(frame){cancelAnimationFrame(frame);frame=0;}}).observe(canvas);
  document.addEventListener('visibilitychange',()=>{if(document.hidden){if(frame)cancelAnimationFrame(frame);frame=0;release(true);}else wake();});
  canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();lost=true;draw();});
  canvas.addEventListener('webglcontextrestored',()=>{gpu=initGL();upload();wake();});
  window.addEventListener('pagehide',()=>{if(frame)cancelAnimationFrame(frame);frame=0;});
  window.addEventListener('pageshow',wake);
  syncControls();background();draw();
  // Deterministic observability for the public browser regression runner.
  window.glassLab={
    info:()=>({gpu:gpu&&!lost,frames,uploads,scheduled:!!frame,geometry:lastGeometry,renderSize:[canvas.width,canvas.height],viewWidth,state:{...state},drawTimes:[...drawTimes]}),
    configure(options){for(const[k,v]of Object.entries(options)){const e=$(k);if(!e)throw Error(k);if(e.type==='checkbox')e.checked=!!v;else e.value=v;}syncControls();background();draw();},
    settle(){if(frame)cancelAnimationFrame(frame);frame=0;for(let n=0;n<2400&&unsettled();n++)step(1/120);draw();return this.info();},
    advance(ms){if(frame)cancelAnimationFrame(frame);frame=0;for(let t=0;t<ms;t+=1000/120)step(Math.min(1000/120,ms-t)/1000);draw();return this.info();},
    pixels(points){if(!gpu||lost)throw Error('No WebGL');draw();const data=new Uint8Array(canvas.width*canvas.height*4);gl.readPixels(0,0,canvas.width,canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,data);return points.map(([x,y])=>{const i=4*(clamp(Math.floor((H-y)/H*canvas.height),0,canvas.height-1)*canvas.width+clamp(Math.floor((x-viewLeft())/viewWidth*canvas.width),0,canvas.width-1));return [...data.slice(i,i+4)];});},
    pixel(x,y){return this.pixels([[x,y]])[0];},
    lose:()=>contextControl?.loseContext(),
    restore:()=>contextControl?.restoreContext(),
    error:()=>gl?.getError()
  };
})();
