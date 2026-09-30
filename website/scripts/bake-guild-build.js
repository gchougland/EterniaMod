// Bake the actual prefab into small WebP frames. Visitors need no WebGL or Hytale asset download.
import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:3851';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Use local viewer assets');
const out=new URL('../web/media/guild-build/',import.meta.url);
const prefab=JSON.parse(await fs.readFile(new URL('../../src/main/resources/Server/Prefabs/FoundersHall.prefab.json',import.meta.url),'utf8'));
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try {
 const page=await browser.newPage({viewport:{width:768,height:768},deviceScaleFactor:1});
 const me=await(await page.request.get(base+'/api/me')).json();
 if(!me.fixtures)throw new Error('Local fixtures required for authoring');
 const login=await page.request.post(base+'/api/dev/login',{headers:{'X-CSRF-Token':me.csrf},data:{}});
 if(!login.ok())throw new Error('Local authoring login failed');
 await page.route('**/__bake',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><style>html,body{margin:0}#viewer{width:768px;height:768px}</style><script type="importmap">{"imports":{"three":"/vendor/three/build/three.module.js","three/addons/":"/vendor/three/examples/jsm/"}}</script><div id="viewer"></div>'}));
 await page.goto(base+'/__bake');
 const count=await page.evaluate(async prefab=>{
  const {PrefabViewer}=await import('/prefab-viewer/PrefabViewer.js');const THREE=await import('three');
  const viewer=new PrefabViewer(document.querySelector('#viewer'),{interactive:false,fullscreenButton:false,hideGrid:true,transparentBackground:true});
  await viewer.loadPrefab(prefab,{frontFacing:'South'});
  if(viewer.undrawn?.length)throw new Error(JSON.stringify(viewer.undrawn));
  cancelAnimationFrame(viewer._raf);viewer.controls.enabled=false;
  const blocks=viewer._root.children.map(o=>({o,position:o.position.clone(),scale:o.scale.clone(),bottom:new THREE.Box3().setFromObject(o).min.y}));
  const max=Math.max(...blocks.map(b=>b.bottom)),min=Math.min(...blocks.map(b=>b.bottom));
  const target=viewer.controls.target.clone(),offset=viewer.camera.position.clone().sub(target).multiplyScalar(.85);
  window.bakeFrame=p=>{
   for(const b of blocks){const stagger=((Math.abs(b.position.x*31+b.position.z*17)%17)/17)*.035;
    const start=(b.bottom-min)/(max-min)*.79+stagger,t=Math.max(0,Math.min(1,(p-start)/.16)),ease=1-(1-t)**3;
    b.o.visible=t>0;b.o.position.copy(b.position);b.o.position.y+=(1-ease)*3;b.o.scale.copy(b.scale).multiplyScalar(.65+.35*ease);
   }
   const angle=(p-.5)*.12,rotated=offset.clone().applyAxisAngle(new THREE.Vector3(0,1,0),angle);
   viewer.camera.position.copy(target).add(rotated);viewer.camera.lookAt(target);viewer.renderer.render(viewer.scene,viewer.camera);
   return viewer.renderer.domElement.toDataURL('image/webp',.8).split(',')[1];
  };return blocks.length;
 },prefab);
 await fs.mkdir(out,{recursive:true});
 for(let i=0;i<48;i++){const bytes=await page.evaluate(p=>window.bakeFrame(p),i/47);await fs.writeFile(new URL(String(i).padStart(2,'0')+'.webp',out),Buffer.from(bytes,'base64'));}
 console.log('Baked 48 construction frames from '+count+' native block/entity groups into '+fileURLToPath(out));
}finally{await browser.close();}
