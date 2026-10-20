/* Actual output against the independent double-precision reference, not merely
   renderer-to-renderer agreement. Coordinate texture eliminates artistic effects. */
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),M=require('../model.js');
(async()=>{
 const root=path.resolve(__dirname,'..'),out=process.env.LAB_EVIDENCE||path.join(root,'test-output');fs.mkdirSync(out,{recursive:true});
 const url=process.env.LAB_URL||pathToFileURL(path.join(root,'site/liquid-glass/lab.html')).href;
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const report=[],errors=[];
 for(const software of [false,true]){
  const page=await browser.newPage({viewport:{width:1200,height:900}});page.on('pageerror',e=>errors.push(e.message));
  const target=new URL(url);if(software)target.searchParams.set('fallback','1');await page.goto(target.href);await page.waitForFunction(()=>window.glassLab);
  for(const shape of ['pill','circle','panel'])for(const mode of ['0','1','2','cap']){
   const options={shape,model:mode==='cap'?'1':'0',profile:mode==='cap'?'2':mode,depth:1,edge:.4,core:.012,band:.55,rim:.35,thickness:.3,gap:.11,ior:1.33,background:'coordinates',source:false,blur:0,tint:0,trim:0,shine:0,shadow:0,hideLabel:true};
   await page.evaluate(o=>{glassLab.configure(o);glassLab.settle();},options);
   for(const held of [false,true]){
    if(held){await page.locator('#scene').focus();await page.keyboard.down('Space');await page.evaluate(()=>glassLab.advance(600));}
    const info=await page.evaluate(()=>glassLab.info()),g=info.geometry,half=[g.w/2,g.h/2];
    const [w,h]=software?[info.software.width,info.software.height]:info.renderSize,dx=info.viewWidth/w,dy=400/h,left=(960-info.viewWidth)/2;
    const points=[],expected=[];let rimSamples=0;
    for(let iy=0;iy<h;iy++)for(let ix=0;ix<w;ix++){
     const p=[left+(ix+.5)*dx-g.x,(iy+.5)*dy-g.y],d=-M.boundary(p,half,g.r).sd;
     // Full coverage excludes the AA mix with the outside image; retain the
     // FIRST fully covered ring and a stratified sample of the entire interior.
     if(d<Math.max(dx,dy)*.5||!(d<Math.max(dx,dy)*1.5||(ix%7===0&&iy%7===0)))continue;
     if(d<Math.max(dx,dy)*1.5)rimSamples++;
     // Runtime band is min(r,min(h)*baseBand)*(1-.25*held); convert to
     // reference's fraction AFTER the radius cap for panels.
     const effectiveBand=Math.min(g.r,Math.min(...half)*.55)*(1-.25*M.clamp(-info.state.z/.3,0,1))/Math.min(...half);
     const ref=mode==='cap'?M.cap(p,half,g.r,{band:effectiveBand,height:g.referenceLength*.3*g.z,gap:g.referenceLength*.11,ior:1.33}):M.field(p,half,g.r,{band:effectiveBand,edge:.4,core:.012,power:g.z,kind:+mode,rim:.35});
     assert(ref.valid!==false);points.push([p[0]+g.x,p[1]+g.y]);expected.push([(ref.map[0]+g.x)/960*255,(ref.map[1]+g.y)/400*255,51]);
    }
    const actual=await page.evaluate(p=>glassLab.pixels(p),points);
    const maxError=Math.max(...actual.flatMap((v,i)=>v.slice(0,3).map((c,j)=>Math.abs(c-M.clamp(expected[i][j],0,255)))));
    assert(rimSamples>20);assert(maxError<.55,`${software?'CPU':'GPU'} ${shape}/${mode}/${held}: ${maxError}`);
    report.push({renderer:software?'software':'webgl',shape,mode,held,samples:points.length,rimSamples,maxEncodedChannelError:maxError});
    if(held){await page.keyboard.up('Space');await page.evaluate(()=>glassLab.settle());}
   }
  }
  // A real texture as well as a coordinate ramp: both models must alter the
  // first covered ring, so a several-unit identity strip cannot pass silently.
  for(const model of ['0','1']){
   await page.evaluate(model=>{glassLab.configure({shape:'pill',model,profile:'2',background:'crossed',depth:1,source:false});glassLab.settle();},model);
   await page.locator('#stage').screenshot({path:path.join(out,`${software?'software':'gpu'}-${model}-edge.png`)});
  }
  await page.close();
 }
 assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'oracle-results.json'),JSON.stringify({report,errors},null,2));
 console.log(`PASS ${report.length} actual framebuffer/reference cases, all shapes/profiles and contact states; first fully covered rim included`);
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
