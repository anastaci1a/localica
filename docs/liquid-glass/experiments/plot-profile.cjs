/* Original vector plot, generated directly from the portable reference. */
const fs=require('node:fs'),path=require('node:path'),M=require('../model.js');
const w=960,h=430,pad=58,pw=390,ph=250,top=88,colors=['#f0ad84','#90b4ef','#76d9ba'];
let svg=`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" role="img" aria-labelledby="title desc"><title id="title">Boundary profile displacement and compression</title><desc id="desc">Left: offset versus inward distance. Right: normal inverse-map derivative; greater than one means compression. Old shoulder has almost no outer-rim displacement. New profile retains outline displacement and compresses near the outline.</desc><rect width="960" height="430" rx="16" fill="#111824"/><g font-family="system-ui,sans-serif" fill="#dfe6f0"><text x="30" y="32" font-size="18">Boundary field · 196 × 92 capsule · band 25.3 · core 0 · power 1</text>`;
for(let chart=0;chart<2;chart++){
 const x0=pad+chart*465,ymin=chart?-1:0,ymax=chart?4:11;
 const X=u=>x0+pw*u,Y=y=>top+ph*(1-(y-ymin)/(ymax-ymin));
 svg+=`<text x="${x0}" y="65" font-size="15">${chart?'Normal derivative ∂F / ∂p':'Inward lookup offset (logical px)'}</text>`;
 for(let i=0;i<=4;i++){const u=i/4;svg+=`<path d="M${X(u)} ${top}V${top+ph}" stroke="#263346"/><text x="${X(u)}" y="${top+ph+22}" text-anchor="middle" font-size="12">${u}</text>`;}
 for(let i=Math.ceil(ymin);i<=ymax;i+=chart?1:2){svg+=`<path d="M${x0} ${Y(i)}H${x0+pw}" stroke="${chart&&i===1?'#8493a5':'#263346'}"/><text x="${x0-8}" y="${Y(i)+4}" text-anchor="end" font-size="12">${i}</text>`;}
 for(let kind=0;kind<3;kind++){
  const points=[];for(let i=0;i<=400;i++){const u=i/400,a=M.field([0,-46+25.3*u],[98,46],46,{kind,edge:kind===0?.1:.4,core:0,rim:.35});points.push(`${X(u).toFixed(3)},${Y(chart?a.normalScale:a.displacement[1]).toFixed(3)}`);}
  svg+=`<polyline points="${points.join(' ')}" fill="none" stroke="${colors[kind]}" stroke-width="2.2"/>`;
 }
 svg+=`<text x="${x0+pw/2}" y="${top+ph+45}" text-anchor="middle" font-size="12">Inward distance / band width (outline = 0)</text>`;
}
['Old shoulder · edge 0.10','Rim-only · edge 0.40','Finite rim + compression · edge 0.40'].forEach((label,i)=>{const x=28+i*300;svg+=`<path d="M${x} 405h24" stroke="${colors[i]}" stroke-width="3"/><text x="${x+32}" y="409" font-size="12">${label}</text>`;});
svg+='</g></svg>';fs.writeFileSync(path.resolve(__dirname,'../assets/edge-profile.svg'),svg);
