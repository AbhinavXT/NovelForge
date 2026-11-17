// Renders the README promo video frames. Usage (from repo root):
//   node scripts/promo-video/render.js scripts/promo-video full /tmp/frames
//   ffmpeg -framerate 30 -i /tmp/frames/f%04d.png -c:v libx264 -crf 20 -pix_fmt yuv420p -movflags +faststart docs/media/promo.mp4
// "preview" instead of "full" renders a handful of keyframes.
const {chromium}=require('playwright');const path=require('path');const fs=require('fs');
(async()=>{const [,,dir,mode,out]=process.argv;
const b=await chromium.launch();const p=await b.newPage({viewport:{width:1920,height:1080}});
await p.goto('file://'+path.resolve(dir,'index.html'));await p.evaluate(()=>document.fonts.ready);await p.waitForTimeout(500);
const D=await p.evaluate(()=>window.DURATION);
const times= mode==='preview'?[1.8,6,11,16,21.5,25.5,29]:Array.from({length:Math.round(D*30)},(_,i)=>i/30);
fs.mkdirSync(out,{recursive:true});
for(let i=0;i<times.length;i++){await p.evaluate(t=>window.render(t),times[i]);await p.screenshot({path:`${out}/f${String(i).padStart(4,'0')}.png`});}
await b.close();})();
