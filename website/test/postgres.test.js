import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import pg from 'pg';
import {config} from '../server/config.js';
import {createRepository} from '../server/repository.js';
import {createCatalog} from '../server/catalog.js';
import {createApp} from '../server/app.js';

// These checks write only random test records. They never infer an endpoint from DATABASE_URL.
const databaseUrl=process.env.ETERNIA_WEB_TEST_DATABASE_URL;
const enabled=Boolean(databaseUrl)&&process.env.ETERNIA_TEST_DATABASE_ALLOW_WRITES==='true';
const skip=enabled?false:'Explicit isolated ETERNIA_WEB_TEST_DATABASE_URL and ETERNIA_TEST_DATABASE_ALLOW_WRITES=true required';

async function migrate(pool){
 const client=await pool.connect();
 try{
  await client.query('BEGIN');
  await client.query(await fs.readFile(new URL('../migrations/001_web.sql',import.meta.url),'utf8'));
  await client.query('COMMIT');
 }catch(error){await client.query('ROLLBACK');throw error;}
 finally{client.release();}
}

test('PostgreSQL catalog uniqueness, rollback and render jobs survive repository restart',{skip},async()=>{
 const cfg={fixtures:false,databaseUrl};
 let repo=await createRepository(cfg),other=await createRepository(cfg);
 const contentId='eternia:prop/pg_'+crypto.randomUUID().replaceAll('-','');
 const jobId=crypto.randomUUID(),rollbackId=crypto.randomUUID();
 const input={definition:{schemaVersion:1,kind:'prop',id:contentId,revision:1,displayName:'Database test chair'},prefab:{blocks:[{x:0,y:0,z:0,name:'Rock_Stone'}]}};
 try{
  await migrate(repo.pool);await migrate(repo.pool);
  const results=await Promise.allSettled([
   createCatalog(repo).import(input,'isolated-pg-test'),
   createCatalog(other).import(input,'isolated-pg-test')
  ]);
  const successes=results.filter(r=>r.status==='fulfilled');
  assert.equal(successes.length,1,'The database must reject a duplicate revision across independent repositories');
  const failure=results.find(r=>r.status==='rejected').reason;
  assert.ok(failure.code==='23505'||failure.status===409);
  const record=successes[0].value;
  const job={id:jobId,contentKey:record.key,mode:'icon',actor:'isolated-pg-test',status:'queued',attempts:0,createdAt:new Date().toISOString()};
  await repo.put('jobs',job.id,job);
  await other.put('jobs',job.id,{...job,status:'running',attempts:1});
  await assert.rejects(repo.put('content',record.key,{...record,definition:{...record.definition,displayName:'Cannot replace immutable revision'}}),error=>error.code==='23505');
  const transaction=await repo.pool.connect();
  try{
   await transaction.query('BEGIN');
   await transaction.query('INSERT INTO eternia_web.render_jobs(id,document) VALUES($1,$2)',[rollbackId,{id:rollbackId,status:'must disappear'}]);
   await assert.rejects(transaction.query('INSERT INTO eternia_web.content_revisions(key,content_id,revision,document) VALUES($1,$2,$3,$4)',[crypto.randomUUID(),contentId,1,record]),error=>error.code==='23505');
   await transaction.query('ROLLBACK');
  }finally{transaction.release();}
  await repo.close();await other.close();
  repo=await createRepository(cfg);other=null;
  assert.deepEqual(await repo.get('content',record.key),record);
  assert.deepEqual(await repo.get('jobs',job.id),{...job,status:'running',attempts:1});
  assert.equal(await repo.get('jobs',rollbackId),undefined);
  const schema=await repo.pool.query("SELECT table_schema FROM information_schema.tables WHERE table_name IN ('content_revisions','render_jobs','sessions') AND table_schema NOT IN ('pg_catalog','information_schema')");
  assert.ok(schema.rows.length>=3);
  assert.ok(schema.rows.every(row=>row.table_schema==='eternia_web'),'Website tables must stay in the website schema');
 }finally{
  await repo.pool.query('DELETE FROM eternia_web.render_jobs WHERE id=ANY($1::uuid[])',[[jobId,rollbackId]]);
  await repo.pool.query('DELETE FROM eternia_web.content_revisions WHERE content_id=$1',[contentId]);
  await repo.close();await other?.close();
 }
});

test('PostgreSQL HTTP sessions survive app restart and apply current admin permissions',{skip},async()=>{
 const dataDir=await fs.mkdtemp(path.join(os.tmpdir(),'eternia-pg-session-'));
 const uuid=crypto.randomUUID(),csrfSeed=crypto.randomBytes(24).toString('hex');
 const env={DATABASE_URL:databaseUrl,SESSION_SECRET:crypto.randomBytes(32).toString('hex'),DATA_DIR:dataDir,ADMIN_HYTALE_UUIDS:uuid};
 let repo,server,queue,cookie='',sid;
 async function boot(admins){
  repo=await createRepository(config({...env,ADMIN_HYTALE_UUIDS:admins}));
  await migrate(repo.pool);
  const created=createApp(config({...env,ADMIN_HYTALE_UUIDS:admins}),repo);queue=created.queue;
  server=created.app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
  return 'http://127.0.0.1:'+server.address().port;
 }
 async function stop(){
  await queue?.close();
  if(server){server.closeAllConnections();await new Promise(resolve=>server.close(resolve));server=null;}
  if(repo){await repo.close();repo=null;}
 }
 async function get(base,url){
  const response=await fetch(base+url,{headers:{Cookie:cookie}});
  const value=response.headers.get('set-cookie');if(value)cookie=value.split(';')[0];
  return response;
 }
 try{
  let base=await boot(uuid);
  const first=await get(base,'/api/me');assert.equal(first.status,200);
  const anonymous=await first.json();assert.equal(anonymous.user,null);assert.ok(cookie);
  const rawCookie=decodeURIComponent(cookie.slice(cookie.indexOf('=')+1));
  assert.ok(rawCookie.startsWith('s:'));sid=rawCookie.slice(2,rawCookie.lastIndexOf('.'));
  const stored=(await repo.pool.query('SELECT sess FROM eternia_web.sessions WHERE sid=$1',[sid])).rows[0].sess;
  assert.equal(stored.csrf,anonymous.csrf);
  // Seed only this test's database session; no development login route or identity bypass is added.
  stored.user={uuid,name:'PostgreSQL Test Player',admin:true};stored.csrf=csrfSeed;
  await repo.pool.query('UPDATE eternia_web.sessions SET sess=$2 WHERE sid=$1',[sid,stored]);
  await stop();base=await boot(uuid);
  const resumed=await (await get(base,'/api/me')).json();
  assert.equal(resumed.user.uuid,uuid);assert.equal(resumed.user.admin,true);assert.equal(resumed.csrf,csrfSeed);
  assert.equal((await get(base,'/api/admin/content')).status,200);
  assert.equal((await fetch(base+'/api/dev/login',{method:'POST',headers:{Cookie:cookie,'X-CSRF-Token':csrfSeed}})).status,404,'Real database mode must not expose fixture sign-in');
  await stop();base=await boot('');
  const revoked=await (await get(base,'/api/me')).json();
  assert.equal(revoked.user.uuid,uuid);assert.equal(revoked.user.admin,false);
  assert.equal((await get(base,'/api/admin/content')).status,403,'A persisted old admin flag must not retain admin authority');
  assert.equal((await fetch(base+'/auth/logout',{method:'POST',headers:{Cookie:cookie,'X-CSRF-Token':csrfSeed}})).status,200);
  assert.equal((await repo.pool.query('SELECT sid FROM eternia_web.sessions WHERE sid=$1',[sid])).rowCount,0);
 }finally{
  if(sid&&repo)await repo.pool.query('DELETE FROM eternia_web.sessions WHERE sid=$1',[sid]);
  await stop();await fs.rm(dataDir,{recursive:true});
 }
});
