import crypto from 'node:crypto';import express from 'express';import {HttpError,route} from './errors.js';import {UUID} from './auth.js';
// Server-to-server uploads from the game. The game checks admin permission; the website trusts only the shared bridge token.
const SLUG=/^[a-z0-9][a-z0-9_-]{0,95}$/,DATE=/^\d{4}-\d{2}-\d{2}$/,CONTENT=/^eternia:[a-z0-9_/-]{1,160}$/;
const text=(v,name,max,required=false)=>{if(v===undefined||v===null||v===''){if(required)throw new HttpError(400,name+' is required');return '';}if(typeof v!=='string'||v.length>max)throw new HttpError(400,name+' must be text up to '+max+' characters');return v.trim();};
const int=(v,name,min,max)=>{if(!Number.isSafeInteger(v)||v<min||v>max)throw new HttpError(400,name+' must be a whole number from '+min+' to '+max);return v;};
const vec=(v,name)=>{if(v===undefined||v===null)return undefined;if(!Array.isArray(v)||v.length!==3||!v.every(n=>Number.isSafeInteger(n)&&Math.abs(n)<=4096))throw new HttpError(400,name+' must contain three integers');return v;};
const date=(v,name)=>{if(v===undefined||v===null||v==='')return null;if(typeof v!=='string'||!DATE.test(v)||Number.isNaN(Date.parse(v+'T00:00:00Z')))throw new HttpError(400,name+' must be a date like 2026-11-01');return v;};
function author(body){const a=body?.author;if(!a||!UUID.test(String(a.uuid||'')))throw new HttpError(400,'author.uuid must be the admin profile UUID');return {uuid:String(a.uuid).toLowerCase(),name:text(a.name,'author.name',64)||'Admin'};}
export function serverGate(cfg){
 const expected=cfg.bridgeToken&&cfg.bridgeToken.length>=32?Buffer.from('Bearer '+cfg.bridgeToken):null;
 return (req,res,next)=>{const got=Buffer.from(String(req.get('Authorization')||''));if(!expected)return res.status(503).json({error:'Game connection is not configured.'});
  if(got.length!==expected.length||!crypto.timingSafeEqual(got,expected))return res.status(401).json({error:'Unauthorized'});next();};
}
export function contentUpload(body){
 const kind=body?.kind;if(!['house','prop','addition'].includes(kind))throw new HttpError(400,'kind must be house, prop or addition');
 if(!SLUG.test(body.id||''))throw new HttpError(400,'id must use lowercase letters, digits, underscores or hyphens');
 const n=body.native||{},rotation=n.rotationYaw||'None';if(!['None','Ninety','OneEighty','TwoSeventy'].includes(rotation))throw new HttpError(400,'Invalid native.rotationYaw');
 const native={plotAnchorOffset:vec(n.plotAnchorOffset,'native.plotAnchorOffset')||[0,0,0],rotationYaw:rotation};
 if(kind==='house'){native.spawnLocalPos=vec(n.spawnLocalPos,'native.spawnLocalPos')||[0,1,0];native.housingKind=n.housingKind==='guild'?'guild':'personal';const m=vec(n.managementBlockLocalPos,'native.managementBlockLocalPos');if(m)native.managementBlockLocalPos=m;}
 const s=body.store||{},sold=s.sold===true;
 const store={sold,crowns:sold?int(s.crowns,'store.crowns',1,1000000):0,category:text(s.category,'store.category',40)||'Furnishings'};
 if(s.itemId!==undefined){if(!CONTENT.test(s.itemId))throw new HttpError(400,'Invalid store.itemId');store.itemId=s.itemId;store.itemRevision=int(s.itemRevision,'store.itemRevision',1,2147483647);}
 let season=null;if(body.season){const p=body.season;if(!CONTENT.test(p.passId||''))throw new HttpError(400,'Invalid season.passId');if(!['FREE','PAID'].includes(p.track))throw new HttpError(400,'season.track must be FREE or PAID');season={passId:p.passId,track:p.track,level:int(p.level,'season.level',1,1000)};}
 if(!body.prefab||typeof body.prefab!=='object'||Array.isArray(body.prefab))throw new HttpError(400,'prefab must be the native prefab JSON');
 return {definition:{schemaVersion:1,kind,id:'eternia:'+kind+'/'+body.id,revision:1,displayName:text(body.displayName,'displayName',80,true),description:text(body.description,'description',600),native,store,season,source:'game',author:author(body)},prefab:body.prefab};
}
export function seasonUpload(body){
 const match=/^eternia:([a-z0-9][a-z0-9_-]{0,95})$/.exec(body?.id||'');if(!match)throw new HttpError(400,'Season id must look like eternia:<name>');
 const thresholds=body.thresholds;if(!Array.isArray(thresholds)||!thresholds.length||thresholds.length>1000)throw new HttpError(400,'A season needs 1 to 1000 levels');
 let previous=0;for(const t of thresholds){int(t,'Level XP',1,1000000000);if(t<=previous)throw new HttpError(400,'Level XP must increase');previous=t;}
 const rewards=Array.isArray(body.rewards)?body.rewards:[];if(rewards.length>2000)throw new HttpError(400,'Too many rewards');const tiers=new Set();
 const clean=rewards.map(r=>{if(!['FREE','PAID'].includes(r?.track))throw new HttpError(400,'Reward track must be FREE or PAID');const level=int(r.level,'Reward level',1,thresholds.length),index=int(r.index??0,'Reward index',0,99);
  if(!CONTENT.test(r.contentId||''))throw new HttpError(400,'Invalid reward contentId');if(!['UNLOCK','CAPABILITY','QUANTITY'].includes(r.kind))throw new HttpError(400,'Invalid reward kind');
  if(tiers.size===tiers.add(r.track+':'+level+':'+index).size)throw new HttpError(400,'Two rewards share one tier slot');
  return {track:r.track,level,index,contentId:r.contentId,kind:r.kind,quantity:int(r.quantity??1,'Reward quantity',1,1000000),name:text(r.name,'Reward name',80)};});
 const startsOn=date(body.startsOn,'startsOn'),endsOn=date(body.endsOn,'endsOn');if(startsOn&&endsOn&&endsOn<startsOn)throw new HttpError(400,'The end date is before the start date');
 const status=body.status==='live'?'live':'draft';
 return {definition:{schemaVersion:1,kind:'season',id:'eternia:season/'+match[1],revision:1,displayName:text(body.name,'name',100,true),description:text(body.description,'description',600),
  pass:{id:body.id,status,startsOn,endsOn,thresholds,rewards:clean},source:'game',author:author(body)}};
}
const summary=r=>({key:r.key,id:r.definition.id,revision:r.definition.revision,unchanged:Boolean(r.unchanged)});
/** Latest live revision of each season pass, safe for public pages. */
export async function publicSeasons(catalog){
 const latest=new Map();for(const r of await catalog.list())if(r.definition.kind==='season'&&r.definition.source==='game'){const prev=latest.get(r.definition.id);if(!prev||prev.definition.revision<r.definition.revision)latest.set(r.definition.id,r);}
 return [...latest.values()].filter(r=>r.definition.pass?.status==='live').map(({definition:d})=>({id:d.pass.id,name:d.displayName,description:d.description,startsOn:d.pass.startsOn,endsOn:d.pass.endsOn,levels:d.pass.thresholds.length,totalXp:d.pass.thresholds.at(-1),thresholds:d.pass.thresholds,
  rewards:d.pass.rewards.map(r=>({track:r.track,level:r.level,contentId:r.contentId,quantity:r.quantity,name:r.name}))})).sort((a,b)=>a.name.localeCompare(b.name));
}
/** Mounted before browser sessions and CSRF: these calls carry no cookies and come only from the game server. */
export function mountGameSync(app,cfg,catalog){
 const gate=serverGate(cfg),json=express.json({limit:'12mb'});
 app.post('/api/server/content',gate,json,route(async(req,res)=>{const input=contentUpload(req.body);res.status(201).json(summary(await catalog.importNext(input,'game:'+input.definition.author.uuid)));}));
 app.post('/api/server/seasons',gate,json,route(async(req,res)=>{const input=seasonUpload(req.body);res.status(201).json(summary(await catalog.importNext(input,'game:'+input.definition.author.uuid)));}));
 app.get('/api/v1/seasons',route(async(req,res)=>res.set('Cache-Control','public, max-age=60').json({seasons:await publicSeasons(catalog)})));
}
