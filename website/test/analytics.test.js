import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';import http from 'node:http';
import {config} from '../server/config.js';import {createRepository} from '../server/repository.js';import {createApp} from '../server/app.js';import {createBridge} from '../server/bridge.js';

test('analytics downloads are admin only, checked, and served as CSV attachments',async()=>{const temp=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-analytics-'));const cfg=config({LOCAL_FIXTURES:'true',DATA_DIR:temp});const repo=await createRepository(cfg),{app,queue}=createApp(cfg,repo);const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));const base='http://127.0.0.1:'+server.address().port;let cookie='',csrf='';
 async function request(url,options={}){const res=await fetch(base+url,{...options,headers:{Cookie:cookie,'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{}),...options.headers}});const c=res.headers.get('set-cookie');if(c)cookie=c.split(';')[0];return res;}
 try{
  assert.equal((await request('/api/admin/analytics/daily.csv?from=2026-11-01&to=2026-11-07')).status,403);
  assert.equal((await request('/admin/analytics')).status,403);
  csrf=(await (await request('/api/me')).json()).csrf;
  assert.equal((await request('/api/dev/login',{method:'POST'})).status,200);
  assert.equal((await request('/admin/analytics')).status,200);
  const ok=await request('/api/admin/analytics/weekly.csv?from=2026-11-01&to=2026-11-07');
  assert.equal(ok.status,200);assert.match(ok.headers.get('content-type'),/^text\/csv/);
  assert.match(ok.headers.get('content-disposition'),/attachment; filename="eternia-weekly-2026-11-01-to-2026-11-07.csv"/);
  assert.equal(ok.headers.get('cache-control'),'no-store');assert.match(await ok.text(),/weekly,2026-11-01,2026-11-07/);
  assert.equal((await request('/api/admin/analytics/passwords.csv?from=2026-11-01&to=2026-11-07')).status,404);
  assert.equal((await request('/api/admin/analytics/daily.csv?from=yesterday&to=2026-11-07')).status,400);
  assert.equal((await request('/api/admin/analytics/daily.csv?from=2026-11-08&to=2026-11-07')).status,400);
 }finally{await queue.close();await new Promise(r=>server.close(r));await repo.close();await fs.rm(temp,{recursive:true});}
});

test('the bridge asks the game for analytics with its token and explains failures',async()=>{let seen;const game=http.createServer((req,res)=>{seen=req;const u=new URL(req.url,'http://x');
  if(u.searchParams.get('report')==='old'){res.writeHead(404);res.end();return;}
  if(u.searchParams.get('from')==='bad'){res.writeHead(400,{'Content-Type':'application/json'});res.end('{"error":"Dates must look like 2026-11-30"}');return;}
  res.writeHead(200,{'Content-Type':'text/csv'});res.end('period_start\r\n2026-11-01\r\n');});
 await new Promise(r=>game.listen(0,'127.0.0.1',r));
 try{const bridge=createBridge({bridgeUrl:'http://127.0.0.1:'+game.address().port+'/',bridgeToken:'secret'});
  assert.equal(await bridge.getAnalytics('daily','2026-11-01','2026-11-02'),'period_start\r\n2026-11-01\r\n');
  assert.equal(seen.headers.authorization,'Bearer secret');assert.equal(seen.url,'/v1/analytics?report=daily&from=2026-11-01&to=2026-11-02');
  await assert.rejects(bridge.getAnalytics('old','2026-11-01','2026-11-02'),e=>e.status===503&&/Update the mod/.test(e.message));
  await assert.rejects(bridge.getAnalytics('daily','bad','2026-11-02'),e=>e.status===400&&/2026-11-30/.test(e.message));
  await assert.rejects(createBridge({}).getAnalytics('daily','2026-11-01','2026-11-02'),e=>e.status===503);
 }finally{await new Promise(r=>game.close(r));}
});
