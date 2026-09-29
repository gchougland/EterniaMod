import crypto from 'node:crypto';import {gzipSync} from 'node:zlib';import {HttpError} from './errors.js';
const supported=new Set(['house','prop','addition']),rotations=new Set(['None','Ninety','OneEighty','TwoSeventy']);
function stable(value){if(Array.isArray(value))return value.map(stable);if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(k=>[k,stable(value[k])]));return value;}
const json=value=>JSON.stringify(stable(value),null,2)+'\n';
function vector(value,name,required=false){if(value===undefined){if(required)throw new HttpError(400,name+' is required for native export');return undefined;}if(!Array.isArray(value)||value.length!==3||!value.every(n=>Number.isSafeInteger(n)&&Math.abs(n)<=4096))throw new HttpError(400,name+' must contain three integers within ±4096');return value;}
export function nativeDefinition(record){
 const d=record.definition;if(!supported.has(d.kind)||!record.prefab)throw new HttpError(400,'Native bundles currently support houses, props and additions.');
 const match=/^eternia:(house|prop|addition)\/([a-z0-9][a-z0-9_-]{0,95})$/.exec(d.id);
 if(!match||match[1]!==d.kind)throw new HttpError(400,'Native export requires eternia:'+d.kind+'/<safe_catalog_id>.');
 const n=d.native||{},id=match[2],rotationYaw=n.rotationYaw||'None';if(!rotations.has(rotationYaw))throw new HttpError(400,'Invalid native.rotationYaw');
 if(typeof d.displayName!=='string'||!d.displayName.trim())throw new HttpError(400,'A literal displayName is required by the native catalog.');
 const prefabPath='EterniaAuthored/'+d.kind+'/'+id+'/r'+d.revision+'.prefab.json';
 const definition={id,displayName:d.displayName,prefabPath,plotAnchorOffset:vector(n.plotAnchorOffset,'native.plotAnchorOffset')||[0,0,0],rotationYaw};
 if(d.kind==='house'){
  definition.managementBlockLocalPos=vector(n.managementBlockLocalPos,'native.managementBlockLocalPos',true);
  definition.spawnLocalPos=vector(n.spawnLocalPos,'native.spawnLocalPos',true);
  definition.housingKind=n.housingKind||'personal';if(!['personal','guild'].includes(definition.housingKind))throw new HttpError(400,'native.housingKind must be personal or guild');
 }else definition.category=d.kind==='addition'?'addition':'prop';
 const blocks=record.prefab.blocks;if(!Array.isArray(blocks)||!blocks.some(b=>b.name&&b.name!=='Empty'))throw new HttpError(400,'Native prefab requires nonempty blocks');
 for(const axis of ['x','y','z']){const values=blocks.map(b=>b[axis]);if(Math.max(...values)-Math.min(...values)+1>512)throw new HttpError(400,'Native prefab exceeds 512 blocks on an axis');}
 return {definition,prefabPath,directory:d.kind==='house'?'Buildings':'Props'};
}
/** Fixed timestamps, sorted files and canonical JSON make identical selected revisions byte-identical. */
export function exportNativeBundle(records,images=new Map()){
 if(!records.length||records.length>100)throw new HttpError(400,'Select 1–100 revisions for native export');
 const files=new Map(),indexes={Buildings:[],Props:[]},manifest=[];const used=new Set();let prefabBytes=0;
 for(const record of [...records].sort((a,b)=>a.definition.id.localeCompare(b.definition.id))){
  const converted=nativeDefinition(record),key=converted.directory+'/'+converted.definition.id;
  if(used.has(key))throw new HttpError(409,'A bundle can contain only one revision per native catalog ID');used.add(key);
  const file=converted.definition.id+'.json';indexes[converted.directory].push(file);
  files.set('Server/EterniaMod/'+converted.directory+'/'+file,Buffer.from(json(converted.definition)));
  for(const mode of ['icon','screenshot']){
   const png=images.get(record.key+':'+mode);if(!png)continue;
   if(!Buffer.isBuffer(png)||png.length>8*1024*1024||!png.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw new HttpError(400,'Invalid rendered catalog image');
   const kind=record.definition.kind==='house'?'house':'prop';
   files.set('Common/UI/Custom/EterniaMod/Catalog/'+kind+'/'+converted.definition.id+'/'+mode+'.png',png);
  }
  const prefab=Buffer.from(json(record.prefab));prefabBytes+=prefab.length;if(prefabBytes>64*1024*1024)throw new HttpError(413,'Native export exceeds 64 MB; select fewer revisions');files.set('Server/Prefabs/'+converted.prefabPath,prefab);
  manifest.push({id:record.definition.id,revision:record.definition.revision,nativeId:converted.definition.id,entitlementId:'eternia:'+(record.definition.kind==='house'?'house':'prop')+'/'+converted.definition.id,prefabPath:converted.prefabPath,prefabSha256:crypto.createHash('sha256').update(prefab).digest('hex'),requiresNativeEntityAdapter:Boolean(record.prefab.entities?.length)});
 }
 for(const [directory,names]of Object.entries(indexes))if(names.length)files.set('Server/EterniaMod/'+directory+'/catalog.index',Buffer.from(names.sort().join('\n')+'\n'));
 files.set('eternia-export.json',Buffer.from(json({format:'eternia-native-catalog-1',revisions:manifest})));
 files.set('README.txt',Buffer.from('Eternia native catalog export\n\nMerge Server/ and Common/ into the mod source resources or a reviewed content pack. Merge catalog.index entries into existing indexes; do not replace other catalog entries. Rebuild and validate the catalog and native placement in a local server before release. This archive contains only the selected definitions, exact prefab JSON and completed revision pictures; no new content grants. No publication or deployment has occurred.\n'));
 return {files,bytes:tarGzip(files)};
}
function tarGzip(files){const parts=[];for(const [name,bytes]of [...files].sort(([a],[b])=>a.localeCompare(b))){
 const header=Buffer.alloc(512);let filename=name,prefix='';if(Buffer.byteLength(name)>100){const split=name.lastIndexOf('/');prefix=name.slice(0,split);filename=name.slice(split+1);}
 if(Buffer.byteLength(filename)>100||Buffer.byteLength(prefix)>155)throw new HttpError(400,'Export path exceeds archive limits');
 header.write(filename,0,100);header.write('0000644\0',100);header.write('0000000\0',108);header.write('0000000\0',116);header.write(bytes.length.toString(8).padStart(11,'0')+'\0',124);header.write('00000000000\0',136);header.fill(32,148,156);header.write('0',156);header.write('ustar\0',257);header.write('00',263);header.write(prefix,345,155);
 const checksum=header.reduce((sum,n)=>sum+n,0);header.write(checksum.toString(8).padStart(6,'0')+'\0 ',148);parts.push(header,bytes,Buffer.alloc((512-bytes.length%512)%512));
 }parts.push(Buffer.alloc(1024));return gzipSync(Buffer.concat(parts),{level:9});}
