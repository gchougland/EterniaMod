// Render bundled native prefabs through the local admin renderer into the game asset pack.
import fs from 'node:fs/promises';import path from 'node:path';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..'),base=process.env.TEST_BASE_URL||'http://127.0.0.1:3847';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('This script only uses local fixture authoring.');
let cookie='',csrf='';
async function api(url,data){const r=await fetch(base+url,{method:data?'POST':'GET',headers:{Cookie:cookie,...(data?{'Content-Type':'application/json','X-CSRF-Token':csrf}:{})},body:data?JSON.stringify(data):undefined});const set=r.headers.getSetCookie();if(set.length)cookie=set.map(v=>v.split(';')[0]).join('; ');if(!r.ok)throw new Error(await r.text());return r;}
let me=await(await api('/api/me')).json();if(!me.fixtures)throw new Error('Local fixture mode required');csrf=me.csrf;
await api('/api/dev/login',{});me=await(await api('/api/me')).json();csrf=me.csrf;
const existing=await(await api('/api/admin/content')).json(),jobs=[];
for(const [directory,kind]of [['Buildings','house'],['Props','prop']]){
 const folder=path.join(root,'src/main/resources/Server/EterniaMod',directory);
 for(const file of (await fs.readFile(path.join(folder,'catalog.index'),'utf8')).split(/\r?\n/).filter(v=>v.trim()&&!v.startsWith('#'))){
  const native=JSON.parse(await fs.readFile(path.join(folder,file),'utf8')),out=path.join(root,'src/main/resources/Common/UI/Custom/EterniaMod/Catalog',kind,native.id);
  if(process.argv.some(a=>a.startsWith('--id='))&&!process.argv.includes('--id='+native.id))continue;
  if(!process.argv.includes('--force')&&await fs.access(path.join(out,'screenshot.png')).then(()=>true,()=>false))continue;
  const id='eternia:'+kind+'/'+native.id,revision=Math.max(0,...existing.filter(r=>r.definition.id===id).map(r=>r.definition.revision))+1;
  const local=path.join(root,'src/main/resources/Server/Prefabs',native.prefabPath);
  const source=await fs.access(local).then(()=>local,()=>path.join(root,'../HytaleSourceCode/hytale-shared-source/HytaleAssets/Server/Prefabs',native.prefabPath));
  const prefab=JSON.parse(await fs.readFile(source,'utf8'));
  const record=await(await api('/api/admin/content',{definition:{schemaVersion:1,kind,id,revision,displayName:native.displayName,frontFacing:native.id==='founders_hall'?'South':'North',native},prefab})).json();
  for(const mode of ['icon','screenshot'])jobs.push({...await(await api('/api/admin/renders',{contentKey:record.key,mode})).json(),out,mode});
 }
}
for(const job of jobs){const deadline=Date.now()+240000;for(;;){const current=(await(await api('/api/admin/renders')).json()).find(j=>j.id===job.id);if(current.status==='failed')throw new Error(JSON.stringify(current));if(current.status==='complete')break;if(Date.now()>deadline)throw new Error('Render timed out');await new Promise(r=>setTimeout(r,1000));}
 await fs.mkdir(job.out,{recursive:true});await fs.writeFile(path.join(job.out,job.mode+'.png'),Buffer.from(await(await api('/api/admin/renders/'+job.id+'/image')).arrayBuffer()));console.log(path.relative(root,path.join(job.out,job.mode+'.png')));
}
