// Present existing Markdown studies as a compact index. Source figures stay intact.
const fs=require('node:fs'),path=require('node:path');
const studies={
 'clear-playback-control':['Playback','Circular control · most polished'],
 'archive-and-plus-toolbar':['Toolbar','Archive and plus · text backdrop'],
 'safari-tab-count-pill':['Safari','Tab-count and plus · grouped control'],
 'safari-circular-controls':['Safari','Circular controls · transfer under review'],
 'next-track-control':['Playback','Next-track circle · sky background'],
 'mail-three-action-toolbar':['Mail','Three-action toolbar · material study'],
 'active-slider':['Settings','Active slider · blue track'],
 'light-and-dark-surfaces':['Surfaces','Light and dark material responses'],
 'expanded-mini-player':['Apple Music','Expanded mini-player'],
 'compact-mini-player':['Apple Music','Compact mini-player'],
 'detached-search-control':['Apple Music','Detached Search control'],
 'navigation-control':['Apple Music','Navigation selector'],
 'home-control':['Apple Music','Home control'],
 'active-switch':['Settings','Active switch · green track'],
 'active-segmented-selector':['Settings','Segmented selector · under review']
};
async function prepareStudies(html,root,write){
 const sharp=require('sharp');
 const headings=[...html.matchAll(/<h([23]) id="([^"]+)">([\s\S]*?)<\/h\1>/g)];
 const replacements=[];
 for(let i=0;i<headings.length;i++){
  const h=headings[i],meta=studies[h[2]];if(!meta)continue;
  let j=i+1;while(j<headings.length&&+headings[j][1]>+h[1])j++;
  const end=j<headings.length?headings[j].index:html.length;
  const body=html.slice(h.index+h[0].length,end),first=body.match(/<img[^>]*src="([^"]+)"/);
  let thumbnail='';
  if(first){
   const file=path.resolve(root,first[1]);if(!file.startsWith(root+path.sep))throw Error('Thumbnail outside guide');
   const name=`assets/thumb-${h[2]}.webp`;
   const buffer=await sharp(fs.readFileSync(file)).resize({width:240,height:120,fit:'inside',withoutEnlargement:true}).webp({quality:75}).toBuffer();
   write('liquid-glass/'+name,buffer);
   thumbnail=`<img class="study-thumbnail" src="${name}" width="120" height="60" alt="" loading="lazy">`;
  }
  replacements.push({start:h.index,end,html:`<details class="study" id="${h[2]}"><summary>${thumbnail}<span class="study-name"><span class="study-category">${meta[0]}</span><strong>${h[3]}</strong><span class="study-description">${meta[1]}</span></span><span class="study-expand" aria-hidden="true">+</span></summary><div class="study-body">${body}</div></details>\n`});
 }
 for(const r of replacements.reverse())html=html.slice(0,r.start)+r.html+html.slice(r.end);
 return html;
}
module.exports={prepareStudies,studyIds:Object.keys(studies)};
