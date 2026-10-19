/* Localica optical experiment. See optics.md; no production/iOS parity implied. */
'use strict';
(() => {
  const $ = id => document.getElementById(id), W = 960, H = 400;
  const canvas = $('scene'), fallback = $('fallback'), foreground = $('foreground');
  const software = createSoftwareLens(fallback);
  let rendererReason = '';
  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
  const mix = (a, b, t) => a + (b - a) * t;
  const defaults = {depth:1, edge:.4, core:.012, band:.55, rim:.35, thickness:.3, gap:.11, ior:1.33, blur:0, tint:0, trim:.04, weight:1.6, shine:.35, shadow:.1, damping:8, redistribution:.5};
  const presetBase={...defaults,model:'0',profile:'2',shape:'pill',ink:'light'};
  delete presetBase.damping;delete presetBase.redistribution;
  const presets={
    media:{name:'Clear media control',values:{...presetBase},note:'Media remains visible with a defined rim and little scattering. Inspired by Apple’s playback controls; values are qualitative, not fitted.',source:'https://www.apple.com/newsroom/videos/2025/autoplay/06/apple-introduces-a-delightful-and-elegant-new-software-design/apple-wwdc25-liquid-glass-details/large_2x.mp4'},
    control:{name:'Transparent active control',values:{...presetBase,profile:'2',edge:.25,core:0,band:.4,trim:0,shine:.2,shadow:.05},note:'No blur, tint or brightness adjustment. Quiet core with localized peripheral bending, following the clear-slider observations. Does not reproduce a complete slider.',source:'https://developer.apple.com/videos/play/wwdc2025/284/?time=1092'},
    regularLight:{name:'Regular · light foreground',values:{...presetBase,blur:3,trim:.28,tint:.02,band:.6},note:'Softens underlying detail and compresses highlights for light text. Apple’s Regular material motivates this treatment; the tone curve is Localica’s approximation.',source:'https://developer.apple.com/videos/play/wwdc2025/219/?time=360'},
    regularDark:{name:'Regular · dark foreground',values:{...presetBase,blur:3,trim:.28,tint:.02,band:.6,ink:'dark'},note:'Softens underlying detail and lifts shadows for dark text. Text polarity is fixed by this preset, not automatically selected from the background.',source:'https://developer.apple.com/videos/play/wwdc2025/219/?time=360'},
    menu:{name:'Expanded menu',values:{...presetBase,shape:'panel',depth:1.2,edge:.5,band:.7,blur:5,trim:.3,tint:.03,shadow:.16},note:'Stronger lensing, scattering and shadow for a larger surface. Apple documents this size-dependent change; these numeric settings remain an approximation.',source:'https://developer.apple.com/videos/play/wwdc2025/219/?time=360'},
    extreme:{name:'Strong edge · diagnostic',values:{...presetBase,edge:1.2,core:0,trim:0,shine:0,shadow:0},note:'Exaggerated rim displacement with a registered center. Image folding is possible. This is a diagnostic range, not an Apple material.',source:'optics.html#evidence-needed-before-claiming-a-closer-match'},
    previous:{name:'Previous lab · baseline',values:{...presetBase,profile:'0',edge:.1,core:.012,tint:.02,trim:.12},note:'The former conservative field, retained for comparison. Its small displacement and zero-power silhouette were not derived from Apple’s outer rim.',source:'optics.html#conclusions-retained-corrected-and-unresolved'}
  };
  for(const[id,preset]of Object.entries(presets))$('preset').add(new Option(preset.name,id));
  const custom=new Option('Custom settings','custom');custom.disabled=true;$('preset').add(custom);
  const values = () => Object.fromEntries(Object.keys(defaults).map(k => [k, +$(k).value]));
  const textureCanvas = document.createElement('canvas');
  textureCanvas.width = W; textureCanvas.height = H;
  const bg = textureCanvas.getContext('2d',{willReadFrequently:true});
  const media = matchMedia('(prefers-reduced-motion: reduce)');
  $('reduce').checked = media.matches;
  let gl, program, texture, vao, contextControl, uniforms = {}, lost = false, disposed = false;
  let frame = 0, last = 0, visible = true, frames = 0, uploads = 0, drawTimes = [];
  let state = {x:480, v:0, target:480, m:0, mv:0, strain:0, sv:0, z:0, zv:0, wallStrain:0, wallVelocity:0};
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
  uniform float radius, depth, edge, core, band, rim, thickness, gap, ior, referenceLength, blur, tint, trim, weight, shine, shadow, held;
  uniform int model, profile, coordinateMode, lightInk, sourceOnly;
  out vec4 frag;
  const vec2 extent=vec2(960.,400.);
  float sd(vec2 p){
    vec2 q=abs(p)-halfSize+radius;
    return length(max(q,0.))+min(max(q.x,q.y),0.)-radius;
  }
  float ramp(float t){t=clamp(t,0.,1.);return t*t*t*(t*(t*6.-15.)+10.);}
  float width(){return min(radius,min(halfSize.x,halfSize.y)*band)*(1.-.25*held);}
  vec2 boundaryNormal(vec2 p){
    vec2 q=abs(p)-halfSize+radius,a=max(q,0.);
    return length(a)>0. ? normalize(a)*sign(p) :
      (q.x>q.y ? vec2(sign(p.x),0) : vec2(0,sign(p.y)));
  }
  float heightAt(vec2 p){
    float v=1.-clamp(-sd(p)/width(),0.,1.);
    vec2 q=p/halfSize;
    return referenceLength*thickness*depth*sqrt(1.-v*v*v)*(1.-.036*dot(q,q));
  }
  vec3 normalAt(vec2 p){
    float H=referenceLength*(model==1?thickness:.3)*depth;
    if(H==0.)return vec3(0,0,1);
    float v=1.-clamp(-sd(p)/width(),0.,1.),f2=1.-v*v*v;
    vec2 q=p/halfSize,gradC=-.072*p/(halfSize*halfSize);
    // Multiply (-gradient(height),1) by sqrt(f2): finite vertical rim.
    return normalize(vec3(H*(1.5*v*v/width()*(1.-.036*dot(q,q))*boundaryNormal(p)-f2*gradC),sqrt(f2)));
  }
  vec2 displacement(vec2 p,vec3 n){
    if(depth==0.)return vec2(0.);
    if(model==1){
      if(thickness==0.||ior==1.)return vec2(0.);
      // 1 <= ior <= 1.4 < sqrt(2): no TIR in this orthographic comparator.
      vec3 ray=refract(vec3(0,0,-1),n,1./ior);
      vec3 outRay=refract(ray,vec3(0,0,1),ior);
      return ray.xy*heightAt(p)/(-ray.z)+outRay.xy*(gap*referenceLength)/(-outRay.z);
    }
    float d=max(0.,-sd(p)),b=width(),u=clamp(d/b,0.,1.),v=1.-u;
    float R=v*v*v*(1.+3.*u),H=(256./27.)*u*v*v*v;
    float shoulder=profile==0 ? 16.*u*u*v*v : profile==1 ? R : mix(H,R,rim);
    return -boundaryNormal(p)*(b*edge*depth*shoulder)-p*(core*depth*ramp(u));
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
    if(new URLSearchParams(location.search).has('fallback')){rendererReason='Software rendering requested by the URL.';return false;}
    try {
      gl=canvas.getContext('webgl2',{alpha:false,antialias:false,depth:false,stencil:false,preserveDrawingBuffer:false,powerPreference:'low-power'});
      if(!gl){rendererReason='This browser did not provide a WebGL2 context.';return false;}
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
      for(const name of ['backdrop','resolution','viewWidth','center','halfSize','radius','depth','edge','core','band','rim','thickness','gap','ior','referenceLength','blur','tint','trim','weight','shine','shadow','held','model','profile','coordinateMode','lightInk','sourceOnly'])uniforms[name]=gl.getUniformLocation(program,name);
      texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
      lost=false;fallback.hidden=true;rendererReason='';$('retry').hidden=true;$('rendererNote').textContent='';$('status').textContent='WebGL2 · background refraction';return true;
    }catch(error){rendererReason='WebGL initialization failed: '+error.message;console.warn('Glass lab software renderer:',error.message);return false;}
  }
  let gpu=initGL();
  function upload(){if(gpu&&!lost){gl.bindTexture(gl.TEXTURE_2D,texture);gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,textureCanvas);uploads++;}}
  function background(){
    bg.clearRect(0,0,W,H);const mode=$('background').value;
    if(mode==='lines'||mode==='crossed'){
      // Original static artwork; generated only when the backdrop is selected.
      const g=bg.createLinearGradient(0,0,W,H*.3);
      [[0,'#172a48'],[.22,'#22676f'],[.45,'#62679e'],[.67,'#ac777d'],[.85,'#bd936b'],[1,'#4d567e']].forEach(([p,c])=>g.addColorStop(p,c));
      bg.fillStyle=g;bg.fillRect(0,0,W,H);
      const shade=bg.createLinearGradient(0,0,0,H);shade.addColorStop(0,'#080e2600');shade.addColorStop(1,'#080e2670');bg.fillStyle=shade;bg.fillRect(0,0,W,H);
      // Fine vertical lines expose horizontal refraction without an animated scene.
      for(let x=12;x<W;x+=18){bg.fillStyle='#f2e5ff38';bg.fillRect(x,0,1.5,H);bg.fillStyle='#08142a28';bg.fillRect(x+2,0,1,H);}
      if(mode==='crossed'){for(let y=12;y<H;y+=18){bg.fillStyle='#f2e5ff38';bg.fillRect(0,y,W,1.5);bg.fillStyle='#08142a28';bg.fillRect(0,y+2,W,1);}}
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
    software.setSource(textureCanvas);upload();wake();
  }
  function dimensions(){
    const shape=$('shape').value;
    const base=shape==='circle'?[106,106]:shape==='panel'?[310,180]:[196,92];
    return [mix(base[0],390,state.m),mix(base[1],238,state.m)];
  }
  function geometry(){
    const [w,h]=dimensions();
    const z=clamp(state.z,-.5,.35),q=clamp(state.strain,-.22,.18);
    // Boundary pressure can redistribute the compressed width into both height
    // and depth. Tangential inertial strain remains in-plane for comparison.
    const transferred=$('wallModel').value==='directional' ? -clamp(state.wallStrain,-.18, .18)*+$('redistribution').value : 0;
    const sx=Math.exp(q-z/2),sy=Math.exp(-q-z/2-transferred),sz=Math.exp(z+transferred),width=w*sx,height=h*sy;
    const [lo,hi]=bounds(width);
    const cx=wall<0?lo:wall>0?hi:clamp(state.x,lo,hi);
    const corner=mix($('shape').value==='panel'?30:h/2,32,state.m)*Math.min(sx,sy);
    return {x:cx,y:200,w:width,h:height,r:Math.min(corner,width/2,height/2),z:sz,volume:sx*sy*sz,wallDepthLog:transferred,referenceLength:Math.min(w,h)/2};
  }
  function drawFallback(g,v){
    fallback.hidden=false;
    software.draw({g,v,viewWidth,model:+$('model').value,profile:+$('profile').value,coordinates:$('background').value==='coordinates',
      lightInk:$('ink').value==='light',sourceOnly:$('source').checked,held:clamp(-state.z/.3,0,1)});
    $('status').textContent='Software · background refraction';
    $('rendererNote').textContent=rendererReason;
    $('retry').hidden=false;
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
      for(const k of ['edge','core','band','rim','thickness','gap','ior','blur','tint','trim','weight','shine','shadow'])gl.uniform1f(uniforms[k],v[k]);
      gl.uniform1f(uniforms.referenceLength,g.referenceLength);gl.uniform1f(uniforms.depth,v.depth*g.z);gl.uniform1f(uniforms.held,clamp(-state.z/.3,0,1));
      gl.uniform1i(uniforms.model,+$('model').value);gl.uniform1i(uniforms.profile,+$('profile').value);gl.uniform1i(uniforms.coordinateMode,$('background').value==='coordinates'?1:0);
      gl.uniform1i(uniforms.lightInk,$('ink').value==='light'?1:0);gl.uniform1i(uniforms.sourceOnly,$('source').checked?1:0);
      gl.drawArrays(gl.TRIANGLES,0,3);
    }else drawFallback(g,v);
    const show=!$('hideLabel').checked&&!$('source').checked;
    foreground.style.visibility=show?'visible':'hidden';foreground.style.left=`${(g.x-viewLeft())/viewWidth*100}%`;foreground.style.top=`${g.y/H*100}%`;
    foreground.style.color=$('ink').value==='light'?'#fff':'#182330';
    $('compactLabel').style.opacity=1-state.m;$('panelLabel').style.opacity=state.m;
    canvas.setAttribute('aria-valuenow',String(Math.round(clamp((g.x-viewLeft()-g.w/2-32)/(viewWidth-64-g.w)*100,0,100))));
    $('readout').value=`depth ×${g.z.toFixed(2)} · scale product ${g.volume.toFixed(3)} · ${pointer||keyHeld?'held':unsettled()?'settling':'rest'}`;
    frames++;drawTimes.push(performance.now()-start);if(drawTimes.length>240)drawTimes.shift();
  }
  function wake(){if(!frame&&visible&&!document.hidden&&!disposed){last=performance.now();frame=requestAnimationFrame(tick);}}
  function step(dt){
    const held=!!pointer||keyHeld;
    if($('reduce').checked){state.x=state.target;state.v=state.mv=state.sv=state.zv=state.wallVelocity=0;state.m=expanded?1:0;state.strain=state.z=state.wallStrain=0;return;}
    while(dt>0){
      const d=Math.min(dt,1/240);dt-=d;
      [state.m,state.mv]=GlassEquations.spring(state.m,state.mv,expanded?1:0,145,24.1,1,d);state.m=clamp(state.m,0,1);
      if(!pointer){[state.x,state.v]=GlassEquations.spring(state.x,state.v,state.target,260,32.3,1,d);}
      const braking=clamp((Math.abs(state.v)-Math.abs(previousV))*.0004,-.12,.12);previousV=state.v;
      state.sv+=braking;
      const damping=held?2*Math.sqrt(220):+$('damping').value;
      [state.strain,state.sv]=GlassEquations.spring(state.strain,state.sv,pressure,220,damping,1,d);
      [state.wallStrain,state.wallVelocity]=GlassEquations.spring(state.wallStrain,state.wallVelocity,pressure,220,damping,1,d);
      [state.z,state.zv]=GlassEquations.spring(state.z,state.zv,held?-.3:0,220,damping,1,d);
    }
  }
  function unsettled(){return !$('reduce').checked&&(Math.abs(state.x-state.target)>.02||Math.abs(state.v)>.03||Math.abs(state.m-(expanded?1:0))>.0001||Math.abs(state.mv)>.001||Math.abs(state.strain-pressure)>.0001||Math.abs(state.sv)>.001||Math.abs(state.z-((pointer||keyHeld)?-.3:0))>.0001||Math.abs(state.zv)>.001||Math.abs(state.wallStrain-pressure)>.0001||Math.abs(state.wallVelocity)>.001);}
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
  function syncControls(){
    for(const k of Object.keys(defaults))$(k+'Value').value=Number($(k).value).toFixed(k==='damping'?1:k==='core'?3:2);
    $('redistribution').disabled=$('wallModel').value!=='directional';
    const cap=$('model').value==='1';for(const k of ['edge','core','profile'])$(k).disabled=cap;
    $('rim').disabled=cap||$('profile').value!=='2';for(const k of ['thickness','gap','ior'])$(k).disabled=!cap;
    $('modelNote').textContent=cap?'Analytic cap normal with Snell entry and planar exit. The rim normal is vertical; a nonzero backdrop gap produces outline displacement. Geometric hypothesis, not an Apple surface.':'Finite rim + compression preserves outline displacement and adds a rising inner band. The older profiles remain comparisons; their magnification and compression differ.';
    const v=values(),kind=+$('profile').value,rho=v.rim;
    const D=kind===0?16*Math.sqrt(3)/9:kind===1?16/9:rho*16/9+(1-rho)*64/27;
    const T=kind===0?64/27:kind===1?256/243:rho*256/243+(1-rho)*1024/729;
    const certified=v.depth*Math.exp(.35+($('wallModel').value==='directional'?.18*v.redistribution:0))*(v.core+v.edge*Math.max(D,T))<1;
    $('opticalWarning').textContent=cap?'Cap settings are not certified against image folds. Zero height is explicitly treated as no lens.':certified?'Within the sufficient local nonfolding bound, including the lab’s maximum depth rebound.':'Outside the sufficient nonfolding bound. Image folds are possible; this does not prove a fold at every point.';
    const match=Object.entries(presets).find(([,p])=>Object.entries(p.values).every(([k,v])=>String($(k).value)===String(v)));
    $('preset').value=match?match[0]:'custom';
    $('presetNote').textContent=match?match[1].note:'Custom parameter combination. The context presets are starting approximations; numeric changes are not Apple calibration.';
    $('presetEvidence').href=match?match[1].source:'optics.html#context-audit--19-october-2026';
  }
  $('preset').onchange=()=>{const preset=presets[$('preset').value];if(!preset)return;for(const[k,v]of Object.entries(preset.values))$(k).value=v;syncControls();wake();};
  const tabs=[...document.querySelectorAll('[role=tab]')];
  function selectTab(tab,focus=false){for(const t of tabs){const active=t===tab;t.setAttribute('aria-selected',String(active));t.tabIndex=active?0:-1;$(t.getAttribute('aria-controls')).hidden=!active;}if(focus)tab.focus();}
  for(const[ i,tab]of tabs.entries()){
    tab.onclick=()=>selectTab(tab);
    tab.onkeydown=e=>{const next={ArrowRight:(i+1)%tabs.length,ArrowLeft:(i+tabs.length-1)%tabs.length,Home:0,End:tabs.length-1}[e.key];if(next!==undefined){e.preventDefault();selectTab(tabs[next],true);}};
  }
  for(const k of Object.keys(defaults))$(k).addEventListener('input',()=>{syncControls();wake();});
  for(const k of ['shape','model','profile','wallModel','ink','reduce','hideLabel','source'])$(k).addEventListener('change',()=>{syncControls();wake();});
  $('background').onchange=background;
  $('optics').onclick=()=>{for(const k of ['blur','tint','trim','shine','shadow'])$(k).value=0;syncControls();wake();};
  $('reset').onclick=()=>{
    release(true);expanded=false;wall=0;state={x:480,v:0,target:480,m:0,mv:0,strain:0,sv:0,z:0,zv:0,wallStrain:0,wallVelocity:0};previousV=0;
    for(const[k,v]of Object.entries(defaults))$(k).value=v;
    $('wallModel').value='planar';$('model').value='0';$('profile').value='2';$('shape').value='pill';$('background').value='lines';$('ink').value='light';$('reduce').checked=media.matches;$('hideLabel').checked=$('source').checked=false;
    $('menu').textContent='Expand surface';$('menu').setAttribute('aria-pressed','false');selectTab(tabs[0]);syncControls();background();
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
  canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();lost=true;rendererReason='The WebGL context was lost; software rendering is active.';draw();});
  canvas.addEventListener('webglcontextrestored',()=>{gpu=initGL();upload();wake();});
  $('retry').onclick=()=>{
    const url=new URL(location.href);url.searchParams.delete('fallback');history.replaceState(null,'',url);
    if(lost){contextControl?.restoreContext();return;}
    gpu=initGL();upload();wake();
  };
  window.addEventListener('pagehide',()=>{if(frame)cancelAnimationFrame(frame);frame=0;});
  window.addEventListener('pageshow',wake);
  syncControls();background();draw();
  // Deterministic observability for the public browser regression runner.
  window.glassLab={
    info:()=>({gpu:gpu&&!lost,renderer:gpu&&!lost?'webgl':'software',reason:rendererReason,software:software.info(),frames,uploads,scheduled:!!frame,geometry:lastGeometry,renderSize:[canvas.width,canvas.height],viewWidth,state:{...state},drawTimes:[...drawTimes]}),
    configure(options){const previousBackground=$('background').value;for(const[k,v]of Object.entries(options)){const e=$(k);if(!e)throw Error(k);if(e.type==='checkbox')e.checked=!!v;else e.value=v;}syncControls();if(previousBackground!==$('background').value)background();draw();},
    settle(){if(frame)cancelAnimationFrame(frame);frame=0;for(let n=0;n<2400&&unsettled();n++)step(1/120);draw();return this.info();},
    advance(ms){if(frame)cancelAnimationFrame(frame);frame=0;for(let t=0;t<ms;t+=1000/120)step(Math.min(1000/120,ms-t)/1000);draw();return this.info();},
    pixels(points){draw();if(!gpu||lost)return software.pixels(points,viewWidth);const data=new Uint8Array(canvas.width*canvas.height*4);gl.readPixels(0,0,canvas.width,canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,data);return points.map(([x,y])=>{const i=4*(clamp(Math.floor((H-y)/H*canvas.height),0,canvas.height-1)*canvas.width+clamp(Math.floor((x-viewLeft())/viewWidth*canvas.width),0,canvas.width-1));return [...data.slice(i,i+4)];});},
    pixel(x,y){return this.pixels([[x,y]])[0];},
    lose:()=>contextControl?.loseContext(),
    restore:()=>contextControl?.restoreContext(),
    error:()=>gl?.getError()
  };
})();
