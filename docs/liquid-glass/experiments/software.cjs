/* Exercise actual CPU pixels, unavailable contexts, retry and GPU agreement. */
const{chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
(async()=>{
  const root=path.resolve(__dirname,'..'),out=process.env.LAB_EVIDENCE||path.join(root,'test-output');fs.mkdirSync(out,{recursive:true});
  const url=process.env.LAB_URL||pathToFileURL(path.join(root,'site/liquid-glass/lab.html')).href;
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const errors=[],results=[],page=await browser.newPage({viewport:{width:1100,height:820}});
  page.on('pageerror',e=>errors.push(e.message));page.on('crash',()=>errors.push('crash'));
  const force=new URL(url);force.searchParams.set('fallback','1');await page.goto(force.href);await page.waitForFunction(()=>window.glassLab);await page.evaluate(()=>glassLab.settle());
  assert.equal(await page.evaluate(()=>glassLab.info().renderer),'software');
  const config=o=>page.evaluate(o=>{glassLab.configure(o);glassLab.settle();},o);
  const zero={depth:0,blur:0,tint:0,trim:0,shine:0,shadow:0,hideLabel:true};
  const pts=Array.from({length:50},(_,i)=>[350+i*5,155+i%10*9]);
  for(const model of ['0','1'])for(const shape of ['circle','pill','panel']){
    await config({...zero,model,shape,background:'grid',source:true});const original=await page.evaluate(p=>glassLab.pixels(p),pts);
    await config({source:false});assert.deepEqual(await page.evaluate(p=>glassLab.pixels(p),pts),original);
  }
  results.push('Zero optical power preserves the sampled backdrop in both models/all shapes');
  // Probe exact software pixel centers, avoiding coordinate quantization mismatch.
  const positions=await page.evaluate(()=>{const i=glassLab.info(),w=i.software.width,h=i.software.height;return [[480,166],[480,234],[403,195],[557,205],[480,200],[200,100]].map(([x,y])=>[(Math.floor(x/960*w)+.5)*960/w,(Math.floor(y/400*h)+.5)*400/h]);});
  const gpu=await browser.newPage({viewport:{width:1100,height:820}});await gpu.goto(url);await gpu.waitForFunction(()=>window.glassLab);
  const agreement=[];
  for(const [model,profile] of [['0','0'],['0','1'],['1','1']]){
    const settings={...zero,depth:1,shape:'pill',profile,model,background:'coordinates',source:false};await config(settings);
    const refracted=await page.evaluate(p=>glassLab.pixels(p),positions);
    await config({source:true});const original=await page.evaluate(p=>glassLab.pixels(p),positions);
    assert(refracted[0][1]>original[0][1]);assert(refracted[1][1]<original[1][1]);assert.deepEqual(refracted[5],original[5]);
    await gpu.evaluate(o=>{glassLab.configure(o);glassLab.settle();},settings);const other=await gpu.evaluate(p=>glassLab.pixels(p),positions);
    const delta=Math.max(...other.flatMap((p,i)=>p.slice(0,3).map((v,c)=>Math.abs(v-refracted[i][c]))));assert(delta<=2,`GPU/CPU mismatch ${model}: ${delta}`);agreement.push({model,profile,maxChannelDifference:delta});
  }
  results.push('Both software models displace background pixels and agree with GPU coordinate probes');
  const texturePoints=Array.from({length:360},(_,i)=>[390+i%30*6,160+Math.floor(i/30)*7]);
  for(const model of ['0','1']){
    await config({...zero,depth:1,shape:'pill',model,background:'lines',source:false});
    const bent=await page.evaluate(p=>glassLab.pixels(p),texturePoints);
    await config({depth:0});const straight=await page.evaluate(p=>glassLab.pixels(p),texturePoints);
    const changed=bent.filter((p,i)=>Math.max(...p.slice(0,3).map((v,c)=>Math.abs(v-straight[i][c])))>10).length;
    assert(changed>=3,`No visible texture displacement in software model ${model}: ${changed}`);
  }
  results.push('Both models visibly displace the actual vertical-line texture with all tint/lighting disabled');
  await config({...zero,shape:'panel',background:'coordinates',source:false,trim:.5,weight:2,shine:1,ink:'light'});
  const ramp=await page.evaluate(()=>glassLab.pixels(Array.from({length:51},(_,i)=>[355+i*5,200])).map(p=>p[0]));assert(ramp.every((v,i)=>v<=128&&(!i||v>=ramp[i-1])));
  await config({ink:'dark'});const inverse=await page.evaluate(()=>glassLab.pixels(Array.from({length:51},(_,i)=>[355+i*5,200])).map(p=>p[0]));assert(inverse.every((v,i)=>v>=127&&(!i||v>=inverse[i-1])));
  results.push('Software tone compression is monotonic and respects both endpoints');
  await config({...zero,depth:1,shape:'panel',model:'0',background:'text',source:false});
  const points=Array.from({length:100},(_,i)=>[360+i%20*12,150+Math.floor(i/20)*20]);const clear=await page.evaluate(p=>glassLab.pixels(p),points);await config({blur:4});const blurred=await page.evaluate(p=>glassLab.pixels(p),points);assert.notDeepEqual(clear,blurred);results.push('Blur changes the sampled background');
  await page.click('#reset');await page.evaluate(()=>glassLab.settle());await page.locator('#stage').screenshot({path:path.join(out,'software-clear.png')});await page.click('#menu');await page.evaluate(()=>glassLab.settle());await page.locator('#stage').screenshot({path:path.join(out,'software-panel.png')});
  const timings=await page.evaluate(()=>{glassLab.configure({blur:0});const start=performance.now();for(let i=0;i<30;i++)glassLab.advance(16);return {elapsed:performance.now()-start,info:glassLab.info()};});
  await page.waitForTimeout(100);const a=await page.evaluate(()=>glassLab.info().frames);await page.waitForTimeout(150);assert.equal(await page.evaluate(()=>glassLab.info().frames),a);results.push('Software renderer sleeps after settling');
  await page.click('#retry');await page.waitForFunction(()=>glassLab.info().gpu);assert.equal(new URL(page.url()).searchParams.has('fallback'),false);results.push('Retry clears forced software mode and starts WebGL');
  const blocked=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2});
  await blocked.addInitScript(()=>{const get=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(type,...args){return type==='webgl2'?null:get.call(this,type,...args);};});
  await blocked.goto(url);await blocked.waitForFunction(()=>window.glassLab);assert.equal(await blocked.evaluate(()=>glassLab.info().renderer),'software');assert((await blocked.locator('#rendererNote').textContent()).includes('did not provide'));
  await blocked.evaluate(o=>{glassLab.configure(o);glassLab.settle();},{...zero,depth:1,background:'coordinates'});const withLens=await blocked.evaluate(()=>glassLab.pixel(480,166));await blocked.evaluate(()=>glassLab.configure({source:true}));assert.notDeepEqual(await blocked.evaluate(()=>glassLab.pixel(480,166)),withLens);
  await blocked.click('#retry');assert.equal(await blocked.evaluate(()=>glassLab.info().renderer),'software');await blocked.click('#reset');await blocked.screenshot({path:path.join(out,'software-phone.png'),fullPage:true});results.push('Unavailable WebGL context still refracts on phone layout; unsuccessful retry preserves rendering');
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'software-results.json'),JSON.stringify({results,agreement,softwareFrameMeanMs:timings.elapsed/30,resolution:timings.info.software,browser:await browser.version(),limitation:'Headless Chromium on server CPU; not device performance'},null,2));
  results.forEach(r=>console.log('PASS',r));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
