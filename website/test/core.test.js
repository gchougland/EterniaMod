import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';
import {config} from '../server/config.js';import {validateContent,createCatalog} from '../server/catalog.js';import {createRepository} from '../server/repository.js';import {createApp} from '../server/app.js';import {createOidc} from '../server/auth.js';
const payload=()=>({definition:{schemaVersion:1,kind:'prop',id:'eternia:prop/test_chair',revision:1,displayName:'Test Chair'},prefab:{blocks:[{x:0,y:0,z:0,name:'Rock_Stone'}]}});
test('production rejects fixture mode, fixture issuers, callback mismatch and missing persistent configuration',()=>{assert.throws(()=>config({NODE_ENV:'production',LOCAL_FIXTURES:'true'}),/forbidden/);assert.throws(()=>config({SESSION_SECRET:'x'.repeat(32)}),/DATABASE_URL/);assert.throws(()=>config({LOCAL_FIXTURES:'true',PUBLIC_BASE_URL:'https://not-local.example'}),/loopback/);assert.throws(()=>config({NODE_ENV:'production',PUBLIC_BASE_URL:'https://eternia.example',SESSION_SECRET:'x'.repeat(32),DATABASE_URL:'postgres://local',HYTALE_OIDC_ISSUER:'https://fixture.example'}),/fixture issuers/);assert.throws(()=>config({LOCAL_FIXTURES:'true',HYTALE_OIDC_REDIRECT_URI:'https://evil.example/auth/callback'}),/callback must match/);});
test('content validation rejects traversal, unsafe cells and unknown kinds',()=>{assert.equal(validateContent(payload()).valid,true);const bad=payload();bad.definition.prefab='../private.json';assert.equal(validateContent(bad).valid,false);bad.definition.prefab='safe.json';bad.prefab.blocks[0].x=Infinity;assert.equal(validateContent(bad).valid,false);});
test('OIDC requires a configured new client and validates state before exchange',async()=>{await assert.rejects(createOidc({}).start(),/not configured/);const auth=createOidc({issuer:'https://provider.example',clientId:'a',clientSecret:'b',redirectUri:'https://site.example/auth/callback'});await assert.rejects(auth.finish({state:'bad',code:'x'},{state:'expected',createdAt:Date.now()}),/expired or invalid/);});
test('immutable import survives restart and concurrent duplicate imports',async()=>{const temp=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-web-'));const cfg={fixtures:true,dataDir:temp};let repo=await createRepository(cfg);const catalog=createCatalog(repo);const results=await Promise.allSettled([catalog.import(payload(),'admin'),catalog.import(payload(),'admin')]);assert.equal(results.filter(r=>r.status==='fulfilled').length,1);await repo.close();repo=await createRepository(cfg);assert.equal((await repo.list('content')).length,1);await repo.close();await fs.rm(temp,{recursive:true});});
test('HTTP boundaries: identity headers rejected, CSRF required, admin private, revisions and jobs authorized',async()=>{const temp=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-http-'));const cfg=config({LOCAL_FIXTURES:'true',DATA_DIR:temp});const repo=await createRepository(cfg),{app,queue}=createApp(cfg,repo);const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));const base='http://127.0.0.1:'+server.address().port;let cookie='',csrf='';
 async function request(url,options={}){const res=await fetch(base+url,{...options,headers:{Cookie:cookie,'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{}),...options.headers}});const c=res.headers.get('set-cookie');if(c)cookie=c.split(';')[0];return res;}
 try{
 assert.equal((await request('/api/admin/content',{headers:{'X-Player-Uuid':'11111111-1111-4111-8111-111111111111'}})).status,403);
 assert.equal((await request('/prefab-viewer/PrefabViewer.js')).status,403);
 assert.equal((await request('/internal/render')).status,403);
 assert.equal((await request('/api/admin/exports/native',{method:'POST',body:'{}'})).status,403);
 assert.equal((await request('/api/dev/login',{method:'POST'})).status,403);
 csrf=(await (await request('/api/me')).json()).csrf;
 assert.equal((await request('/api/dev/login',{method:'POST'})).status,200);
 csrf=(await (await request('/api/me')).json()).csrf;
 const account=await (await request('/api/account')).json();assert.equal(account.source,'local-fixture');
 const record=await (await request('/api/admin/content',{method:'POST',body:JSON.stringify(payload())})).json();assert.ok(record.key);
 assert.equal((await request('/api/admin/content',{method:'POST',body:JSON.stringify(payload())})).status,409);
 assert.equal((await request('/api/admin/exports/native',{method:'POST',headers:{'X-CSRF-Token':''},body:JSON.stringify({contentKeys:[record.key]})})).status,403);
 const bundle=await request('/api/admin/exports/native',{method:'POST',body:JSON.stringify({contentKeys:[record.key]})});assert.equal(bundle.status,200);assert.equal(bundle.headers.get('content-type'),'application/gzip');assert.ok((await bundle.arrayBuffer()).byteLength>100);
 assert.equal((await request('/api/admin/renders',{method:'POST',body:JSON.stringify({contentKey:record.key,mode:'remote_url'})})).status,400);
 assert.equal((await request('/api/admin/renders',{method:'POST',body:JSON.stringify({contentKey:record.key,mode:'icon'})})).status,202);
 assert.equal((await request('/api/admin/content',{method:'POST',headers:{Origin:'https://evil.example'},body:JSON.stringify(payload())})).status,403);
 }finally{await queue.close();await new Promise(r=>server.close(r));await repo.close();await fs.rm(temp,{recursive:true});}
});
