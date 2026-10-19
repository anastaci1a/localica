/* Run after npm ci && npm run build. Uses actual WebGL readback + real input. */
const{chromium}=require('playwright'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..'),out=process.env.LAB_EVIDENCE||path.join(root,'test-output');
fs.mkdirSync(out,{recursive:true});
const url=process.env.LAB_URL||pathToFileURL(path.join(root,'site/liquid-glass/lab.html')).href;
(async()=>{
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,
    args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const page=await browser.newPage({viewport:{width:1100,height:1000},deviceScaleFactor:1});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('crash',()=>errors.push('Renderer process crashed'));
  const results=[],check=(name,data)=>{results.push({name,...data});console.log('PASS',name);};
  await page.goto(url);await page.waitForFunction(()=>window.glassLab);
  assert.equal((await page.evaluate(()=>glassLab.info())).gpu,true,'Expected real WebGL2');
  const configure=x=>page.evaluate(x=>glassLab.configure(x),x);
  const settle=()=>page.evaluate(()=>glassLab.settle());
  const pixel=(x,y)=>page.evaluate(([x,y])=>glassLab.pixel(x,y),[x,y]);
  const zero={depth:0,blur:0,tint:0,trim:0,shine:0,shadow:0,hideLabel:true};
  // Compare actual textured GPU output, not just a JavaScript duplicate of optics.
  for(const shape of ['circle','pill','panel'])for(const model of ['0','1']){
    await configure({...zero,shape,model,background:'grid'});await settle();
    const before=await Promise.all([[480,200],[480,163],[430,190],[510,225],[200,100]].map(p=>pixel(...p)));
    await configure({source:true});const after=await Promise.all([[480,200],[480,163],[430,190],[510,225],[200,100]].map(p=>pixel(...p)));
    assert.deepEqual(before,after);await configure({source:false});
  }
  check('Zero-power texture identity: both models, all three shapes',{});
  await configure({...zero,depth:1,shape:'pill',model:'0',background:'coordinates'});await settle();
  const samples={center:await pixel(480,200),upper:await pixel(480,166),lower:await pixel(480,234),outside:await pixel(200,100)};
  await configure({source:true});const neutral={center:await pixel(480,200),upper:await pixel(480,166),lower:await pixel(480,234),outside:await pixel(200,100)};
  assert.deepEqual(samples.center,neutral.center);assert.deepEqual(samples.outside,neutral.outside);
  assert(samples.upper[1]>neutral.upper[1],'Upper lookup must bend inward');assert(samples.lower[1]<neutral.lower[1],'Lower lookup must bend inward');
  assert(Math.abs((samples.upper[1]-neutral.upper[1])+(samples.lower[1]-neutral.lower[1]))<=1,'Symmetric axial lookup');
  check('Actual shader displaces shoulder, registers core/exterior, has no downward bias',{samples,neutral});
  await configure({...zero,shape:'panel',source:false,background:'coordinates',trim:.5,weight:2,ink:'light',shine:1});await settle();
  const positions=Array.from({length:51},(_,i)=>[355+i*5,200]);
  const ramp=await page.evaluate(p=>glassLab.pixels(p).map(c=>c[0]),positions);
  assert(ramp.every((v,i)=>v<=128&&(i===0||v>=ramp[i-1])));await configure({ink:'dark'});
  const inverse=await page.evaluate(p=>glassLab.pixels(p).map(c=>c[0]),positions);
  assert(inverse.every((v,i)=>v>=127&&(i===0||v>=inverse[i-1])));
  check('GPU tone compression remains ordered with bounded endpoints for both polarities',{});
  // Pixel derivative continuity around the compact shoulder join and panel diagonal.
  await configure({...zero,depth:1,shape:'panel',model:'0',background:'coordinates'});await settle();
  const diagonal=await page.evaluate(()=>glassLab.pixels(Array.from({length:101},(_,i)=>[430+i,150+i])));
  const jumps=diagonal.slice(1).map((p,i)=>Math.max(...p.slice(0,3).map((v,j)=>Math.abs(v-diagonal[i][j]))));
  assert(Math.max(...jumps)<=3,'Unexpected diagonal seam');check('Panel diagonal coordinate continuity',{maxChannelStep:Math.max(...jumps)});
  await configure({shape:'pill',background:'lines',ink:'light',depth:1,tint:.02,trim:.12,shine:.35,shadow:.1,hideLabel:false});await settle();
  await page.locator('#scene').scrollIntoViewIfNeeded();await page.locator('#stage').screenshot({path:path.join(out,'clear.png')});
  await page.locator('#scene').focus();await page.keyboard.down('Space');
  const held=await page.evaluate(()=>glassLab.advance(400));assert(held.geometry.z<.9);assert(held.geometry.w>196);assert(Math.abs(held.geometry.volume-1)<1e-10);
  await page.keyboard.up('Space');const rebound=await page.evaluate(()=>glassLab.advance(180));assert(rebound.geometry.z>held.geometry.z);await settle();
  check('Contact flattens/expands; release restores coupled volume',{heldDepth:held.geometry.z,reboundDepth:rebound.geometry.z});
  await page.locator('#scene').scrollIntoViewIfNeeded();let box=await page.locator('#scene').boundingBox();
  await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.evaluate(()=>glassLab.advance(300));
  await page.mouse.move(box.x+box.width+40,box.y+box.height/2,{steps:10});const wall=await page.evaluate(()=>glassLab.advance(400));
  assert(Math.abs(wall.geometry.x+wall.geometry.w/2-928)<.001);await page.mouse.up();const returning=await page.evaluate(()=>glassLab.advance(90));assert(Math.abs(returning.geometry.x+returning.geometry.w/2-928)<.001);await settle();
  check('Wall contact remains pinned through compression and released recovery',{});
  await page.click('#reset');await settle();await page.click('#menu');await page.evaluate(()=>glassLab.advance(140));const mid=await page.evaluate(()=>glassLab.info());assert(mid.state.m>0&&mid.state.m<1);
  await page.click('#menu');const reversed=await page.evaluate(()=>glassLab.info());assert(Math.abs(mid.state.m-reversed.state.m)<.3);await settle();assert((await page.evaluate(()=>glassLab.info())).state.m<.001);
  check('Morph interruption returns continuously without an endpoint swap',{});
  await configure({reduce:true});await page.click('#menu');await page.waitForTimeout(100);await settle();let idle=await page.evaluate(()=>glassLab.info());assert.equal(idle.state.m,1);await page.waitForTimeout(150);let next=await page.evaluate(()=>glassLab.info());assert.equal(next.frames,idle.frames);assert.equal(next.uploads,idle.uploads);
  check('Reduced motion snaps; idle frames and texture uploads stop',{});
  await configure({reduce:false,blur:3});await page.locator('#stage').screenshot({path:path.join(out,'panel.png')});
  await page.evaluate(()=>glassLab.lose());await page.waitForTimeout(100);assert.equal((await page.evaluate(()=>glassLab.info())).gpu,false);assert((await page.locator('#status').textContent()).includes('fallback'));
  await page.evaluate(()=>glassLab.restore());await page.waitForFunction(()=>glassLab.info().gpu);assert.equal(await page.evaluate(()=>glassLab.error()),0);
  check('Context loss preserves fallback; restoration rebuilds GL resources',{});
  const fallback=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});await fallback.goto(url+'?fallback=1');assert.equal((await fallback.evaluate(()=>glassLab.info())).gpu,false);await fallback.click('#menu');await fallback.evaluate(()=>glassLab.settle());assert.equal(await fallback.locator('#menu').getAttribute('aria-pressed'),'true');await fallback.screenshot({path:path.join(out,'fallback.png'),fullPage:true});
  const phone=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true});await phone.goto(url);assert.equal(await phone.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await phone.screenshot({path:path.join(out,'phone.png'),fullPage:true});
  const phoneInfo=await phone.evaluate(()=>glassLab.settle());assert.equal(phoneInfo.viewWidth,520);assert(phoneInfo.renderSize[0]*phoneInfo.renderSize[1]<=960*400);
  await phone.click('#right');const phoneEdge=await phone.evaluate(()=>glassLab.settle());assert(Math.abs(phoneEdge.geometry.x+phoneEdge.geometry.w/2-(480+260-32))<.1);
  check('Phone layout fits; no-WebGL fallback retains working controls',{});
  await page.emulateMedia({reducedMotion:'reduce'});
  await configure({depth:2,band:.8,blur:5,tint:.3,trim:.5,weight:2,shine:1,shadow:.3,damping:35,shape:'circle',model:'1',background:'text',ink:'dark',reduce:false,hideLabel:true,source:true});
  await page.click('#reset');await settle();
  const reset=await page.evaluate(()=>Object.fromEntries([...document.querySelectorAll('input,select')].map(e=>[e.id,e.type==='checkbox'?e.checked:e.value])));
  assert.deepEqual(reset,{model:'0',shape:'pill',background:'lines',ink:'light',depth:'1',band:'0.55',blur:'0',tint:'0.02',trim:'0.12',weight:'1.6',shine:'0.35',shadow:'0.1',damping:'8',reduce:true,hideLabel:false,source:false});
  assert.equal(await page.locator('#menu').getAttribute('aria-pressed'),'false');
  await page.emulateMedia({reducedMotion:'no-preference'});
  check('Reset restores every control and the system Reduced Motion preference',{});
  for(let i=0;i<40;i++){
    await configure({shape:['panel','circle','pill'][i%3],model:String(i%2),blur:i%6});
    await page.click('#menu');await page.evaluate(()=>glassLab.advance(160));
  }
  assert.equal(await page.evaluate(()=>glassLab.error()),0);
  for(let i=0;i<3;i++){
    await page.evaluate(()=>glassLab.lose());await page.waitForFunction(()=>!glassLab.info().gpu);
    await page.evaluate(()=>glassLab.restore());await page.waitForFunction(()=>glassLab.info().gpu);
  }
  check('Repeated morph/model changes and three context recoveries remain functional',{});
  await page.click('#reset');await settle();await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});
  // Actual contact/recovery sequence, captured from this renderer.
  fs.mkdirSync(path.join(out,'pressure'),{recursive:true});
  await page.locator('#scene').scrollIntoViewIfNeeded();box=await page.locator('#scene').boundingBox();
  await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
  for(let i=0;i<60;i++){
    if(i<25)await page.mouse.move(box.x+box.width/2+(box.width/2+45)*i/24,box.y+box.height/2);
    if(i===32)await page.mouse.up();
    await page.evaluate(()=>glassLab.advance(1000/30));
    await page.locator('#stage').screenshot({path:path.join(out,'pressure',String(i).padStart(3,'0')+'.png')});
  }

  // Capture deterministic original WebGL motion, not copied Apple frames.
  await page.click('#reset');await settle();await page.locator('#scene').scrollIntoViewIfNeeded();await page.click('#menu');
  fs.mkdirSync(path.join(out,'morph'),{recursive:true});
  for(let i=0;i<30;i++){await page.evaluate(()=>glassLab.advance(1000/30));await page.locator('#stage').screenshot({path:path.join(out,'morph',String(i).padStart(3,'0')+'.png')});}
  await page.click('#menu');for(let i=30;i<60;i++){await page.evaluate(()=>glassLab.advance(1000/30));await page.locator('#stage').screenshot({path:path.join(out,'morph',String(i).padStart(3,'0')+'.png')});}
  assert.deepEqual(errors,[]);check('No uncaught browser errors',{count:errors.length});
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({browser:await browser.version(),renderer:'headless SwiftShader (not phone performance)',results},null,2));
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
