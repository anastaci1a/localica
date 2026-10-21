// Test actual HTTP requests: closed details must not fetch media, even offscreen.
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright'),{prepareMedia}=require('../../guide-media.cjs');
const root=path.resolve(__dirname,'../../site'),requests=[],finished=[];
const fixture=prepareMedia('<details open><summary>Outer</summary><img src="assets/study-active-switch-progress-a.png"><details><summary>Inner</summary><img src="assets/study-active-switch-progress-b.png"></details></details>',path.resolve(__dirname,'../..')).replaceAll('="assets/','="/liquid-glass/assets/');
// Exercise nested sections using real local image assets.
(async()=>{
 const server=http.createServer((req,res)=>{
  const name=decodeURIComponent(new URL(req.url,'http://localhost').pathname),file=path.resolve(root,'.'+name);
  if(name==='/nested.html'){res.setHeader('Content-Type','text/html');res.end('<script src="/liquid-glass/guide-media.js" defer></script>'+fixture);return;}
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return;}
  const size=fs.statSync(file).size;res.setHeader('Content-Type',({'.html':'text/html','.css':'text/css','.js':'application/javascript','.png':'image/png','.gif':'image/gif','.mp4':'video/mp4'})[path.extname(file)]||'application/octet-stream');
  let start=0,end=size-1;
  if(req.headers.range){const match=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range);if(!match){res.writeHead(416);res.end();return;}start=+match[1];end=match[2]?Math.min(+match[2],end):end;res.statusCode=206;res.setHeader('Content-Range',`bytes ${start}-${end}/${size}`);}
  res.setHeader('Content-Length',end-start+1);res.setHeader('Accept-Ranges','bytes');const stream=fs.createReadStream(file,{start,end});res.on('close',()=>stream.destroy());stream.pipe(res);
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:950}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>requests.push(new URL(r.url()).pathname));page.on('requestfinished',r=>finished.push(new URL(r.url()).pathname));
  const base='http://127.0.0.1:'+server.address().port;
  await page.goto(base+'/liquid-glass/reconstruction.html',{waitUntil:'networkidle'});
  const closed=await page.locator('details [data-media-src]').evaluateAll(list=>list.map(i=>new URL(i.dataset.mediaSrc,location.href).pathname));
  assert(!requests.some(p=>closed.includes(p)),'Closed figure requested media');
  assert(!requests.some(p=>/\.(gif|mp4)$/.test(p)),'Initial animation request');
  const initial={requests:[...requests],png:requests.filter(p=>p.endsWith('.png')).length,bytes:await page.evaluate(()=>performance.getEntriesByType('resource').reduce((n,r)=>n+r.transferSize,0))};
  await page.locator('#detached-search-control').evaluate(d=>d.open=true);
  const section=page.locator('details:not(.study)').filter({has:page.locator('img[data-media-src*="music-search-vector-paired-a.gif"]')});
  await section.locator('summary').click();
  const gif=section.locator('img');await gif.evaluate(async i=>{while(!i.hasAttribute('src'))await new Promise(requestAnimationFrame);await i.decode();});
  assert.equal(requests.filter(p=>p.endsWith('.gif')).length,1,'Only opened GIF should load');
  assert(requests.filter(p=>p.endsWith('.mp4')).every(p=>p.includes('music-search-vector-paired-a')), 'Unopened video requested');
  const video=section.locator('video');await video.evaluate(async v=>{v.muted=true;await v.play();});
  await page.waitForFunction(()=>document.querySelector('details[open] video').currentTime>0);
  await section.locator('summary').click();
  await page.waitForFunction(()=>!document.querySelector('details:not([open]) img[src]:not(.study-thumbnail),details:not([open]) source[src]'));
  assert(await video.evaluate(v=>v.paused&&v.readyState===0),'Closed video must be stopped/unloaded');
  await section.locator('summary').click();await gif.evaluate(async i=>{while(!i.hasAttribute('src'))await new Promise(requestAnimationFrame);await i.decode();});await section.locator('summary').click();
  await page.goto(base+'/nested.html',{waitUntil:'networkidle'});
  assert.equal(await page.locator('details details img[src]').count(),0,'Nested closed section hydrated');
  await page.locator('details details summary').click();await page.locator('details details img').evaluate(async i=>{while(!i.hasAttribute('src'))await new Promise(requestAnimationFrame);await i.decode();});
  await page.locator('body>details>summary').click();await page.waitForFunction(()=>!document.querySelector('details img[src]'));
  await page.locator('body>details>summary').click();await page.locator('details details img').evaluate(async i=>{while(!i.hasAttribute('src'))await new Promise(requestAnimationFrame);await i.decode();});
  assert.deepEqual(errors,[]);
  const report={initial,openedGif:requests.filter(p=>p.endsWith('.gif')),videoRequested:requests.some(p=>p.endsWith('.mp4')),nestedAndReopen:true};
  if(process.env.LAB_EVIDENCE){fs.mkdirSync(process.env.LAB_EVIDENCE,{recursive:true});fs.writeFileSync(path.join(process.env.LAB_EVIDENCE,'media-network.json'),JSON.stringify(report,null,2)+'\n');}
  console.log(JSON.stringify(report));
 }finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exit(1)});
