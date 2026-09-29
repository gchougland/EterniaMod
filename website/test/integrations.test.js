import test from 'node:test';import assert from 'node:assert/strict';import http from 'node:http';import express from 'express';import crypto from 'node:crypto';import {gunzipSync} from 'node:zlib';
import {mountTebexRelay} from '../server/tebex-relay.js';import {exportNativeBundle} from '../server/native-export.js';
import {resolveBlockDef} from '../web/prefab-viewer/BlockCatalog.js';
const listen=async app=>{const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));return {server,url:'http://127.0.0.1:'+server.address().port};};
test('public relay preserves signed raw bytes, forwards only bridge authority, and needs no browser session',async()=>{
 const body=Buffer.from('{  "id": "local-validation", "unicode": "é" }\n'),secret='synthetic-test-secret';
 const signature=crypto.createHmac('sha256',secret).update(crypto.createHash('sha256').update(body).digest('hex')).digest('hex');let received;
 const upstream=await listen(http.createServer(async(req,res)=>{const chunks=[];for await(const c of req)chunks.push(c);received={url:req.url,headers:req.headers,body:Buffer.concat(chunks)};res.setHeader('Content-Type','application/json');const expected=crypto.createHmac('sha256',secret).update(crypto.createHash('sha256').update(received.body).digest('hex')).digest('hex');if(req.headers['x-signature']!==expected){res.statusCode=401;res.end('{"status":"invalid_signature"}');return;}res.end('{"id":"local-validation","secret":"must-not-leak"}');}));
 const app=express();mountTebexRelay(app,{bridgeUrl:upstream.url,bridgeToken:'synthetic-bridge-token'});app.use(express.json());app.use((req,res)=>res.sendStatus(403));const web=await listen(app);
 try{
  const response=await fetch(web.url+'/webhooks/tebex',{method:'POST',headers:{'Content-Type':'application/json','X-Signature':signature,Cookie:'do-not-forward','X-Player-Uuid':'do-not-forward'},body});
  assert.equal(response.status,200);assert.deepEqual(await response.json(),{id:'local-validation'});assert.deepEqual(received.body,body);assert.equal(received.url,'/v1/commerce/tebex');assert.equal(received.headers['x-signature'],signature);assert.equal(received.headers.authorization,'Bearer synthetic-bridge-token');assert.equal(received.headers.cookie,undefined);assert.equal(received.headers['x-player-uuid'],undefined);
  assert.equal((await fetch(web.url+'/webhooks/tebex')).status,405);
  assert.equal((await fetch(web.url+'/webhooks/tebex',{method:'POST',headers:{'X-Signature':signature},body:Buffer.concat([body,Buffer.from(' ')])})).status,401,'A signed fixture cannot authorize different bytes');
  assert.equal((await fetch(web.url+'/webhooks/tebex',{method:'POST',body:'{}'})).status,401);
  assert.equal((await fetch(web.url+'/webhooks/tebex',{method:'POST',headers:{'X-Signature':signature},body:Buffer.alloc(1024*1024+1)})).status,413);
 }finally{web.server.closeAllConnections();upstream.server.closeAllConnections();await Promise.all([new Promise(r=>web.server.close(r)),new Promise(r=>upstream.server.close(r))]);}
});
test('unconfigured relay refuses fulfillment without a fixture bypass',async()=>{const app=express();mountTebexRelay(app,{fixtures:true});const web=await listen(app);try{assert.equal((await fetch(web.url+'/webhooks/tebex',{method:'POST',body:'{}'})).status,503);}finally{await new Promise(r=>web.server.close(r));}});
const prop={definition:{schemaVersion:1,kind:'prop',id:'eternia:prop/aqua_lamp',revision:2,displayName:'Aqua Lamp'},prefab:{version:8,blockIdVersion:11,anchorX:0,anchorY:0,anchorZ:0,blocks:[{x:0,y:0,z:0,name:'Rock_Stone'}]}};
test('render preflight resolves native state IDs, nested state overrides and rejects unknown states',()=>{
 const wet={textures:{All:'wet.png'}};
 const bottom={textures:{All:'bottom.png'},states:{Wet:wet}};
 const blocks={Wall:{textures:{All:'wall.png'},states:{Bottom:bottom}}};
 assert.equal(resolveBlockDef(blocks,'*Wall_State_Definitions_Bottom',undefined,true).textures.All,'bottom.png');
 assert.equal(resolveBlockDef(blocks,'*Wall_State_Definitions_Bottom_State_Definitions_Wet',undefined,true).textures.All,'wet.png');
 assert.equal(resolveBlockDef(blocks,'*Wall_State_Definitions_Missing',undefined,true),null);
});
test('native export converts typed fields and creates deterministic indexed asset bundle',()=>{
 const house={definition:{schemaVersion:1,kind:'house',id:'eternia:house/hub_house',revision:3,displayName:'House',native:{managementBlockLocalPos:[1,2,2],spawnLocalPos:[0,1,0],plotAnchorOffset:[4,0,4]}},prefab:prop.prefab};
 const a=exportNativeBundle([prop,house]),b=exportNativeBundle([house,prop]);assert.deepEqual(a.bytes,b.bytes);assert.ok(gunzipSync(a.bytes).length>1024);
 const imageRecord={...house,key:'house-fixture'},png=Buffer.from([137,80,78,71,13,10,26,10,0,0,0,0]);
 const illustrated=exportNativeBundle([imageRecord],new Map([['house-fixture:icon',png],['house-fixture:screenshot',png]]));
 assert.deepEqual(illustrated.files.get('Common/UI/Custom/EterniaMod/Catalog/house/hub_house/icon.png'),png);
 assert.deepEqual(illustrated.files.get('Common/UI/Custom/EterniaMod/Catalog/house/hub_house/screenshot.png'),png);
 assert.throws(()=>exportNativeBundle([imageRecord],new Map([['house-fixture:icon',Buffer.from('invalid')]])),/Invalid rendered/);
 const definition=JSON.parse(a.files.get('Server/EterniaMod/Buildings/hub_house.json'));assert.deepEqual(definition.plotAnchorOffset,[4,0,4]);assert.deepEqual(definition.spawnLocalPos,[0,1,0]);assert.ok(a.files.has('Server/Prefabs/'+definition.prefabPath));assert.equal(a.files.get('Server/EterniaMod/Buildings/catalog.index').toString(),'hub_house.json\n');
 assert.throws(()=>exportNativeBundle([prop,prop]),/one revision/);assert.throws(()=>exportNativeBundle([{...house,definition:{...house.definition,native:{}}}]),/managementBlockLocalPos/);assert.throws(()=>exportNativeBundle([{...prop,definition:{...prop.definition,id:'eternia:prop/../../oops'}}]),/safe_catalog_id/);
});
