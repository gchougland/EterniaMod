import test from 'node:test';import assert from 'node:assert/strict';import http from 'node:http';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';
import express from 'express';
import {createPlayersOnline,mountPlayersOnline} from '../server/players-online.js';
import {createBridge} from '../server/bridge.js';
import {canonicalHost,twinHost} from '../server/canonical-host.js';
import {config} from '../server/config.js';import {createRepository} from '../server/repository.js';import {createApp} from '../server/app.js';
import {onlineText} from '../web/js/players-online.js';

const listen=async app=>{const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));return {server,url:'http://127.0.0.1:'+server.address().port,port:server.address().port};};
const close=s=>{s.closeAllConnections?.();return new Promise(r=>s.close(r));};
// fetch cannot set Host, so raw requests check the www redirect.
const raw=(port,host,urlPath,method='GET')=>new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port,path:urlPath,method,headers:{Host:host}},res=>{res.resume();resolve({status:res.statusCode,location:res.headers.location});});req.on('error',reject);req.end();});

test('player count reads the bridge once per cache window and returns only a number',async()=>{
 let time=0,calls=0;const bridge={async getPlayersOnline(){calls++;return {source:'game',online:42,names:['Secret']};}};
 const counter=createPlayersOnline(bridge,{ttl:30000,now:()=>time});
 const results=await Promise.all(Array.from({length:20},()=>counter.get()));
 assert.equal(calls,1);assert.deepEqual(Object.keys(results[0]).sort(),['online','status','updatedAt']);assert.equal(results[0].online,42);
 time=29999;await counter.get();assert.equal(calls,1);time=30001;await counter.get();assert.equal(calls,2);
});
test('an offline or broken bridge becomes a calm resting state that is also cached',async()=>{
 let time=0,calls=0;const counter=createPlayersOnline({async getPlayersOnline(){calls++;throw new Error('connect ECONNREFUSED 10.0.0.5:8443');}},{offlineTtl:15000,now:()=>time});
 const value=await counter.get();assert.equal(value.status,'resting');assert.equal(value.online,undefined);assert.ok(!JSON.stringify(value).includes('ECONNREFUSED'));
 await counter.get();assert.equal(calls,1);time=15001;await counter.get();assert.equal(calls,2);
 for(const bad of [{online:-3},{online:'many'},{online:1.5},null]){const c=createPlayersOnline({async getPlayersOnline(){return bad;}});assert.equal((await c.get()).status,'resting');}
 // Unconfigured production bridge rests too.
 const unconfigured=createPlayersOnline(createBridge({fixtures:false,bridgeUrl:'',bridgeToken:''}));assert.equal((await unconfigured.get()).status,'resting');
});
test('public endpoint answers without a session and the bridge client sends the bearer token',async()=>{
 let seen;const game=await listen(http.createServer((req,res)=>{seen={url:req.url,auth:req.headers.authorization};res.setHeader('Content-Type','application/json');res.end('{"source":"game","online":7}');}));
 const app=express();mountPlayersOnline(app,createBridge({fixtures:false,bridgeUrl:game.url+'/',bridgeToken:'synthetic-bridge-token'}));const web=await listen(app);
 try{
  const res=await fetch(web.url+'/api/v1/players-online');assert.equal(res.status,200);assert.match(res.headers.get('cache-control'),/max-age/);
  const body=await res.json();assert.equal(body.status,'online');assert.equal(body.online,7);
  assert.equal(seen.url,'/v1/status/players');assert.equal(seen.auth,'Bearer synthetic-bridge-token');
 }finally{await close(web.server);await close(game.server);}
 const offline=express();mountPlayersOnline(offline,createBridge({fixtures:false,bridgeUrl:'http://127.0.0.1:9',bridgeToken:'synthetic-bridge-token'}));const site=await listen(offline);
 try{const res=await fetch(site.url+'/api/v1/players-online');assert.equal(res.status,200);assert.deepEqual(Object.keys(await res.json()).sort(),['status','updatedAt']);}finally{await close(site.server);}
});
test('welcome page wording never shows a broken looking zero',()=>{
 assert.equal(onlineText({status:'online',online:42}).text,'42 adventurers online now');
 assert.equal(onlineText({status:'online',online:1}).text,'1 adventurer online now');
 assert.equal(onlineText({status:'online',online:0}).state,'quiet');
 assert.equal(onlineText({status:'resting'}).text,'The realm is resting. Check back soon.');
 assert.equal(onlineText(null).state,'resting');
 for(const v of ['online','quiet','resting'].map(s=>onlineText(s==='online'?{status:'online',online:3}:s==='quiet'?{status:'online',online:0}:null).text))assert.ok(!/ [-–—] /.test(v));
});
test('www twin hosts redirect to the one public address, keeping path and method',async()=>{
 assert.equal(twinHost('https://eternia-hytale.com'),'www.eternia-hytale.com');assert.equal(twinHost('https://www.eternia-hytale.com'),'eternia-hytale.com');assert.equal(twinHost('http://127.0.0.1:3847'),null);
 const cfg={baseUrl:'https://eternia-hytale.com',production:true};const app=express();app.use(canonicalHost(cfg));app.all('*',(req,res)=>res.send('ok'));const web=await listen(app);
 try{
  assert.deepEqual(await raw(web.port,'www.eternia-hytale.com','/store?checkout=returned'),{status:301,location:'https://eternia-hytale.com/store?checkout=returned'});
  assert.deepEqual(await raw(web.port,'WWW.Eternia-Hytale.com','/'),{status:301,location:'https://eternia-hytale.com/'});
  assert.equal((await raw(web.port,'www.eternia-hytale.com','/webhooks/tebex','POST')).status,308);
  assert.equal((await raw(web.port,'eternia-hytale.com','/')).status,200);
  assert.equal((await raw(web.port,'healthcheck.railway.app','/api/v1/health')).status,200,'Railway health checks are never redirected');
  assert.equal((await raw(web.port,'www.evil.example','/')).status,200,'Unrelated hosts are not turned into redirects');
 }finally{await close(web.server);}
});
test('the full app redirects www before sessions and serves the fixture count',async()=>{
 const temp=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-www-'));const cfg=config({LOCAL_FIXTURES:'true',DATA_DIR:temp,PUBLIC_BASE_URL:'http://localhost:3847',FIXTURE_PLAYERS_ONLINE:'5'});
 const repo=await createRepository(cfg),{app,queue}=createApp(cfg,repo);const web=await listen(app);
 try{
  const moved=await raw(web.port,'www.localhost:3847','/account');assert.equal(moved.status,301);assert.equal(moved.location,'http://localhost:3847/account');
  assert.equal((await raw(web.port,'localhost:3847','/account')).status,200);
  assert.deepEqual((await (await fetch(web.url+'/api/v1/players-online')).json()).online,5);
 }finally{await queue.close();await close(web.server);await repo.close();await fs.rm(temp,{recursive:true});}
});
