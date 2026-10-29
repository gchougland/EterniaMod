import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';
import {config} from '../server/config.js';import {createRepository} from '../server/repository.js';import {createApp} from '../server/app.js';import {contentUpload,seasonUpload} from '../server/game-sync.js';
const TOKEN='t'.repeat(40),ADMIN={uuid:'22222222-2222-4222-8222-222222222222',name:'Hexvane'};
const entry=()=>({kind:'prop',id:'garden_lamp',displayName:'Garden Lamp',description:'A warm light.',native:{rotationYaw:'None'},store:{sold:true,crowns:120,category:'Furnishings',itemId:'eternia:store/garden_lamp',itemRevision:1},season:{passId:'eternia:harvest',track:'PAID',level:4},prefab:{blocks:[{x:0,y:0,z:0,name:'Rock_Stone'}]},author:ADMIN});
const pass=()=>({id:'eternia:harvest',name:'Harvest',description:'Autumn rewards.',startsOn:'2026-11-01',endsOn:'2027-01-31',status:'live',thresholds:[1000,2000,3000,4000],rewards:[{track:'PAID',level:4,index:0,contentId:'eternia:prop/garden_lamp',kind:'QUANTITY',quantity:1,name:'Garden Lamp'}],author:ADMIN});
test('game uploads validate their fields',()=>{
 assert.equal(contentUpload(entry()).definition.id,'eternia:prop/garden_lamp');
 assert.throws(()=>contentUpload({...entry(),id:'../x'}),/lowercase/);
 assert.throws(()=>contentUpload({...entry(),store:{sold:true,crowns:0}}),/store.crowns/);
 assert.throws(()=>contentUpload({...entry(),author:{uuid:'nope'}}),/author/);
 assert.throws(()=>seasonUpload({...pass(),thresholds:[5,5]}),/increase/);
 assert.throws(()=>seasonUpload({...pass(),endsOn:'2026-10-01'}),/before/);
 assert.throws(()=>seasonUpload({...pass(),rewards:[...pass().rewards,pass().rewards[0]]}),/tier slot/);
 assert.throws(()=>seasonUpload({...pass(),rewards:[{...pass().rewards[0],level:9}]}),/Reward level/);
});
test('the game server uploads content and season passes with the bridge token only',async()=>{
 const temp=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-sync-'));const cfg=config({LOCAL_FIXTURES:'true',DATA_DIR:temp,GAME_BRIDGE_TOKEN:TOKEN});
 const repo=await createRepository(cfg),{app,queue,catalog}=createApp(cfg,repo),server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));const base='http://127.0.0.1:'+server.address().port;
 const post=(url,body,token=TOKEN)=>fetch(base+url,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});
 try{
  assert.equal((await post('/api/server/content',entry(),null)).status,401);
  assert.equal((await post('/api/server/content',entry(),'x'.repeat(40))).status,401);
  assert.equal((await fetch(base+'/api/server/content',{method:'POST',headers:{'Content-Type':'application/json',Cookie:'eternia.sid=forged'},body:JSON.stringify(entry())})).status,401);
  const first=await (await post('/api/server/content',entry())).json();assert.equal(first.revision,1);assert.equal(first.unchanged,false);
  const again=await (await post('/api/server/content',entry())).json();assert.equal(again.revision,1);assert.equal(again.unchanged,true);
  const changed=await (await post('/api/server/content',{...entry(),displayName:'Garden Lantern'})).json();assert.equal(changed.revision,2);
  const stored=await catalog.get(changed.key);assert.equal(stored.definition.store.crowns,120);assert.equal(stored.definition.season.level,4);assert.equal(stored.createdBy,'game:'+ADMIN.uuid);assert.ok(stored.prefab.blocks.length);
  assert.equal((await post('/api/server/content',{...entry(),store:{sold:true,crowns:-5}})).status,400);
  assert.equal((await post('/api/server/seasons',{...pass(),status:'draft'})).status,201);
  assert.deepEqual((await (await fetch(base+'/api/v1/seasons')).json()).seasons,[]);
  const live=await (await post('/api/server/seasons',pass())).json();assert.equal(live.revision,2);
  const seasons=(await (await fetch(base+'/api/v1/seasons')).json()).seasons;assert.equal(seasons.length,1);assert.equal(seasons[0].name,'Harvest');assert.equal(seasons[0].levels,4);assert.equal(seasons[0].thresholds.length,4);assert.equal(seasons[0].thresholds.at(-1),seasons[0].totalXp);assert.equal(seasons[0].rewards[0].name,'Garden Lamp');assert.equal(seasons[0].startsOn,'2026-11-01');
  assert.equal(seasons[0].author,undefined);
 }finally{await queue.close();await new Promise(r=>server.close(r));await repo.close();await fs.rm(temp,{recursive:true});}
});
test('uploads are refused when no bridge token is configured',async()=>{
 const temp=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-sync-'));const cfg=config({LOCAL_FIXTURES:'true',DATA_DIR:temp});
 const repo=await createRepository(cfg),{app,queue}=createApp(cfg,repo),server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));
 try{const res=await fetch('http://127.0.0.1:'+server.address().port+'/api/server/content',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '},body:'{}'});assert.equal(res.status,503);}
 finally{await queue.close();await new Promise(r=>server.close(r));await repo.close();await fs.rm(temp,{recursive:true});}
});
