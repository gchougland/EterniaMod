// Render bind-pose comparisons with the existing model viewer; no game or account server needed.
import express from 'express';
import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const web=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const repo=path.resolve(web,'..');
const app=express();
app.get('/favicon.ico',(_req,res)=>res.status(204).end());
app.use('/prefab-viewer',express.static(path.join(web,'web/prefab-viewer')));
app.use('/vendor/three',express.static(path.join(web,'node_modules/three')));
app.get('/hytale-assets/Common/NPC/Review/Before.blockymodel',(_req,res)=>res.sendFile(path.join(repo,'build/prowl-before.blockymodel')));
app.use('/hytale-assets/Common',express.static(path.join(repo,'src/main/resources/Common')));
const hasBefore=await fs.access(path.join(repo,'build/prowl-before.blockymodel')).then(()=>true,()=>false);
const samples=[['Original Prowl','NPC/Eternia/Prowl/prowl_hytale.blockymodel'],...(hasBefore?[['Previous rig','NPC/Review/Before.blockymodel']]:[]),['Corrected rig','NPC/Eternia/Prowl/Prowl_PlayerRig.blockymodel']];
app.get('/',(_req,res)=>res.type('html').send(`<!doctype html><meta charset="utf-8"><title>Prowl rig comparison</title>
<style>body{margin:0;background:#101e22;color:#f0e8d5;font:18px system-ui}h1{font-size:26px;margin:24px}p{margin:0 24px 20px;color:#b5c3b8}main{display:flex;gap:16px;margin:0 24px}section{flex:1;text-align:center;background:#192d31}h2{font-size:18px;font-weight:500;color:#d8b66b}canvas{display:block;width:100%}</style>
<h1>Prowl · original proportions restored</h1><p>Same camera, texture and scale. Bind-pose render using Eternia’s prefab viewer; this is not an in-game screenshot.</p><main></main>
<script type="importmap">{"imports":{"three":"/vendor/three/build/three.module.js"}}</script><script type="module">
import * as THREE from 'three';import {loadBlockyModel} from '/prefab-viewer/BlockyModelLoader.js';
const bounds=[];const samples=${JSON.stringify(samples)};
for(const [label,model] of samples){
 const section=document.createElement('section');section.innerHTML='<h2>'+label+'</h2>';document.querySelector('main').append(section);
 const renderer=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});renderer.setPixelRatio(1);renderer.setSize(430,580);renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.setClearColor('#192d31');section.append(renderer.domElement);
 const scene=new THREE.Scene(),camera=new THREE.OrthographicCamera(-1.08,1.08,1.46,-1.46,.1,50);camera.position.set(3,2.5,6);camera.lookAt(0,.98,0);
 scene.add(new THREE.HemisphereLight(0xffffff,0x52655a,2));const light=new THREE.DirectionalLight(0xffffff,2.5);light.position.set(2,4,5);scene.add(light);
 const figure=await loadBlockyModel(model,'NPC/Eternia/Prowl/prowl_hytale.png');if(!figure)throw new Error('Model failed: '+model);scene.add(figure);figure.updateMatrixWorld(true);const box=new THREE.Box3().setFromObject(figure);bounds.push({label,min:box.min.toArray(),max:box.max.toArray()});
 const floor=new THREE.Mesh(new THREE.PlaneGeometry(8,8),new THREE.MeshLambertMaterial({color:0x334a47}));floor.rotation.x=-Math.PI/2;floor.position.y=-.02;scene.add(floor);renderer.render(scene,camera);
}
window.prowlComparison={bounds,ready:true};</script>`));
const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
let browser;
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const page=await browser.newPage({viewport:{width:1390,height:745}});const errors=[];page.on('pageerror',error=>{errors.push(error.message);console.error(error.message);});page.on('console',message=>{if(message.type()==='error'||message.type()==='warn')console.error(message.text());});
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>window.prowlComparison?.ready);
 assert.deepEqual(errors,[]);const result=await page.evaluate(()=>window.prowlComparison);const original=result.bounds[0],corrected=result.bounds.at(-1);
 for(const edge of ['min','max'])for(let axis=0;axis<3;axis++)assert.ok(Math.abs(original[edge][axis]-corrected[edge][axis])<1e-6,`Changed rendered ${edge} axis ${axis}`);
 const out=path.join(web,'test-output');await fs.mkdir(out,{recursive:true});await page.screenshot({path:path.join(out,'prowl-rig-comparison.png'),fullPage:true});await fs.writeFile(path.join(out,'prowl-rig-comparison.json'),JSON.stringify(result,null,2));
 console.log('Prowl viewer render passed: original and corrected bounds agree. Saved test-output/prowl-rig-comparison.png.');
}finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
