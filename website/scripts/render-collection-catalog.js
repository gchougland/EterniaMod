// Render the actual bundled wearable, outfit and pet assets into native UI previews.
// Requires the local website for Three.js modules, plus legally installed Hytale assets.
import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const repo=fileURLToPath(new URL('../../',import.meta.url));
const assets=path.resolve(process.env.HYTALE_ASSETS_SRC||path.join(repo,'../HytaleSourceCode/hytale-shared-source/HytaleAssets'));
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:3851';
if(!['localhost','127.0.0.1'].includes(new URL(base).hostname))throw new Error('Use a local website');
const read=async p=>JSON.parse(await fs.readFile(p,'utf8'));
const catalog=path.join(repo,'src/main/resources/Server/EterniaMod');
const parts={overtop:'Overtops',pants:'Pants',shoes:'Shoes'};
async function clothing(slot,value){
 if(!parts[slot])throw new Error('Add a cosmetic catalog mapping for '+slot);
 const [id,color]=value.split('.');
 const entry=(await read(path.join(assets,'Cosmetics/CharacterCreator',parts[slot]+'.json'))).find(p=>p.Id===id);
 if(!entry)throw new Error('Missing native cosmetic '+value);
 const texture=entry.Textures?.[color]?.Texture||entry.GreyscaleTexture;
 if(!texture)throw new Error('Missing native texture '+value);
 return {model:entry.Model,texture,gradient:entry.GreyscaleTexture?'TintGradients/'+entry.GradientSet+'/'+color+'.png':null};
}
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
 const page=await browser.newPage({viewport:{width:512,height:512}});
 const me=await(await page.request.get(base+'/api/me')).json();
 if(!me.fixtures)throw new Error('Start the local website with LOCAL_FIXTURES=true');
 const login=await page.request.post(base+'/api/dev/login',{headers:{'X-CSRF-Token':me.csrf},data:{}});
 if(!login.ok())throw new Error('Local authoring login failed');
 // Serve source assets only to this local render page, without copying them into the public site.
 await page.route('**/hytale-assets/Common/**',async route=>{
  const relative=decodeURIComponent(new URL(route.request().url()).pathname.split('/hytale-assets/Common/')[1]);
  const root=path.join(assets,'Common'),file=path.resolve(root,relative);
  if(!file.startsWith(root+path.sep))return route.abort();
  await route.fulfill({body:await fs.readFile(file),contentType:file.endsWith('.png')?'image/png':'application/json'});
 });
 await page.route('**/__collection-render',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><script type="importmap">{"imports":{"three":"/vendor/three/build/three.module.js"}}</script>'}));
 await page.goto(base+'/__collection-render');
 for(const file of (await fs.readdir(path.join(catalog,'Collections'))).filter(f=>f.endsWith('.json'))){
  const item=await read(path.join(catalog,'Collections',file));let layers=[];
  if(item.kind==='WEARABLE')layers=[await clothing(item.slot,item.assetId)];
  else if(item.kind==='OUTFIT'){
   const appearance=await read(path.join(catalog,'Appearances',item.assetId+'.json'));
   for(const [slot,value]of Object.entries(appearance))if(value)layers.push(await clothing(slot,value));
  }else if(item.kind==='PET'){
   const model=await read(path.join(repo,'src/main/resources/Server/Models/Eternia',item.assetId+'.json'));
   layers=[{model:model.Model,texture:model.Texture}];
  }else continue;
  const png=await page.evaluate(async layers=>{
   const THREE=await import('three');const {loadBlockyModel,clearModelCaches}=await import('/prefab-viewer/BlockyModelLoader.js');
   clearModelCaches();const scene=new THREE.Scene(),group=new THREE.Group();scene.add(group);
   const loadImage=async p=>{const res=await fetch('/hytale-assets/Common/'+p);if(!res.ok)throw new Error(p);return createImageBitmap(await res.blob());};
   for(const layer of layers){
    const model=await loadBlockyModel(layer.model,layer.texture);if(!model)throw new Error(layer.model);
    // Cosmetics use player units, regardless of the block viewer's default density.
    if(layer.model.startsWith('Cosmetics/'))model.scale.setScalar(1/64);
    if(layer.gradient){
     const bitmap=await loadImage(layer.texture),gradient=await loadImage(layer.gradient);
     const canvas=document.createElement('canvas');canvas.width=bitmap.width;canvas.height=bitmap.height;
     const ctx=canvas.getContext('2d');ctx.drawImage(bitmap,0,0);const pixels=ctx.getImageData(0,0,canvas.width,canvas.height);
     const lut=document.createElement('canvas');lut.width=gradient.width;lut.height=gradient.height;const gc=lut.getContext('2d');gc.drawImage(gradient,0,0);const colors=gc.getImageData(0,Math.floor(lut.height/2),lut.width,1).data;
     for(let i=0;i<pixels.data.length;i+=4){const x=Math.round(pixels.data[i]/255*(lut.width-1))*4;for(let c=0;c<3;c++)pixels.data[i+c]=colors[x+c];}
     ctx.putImageData(pixels,0,0);const tex=new THREE.CanvasTexture(canvas);tex.colorSpace=THREE.SRGBColorSpace;tex.magFilter=THREE.NearestFilter;tex.minFilter=THREE.NearestFilter;
     model.traverse(o=>{if(o.isMesh){const tint=m=>{m=m.clone();m.map=tex;return m;};o.material=Array.isArray(o.material)?o.material.map(tint):tint(o.material);}});
    }
    group.add(model);
   }
   // Bounds of visible triangles, not the empty cosmetic rig nodes above the garment.
   group.updateMatrixWorld(true);const box=new THREE.Box3();group.traverse(o=>{if(o.isMesh&&(Array.isArray(o.material)?o.material.some(m=>m.visible!==false):o.material.visible!==false))box.union(new THREE.Box3().setFromObject(o));});
   const center=box.getCenter(new THREE.Vector3()),size=box.getSize(new THREE.Vector3());
   group.position.sub(center);const extent=Math.max(size.x,size.y,size.z)*.72;
   const camera=new THREE.OrthographicCamera(-extent,extent,extent,-extent,.01,100);
   camera.position.set(2,.9,5).normalize().multiplyScalar(10);camera.lookAt(0,0,0);
   scene.add(new THREE.HemisphereLight(0xf5f5e9,0x667e7d,2.5));const key=new THREE.DirectionalLight(0xffefdb,2.1);key.position.set(-3,6,8);scene.add(key);
   const renderer=new THREE.WebGLRenderer({alpha:true,antialias:true,preserveDrawingBuffer:true});renderer.setSize(512,512);renderer.setClearColor(0,0);renderer.render(scene,camera);
   const png=renderer.domElement.toDataURL('image/png').split(',')[1];renderer.dispose();return png;
  },layers);
  const out=path.join(repo,'src/main/resources/Common/UI/Custom/EterniaMod/Catalog',item.id.slice(8));
  await fs.mkdir(out,{recursive:true});
  const screenshot=await page.evaluate(async png=>{const img=new Image();img.src='data:image/png;base64,'+png;await img.decode();const c=document.createElement('canvas');c.width=1280;c.height=800;c.getContext('2d').drawImage(img,240,0,800,800);return c.toDataURL('image/png').split(',')[1];},png);
  await fs.writeFile(path.join(out,'screenshot.png'),Buffer.from(screenshot,'base64'));
  // Downsample in the browser so the script only needs the website's existing dependencies.
  const icon=await page.evaluate(async png=>{const img=new Image();img.src='data:image/png;base64,'+png;await img.decode();const c=document.createElement('canvas');c.width=c.height=128;c.getContext('2d').drawImage(img,0,0,128,128);return c.toDataURL('image/png').split(',')[1];},png);
  await fs.writeFile(path.join(out,'icon.png'),Buffer.from(icon,'base64'));console.log('Rendered '+item.id);
 }
}finally{await browser.close();}
