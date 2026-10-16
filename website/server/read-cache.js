// Display/immutable documents only. Never use for session authentication or queue admission.
export function createReadCache({ttl=5000,maxEntries=128,maxBytes=16*1024*1024,now=Date.now}={}){
 const entries=new Map(),pending=new Map();let epoch=0,bytes=0;
 function clear(){epoch++;entries.clear();bytes=0;}
 function remove(key){const old=entries.get(key);if(old){bytes-=old.bytes;entries.delete(key);}}
 async function get(key,load){
  const hit=entries.get(key);if(hit&&hit.expires>now()){entries.delete(key);entries.set(key,hit);return structuredClone(hit.value);}remove(key);
  const version=epoch,flightKey=version+':'+key;
  if(pending.has(flightKey))return structuredClone(await pending.get(flightKey));
  const task=Promise.resolve().then(load).then(value=>{
   const copy=structuredClone(value),size=Buffer.byteLength(JSON.stringify(copy)??'null');
   if(version===epoch&&size<=maxBytes){remove(key);entries.set(key,{value:copy,bytes:size,expires:now()+ttl});bytes+=size;while(entries.size>maxEntries||bytes>maxBytes)remove(entries.keys().next().value);}
   return copy;
  }).finally(()=>pending.delete(flightKey));
  pending.set(flightKey,task);return structuredClone(await task);
 }
 return {get,clear};
}
