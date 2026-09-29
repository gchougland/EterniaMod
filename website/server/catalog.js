import crypto from 'node:crypto';
import {HttpError} from './errors.js';
export const KINDS=new Set(['house','prop','addition','palette','improvement','pet','cosmetic','title','reward_bundle','recipe','discovery_token','activity','quest','season','npc','dialogue','destination','offer','supporter_benefits','plot_shape']);
export function validateContent(input){
 const errors=[],warnings=[]; const d=input?.definition,p=input?.prefab;
 if(!d||typeof d!=='object'||Array.isArray(d))return {valid:false,errors:['definition must be an object'],warnings};
 if(d.schemaVersion!==1)errors.push('schemaVersion must equal 1');
 if(!KINDS.has(d.kind))errors.push('Unknown content kind');
 if(!/^eternia:[a-z0-9_/-]+$/.test(d.id||''))errors.push('Use an eternia: namespaced ID');
 if(!Number.isSafeInteger(d.revision)||d.revision<1||d.revision>2147483647)errors.push('revision must be an integer between 1 and 2147483647');
 if(!d.displayName&&!d.displayNameKey)errors.push('displayName or displayNameKey required');
 if(['house','prop','addition'].includes(d.kind)&&!p)errors.push('This content kind requires an attached prefab');
 let visited=0;
 function visit(obj,depth=0){if(++visited>500000||depth>48)throw new HttpError(400,'Content exceeds structural limits');
 if(obj&&typeof obj==='object')for(const [key,value]of Object.entries(obj)){if(['__proto__','prototype','constructor'].includes(key))errors.push('Forbidden object key');
 if(typeof value==='string'&&(/path|prefab|iconAsset|regionMap/i.test(key))&&(/(^[a-z]+:|^[/\\]|(^|[/\\])\.\.([/\\]|$))/i.test(value)))errors.push('Asset references must be relative and confined to the content pack');visit(value,depth+1);}}
 visit(input);
 if(p){if(!Array.isArray(p.blocks))errors.push('prefab.blocks must be an array');
 const cells=[...(Array.isArray(p.blocks)?p.blocks:[]),...(Array.isArray(p.fluids)?p.fluids:[])];
 if(cells.length>80000)errors.push('Prefab exceeds 80,000 cell preview budget');
 const seen=new Set();
 for(const c of cells){if(![c.x,c.y,c.z].every(n=>Number.isSafeInteger(n)&&Math.abs(n)<=1024)){errors.push('Prefab coordinates must be integers within ±1024');break;}
 if(typeof c.name!=='string'||c.name.length>200){errors.push('Each prefab cell requires a bounded asset name');break;}}
 if((p.entities?.length||0)>256)errors.push('Prefab exceeds 256 entity preview budget');
 if(!cells.length&&!(p.entities?.length))errors.push('Prefab is empty');
 if(p.entities?.length)warnings.push('Entity components require native snapshot/placement validation before release.');
 const bounds=d.structureBounds;
 if(bounds){if(!Array.isArray(bounds.min)||!Array.isArray(bounds.size)||bounds.min.length!==3||bounds.size.length!==3||!bounds.min.every(Number.isSafeInteger)||!bounds.size.every(n=>Number.isSafeInteger(n)&&n>0))errors.push('Invalid structureBounds');
 else if(cells.some(c=>[c.x,c.y,c.z].some((v,i)=>v<bounds.min[i]||v>=bounds.min[i]+bounds.size[i])))warnings.push('Prefab mutation mask extends beyond structureBounds; native placement must validate all cleared cells.');}
 }
 warnings.push('Import creates an admin authoring revision only; game publication and entitlement grants are separate.');
 return {valid:errors.length===0,errors:[...new Set(errors)],warnings:[...new Set(warnings)]};
}
export function createCatalog(repo){
 let importLock=Promise.resolve();
 return {list:()=>repo.list('content'),get:key=>repo.get('content',key),validate:validateContent,
 async import(input,actor){
  const job=importLock.then(async()=>{const validation=validateContent(input);if(!validation.valid)throw new HttpError(400,validation.errors.join('; '));
  const prior=await repo.list('content');if(prior.some(x=>x.definition.id===input.definition.id&&x.definition.revision===input.definition.revision))throw new HttpError(409,'That immutable content revision already exists. Increment revision.');
  const record={key:crypto.randomUUID(),definition:input.definition,prefab:input.prefab||null,validation,sha256:crypto.createHash('sha256').update(JSON.stringify(input)).digest('hex'),createdAt:new Date().toISOString(),createdBy:actor};
  await repo.put('content',record.key,record);return record;});importLock=job.catch(()=>{});return job;
 }};
}
