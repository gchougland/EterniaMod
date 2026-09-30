import crypto from 'node:crypto';import fs from 'node:fs/promises';import path from 'node:path';
import {chromium} from 'playwright';import {HttpError} from './errors.js';
import {resolveBlockDef} from '../web/prefab-viewer/BlockCatalog.js';
export function createRenderQueue(cfg,repo,catalog){
 let active=false,closing=false,origin='',browser,admission=Promise.resolve();const tokens=new Map();
 function admit(fn){const next=admission.then(fn);admission=next.catch(()=>{});return next;}
 const outputDir=path.join(cfg.dataDir,'renders');
 const auth=(key,contentKey)=>{const match=tokens.get(key);return match&&(!contentKey||match===contentKey);};
 async function start(base){origin=base;await fs.mkdir(outputDir,{recursive:true});
 for(const job of await repo.list('jobs'))if(job.status==='running'){job.status='queued';await repo.put('jobs',job.id,job);}void drain();}
 async function enqueue(contentKey,mode,actor){return admit(async()=>{const record=await catalog.get(contentKey);if(!record?.prefab)throw new HttpError(404,'Prefab revision not found');
 if(!['screenshot','icon'].includes(mode))throw new HttpError(400,'Unknown render mode');
 if((await repo.list('jobs')).filter(x=>['queued','running'].includes(x.status)).length>=24)throw new HttpError(429,'Render queue is full');
 const job={id:crypto.randomUUID(),contentKey,mode,actor,status:'queued',attempts:0,createdAt:new Date().toISOString()};
 await repo.put('jobs',job.id,job);void drain();return job;});}
 async function retry(id){return admit(async()=>{const job=await repo.get('jobs',id);if(!job||job.status!=='failed')throw new HttpError(409,'Only failed jobs can be retried');if((await repo.list('jobs')).filter(x=>['queued','running'].includes(x.status)).length>=24)throw new HttpError(429,'Render queue is full');job.status='queued';job.attempts=0;delete job.error;await repo.put('jobs',id,job);void drain();return job;});}
 async function drain(){if(active||closing||!origin)return;active=true;
 try{while(!closing){const job=(await repo.list('jobs')).filter(x=>x.status==='queued').sort((a,b)=>a.createdAt.localeCompare(b.createdAt))[0];if(!job)break;
 job.status='running';job.attempts++;await repo.put('jobs',job.id,job);
 try{await capture(job);job.status='complete';job.completedAt=new Date().toISOString();}
 catch(error){job.status='failed';job.error=String(error.message||error).slice(0,300);}
 await repo.put('jobs',job.id,job);}}finally{active=false;}}
 async function capture(job){
  await fs.access(path.join(cfg.assetsDir,'catalog','block_catalog.json')).catch(()=>{throw new Error('Viewer assets missing. Run npm run sync:hytale-assets first.');});
  const record=await catalog.get(job.contentKey);
  const blockCatalog=JSON.parse(await fs.readFile(path.join(cfg.assetsDir,'catalog','block_catalog.json'),'utf8'));
  const blocksLower=new Map(Object.entries(blockCatalog).map(([id,value])=>[id.toLowerCase(),value]));
  const unknown=[...new Set([...(record.prefab.blocks||[]),...(record.prefab.fluids||[])].map(x=>String(x.name||'')).filter(x=>x&&x!=='Empty'&&!resolveBlockDef(blockCatalog,x,blocksLower,true)))];
  if(unknown.length)throw new Error('Missing native asset definitions: '+unknown.slice(0,8).join(', ')+'. Sync the required content pack before rendering.');
  const token=crypto.randomBytes(32).toString('hex');tokens.set(token,job.contentKey);let context;
  try{
   browser=browser?.isConnected()?browser:await chromium.launch({executablePath:await executable(),headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--disable-dev-shm-usage']});
   context=await browser.newContext({viewport:job.mode==='icon'?{width:512,height:512}:{width:1280,height:800},deviceScaleFactor:1,extraHTTPHeaders:{'X-Eternia-Render':token}});
   await context.route('**/*',route=>{const u=new URL(route.request().url());const allowed=u.origin===origin&&(u.pathname.startsWith('/internal/render')||u.pathname==='/internal/prefab/'+job.contentKey||u.pathname.startsWith('/prefab-viewer/')||u.pathname.startsWith('/hytale-assets/')||u.pathname.startsWith('/vendor/three/'));return allowed?route.continue():route.abort();});
   const page=await context.newPage();await page.goto(origin+'/internal/render?key='+job.contentKey+'&mode='+job.mode+'&front='+encodeURIComponent(record.definition.frontFacing||'North'),{waitUntil:'domcontentloaded',timeout:45000});
   await page.waitForFunction(()=>window.renderReady||window.renderError,null,{timeout:45000});
   const error=await page.evaluate(()=>window.renderError);if(error)throw new Error(error);
   const tmp=path.join(outputDir,job.id+'.tmp.png');await page.screenshot({path:tmp,omitBackground:job.mode==='icon'});await fs.rename(tmp,path.join(outputDir,job.id+'.png'));
  }finally{tokens.delete(token);await context?.close();}
 }
 async function executable(){
  if(cfg.chromiumPath)return cfg.chromiumPath;
  const candidates=[chromium.executablePath()];
  for(const dir of (process.env.PATH||'').split(path.delimiter))for(const name of ['chromium','chromium-browser','google-chrome','chrome.exe'])candidates.push(path.join(dir,name));
  for(const candidate of candidates){try{await fs.access(candidate);return candidate;}catch{}}
  throw new Error('Chromium is unavailable. Install it or configure CHROMIUM_PATH.');
 }
 async function nativeImages(records){
  const keys=new Set(records.map(r=>r.key)),images=new Map();
  const jobs=(await repo.list('jobs')).filter(j=>keys.has(j.contentKey)&&j.status==='complete'&&['icon','screenshot'].includes(j.mode)).sort((a,b)=>(a.createdAt||'').localeCompare(b.createdAt||'')||a.id.localeCompare(b.id));
  for(const job of jobs){if(!/^[0-9a-f-]{36}$/i.test(job.id))throw new Error('Invalid stored render ID');images.set(job.contentKey+':'+job.mode,await fs.readFile(path.join(outputDir,job.id+'.png')));}
  return images;
 }
 return {start,enqueue,retry,auth,nativeImages,list:()=>repo.list('jobs'),get:id=>repo.get('jobs',id),outputPath:id=>path.join(outputDir,id+'.png'),async close(){closing=true;await browser?.close();}};
}
