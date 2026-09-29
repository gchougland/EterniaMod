import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:3847';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Synthetic authoring smoke requires loopback');
const out=path.join(root,'test-output');await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page=await browser.newPage({viewport:{width:1440,height:1050}}),failures=[];
page.on('pageerror',e=>failures.push(e.message));
try{
 let state=await(await page.request.get(base+'/api/me')).json();assert.equal(state.fixtures,true,'Fixture mode must be explicitly enabled');
 let response=await page.request.post(base+'/api/dev/login',{headers:{'X-CSRF-Token':state.csrf},data:{}});assert.equal(response.status(),200);
 state=await(await page.request.get(base+'/api/me')).json();assert.equal(state.user.fixture,true);
 const headers={'X-CSRF-Token':state.csrf};
 const existing=await(await page.request.get(base+'/api/admin/content')).json();
 const sources=[
  {kind:'house',id:'hub_house',file:'House.prefab.json',displayName:'House',native:{plotAnchorOffset:[4,0,4],managementBlockLocalPos:[1,2,2],spawnLocalPos:[0,1,0],rotationYaw:'None',housingKind:'personal'}},
  {kind:'prop',id:'potion_shelf',file:'Potion Shelf.prefab.json',displayName:'Potion Shelf',native:{plotAnchorOffset:[0,0,0],rotationYaw:'None'}},
  {kind:'prop',id:'aqua_lamp',file:'Aqua Lamp.prefab.json',displayName:'Aqua Lamp',native:{plotAnchorOffset:[0,0,0],rotationYaw:'None'}}
 ];
 const imported=[],jobs=[];
 for(const source of sources){
  const id='eternia:'+source.kind+'/'+source.id,revision=Math.max(0,...existing.filter(r=>r.definition.id===id).map(r=>r.definition.revision))+1;
  const prefab=JSON.parse(await fs.readFile(path.join(root,'../src/main/resources/Server/Prefabs',source.file),'utf8'));
  const definition={schemaVersion:1,kind:source.kind,id,revision,displayName:source.displayName,native:source.native};
  response=await page.request.post(base+'/api/admin/content',{headers,data:{definition,prefab}});assert.equal(response.status(),201,await response.text());
  const record=await response.json();imported.push(record);
  for(const mode of ['screenshot','icon']){
   response=await page.request.post(base+'/api/admin/renders',{headers,data:{contentKey:record.key,mode}});assert.equal(response.status(),202,await response.text());jobs.push({...await response.json(),source:source.id});
  }
 }
 const deadline=Date.now()+240000;
 for(;;){
  const all=await(await page.request.get(base+'/api/admin/renders')).json();
  for(const job of jobs)Object.assign(job,all.find(j=>j.id===job.id));
  if(jobs.every(j=>['complete','failed'].includes(j.status)))break;
  if(Date.now()>deadline)throw new Error('Native render timeout: '+JSON.stringify(jobs));
  await page.waitForTimeout(1000);
 }
 assert.ok(jobs.every(j=>j.status==='complete'),JSON.stringify(jobs));
 for(const job of jobs){response=await page.request.get(base+'/api/admin/renders/'+job.id+'/image');assert.equal(response.status(),200);await fs.writeFile(path.join(out,'native-'+job.source+'-'+job.mode+'.png'),await response.body());}
 const data={contentKeys:imported.map(r=>r.key)};
 response=await page.request.post(base+'/api/admin/exports/native',{headers,data});assert.equal(response.status(),200,await response.text());const archive=await response.body();
 const repeat=await page.request.post(base+'/api/admin/exports/native',{headers,data:{contentKeys:[...data.contentKeys].reverse()}});assert.equal(repeat.status(),200);assert.deepEqual(await repeat.body(),archive,'Export must be byte-identical independent of selection order');
 await fs.writeFile(path.join(out,'eternia-native-catalog.tar.gz'),archive);
 await page.goto(base+'/admin');await page.locator('.revision[data-key="'+imported[0].key+'"]').click();await page.locator('#viewer canvas').waitFor();await page.waitForTimeout(2000);await page.screenshot({path:path.join(out,'native-workshop.png'),fullPage:true});
 assert.deepEqual(failures,[]);
 await fs.writeFile(path.join(out,'native-render-report.json'),JSON.stringify({fixture:true,imports:imported.map(r=>({id:r.definition.id,revision:r.definition.revision,blocks:r.prefab.blocks.length,entities:r.prefab.entities?.length||0})),jobs:jobs.map(j=>({id:j.id,source:j.source,mode:j.mode,status:j.status})),archiveBytes:archive.length},null,2)+'\n');
 console.log('Actual native content smoke passed: House (729 blocks), Potion Shelf (entity models), Aqua Lamp (multiple blocks); six PNGs, admin UI and deterministic native bundle. Local fixture authoring only.');
}finally{await browser.close();}
