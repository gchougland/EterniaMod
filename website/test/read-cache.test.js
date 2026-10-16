import test from 'node:test';import assert from 'node:assert/strict';
import {createReadCache} from '../server/read-cache.js';
test('coalesces readers, detaches results, expires and invalidates',async()=>{
 let time=0,loads=0;const cache=createReadCache({ttl:10,now:()=>time});const load=async()=>{loads++;return {value:loads};};
 const [a,b]=await Promise.all([cache.get('a',load),cache.get('a',load)]);assert.equal(loads,1);a.value=999;assert.equal(b.value,1);assert.equal((await cache.get('a',load)).value,1);
 time=11;assert.equal((await cache.get('a',load)).value,2);cache.clear();assert.equal((await cache.get('a',load)).value,3);
});
test('old in flight reads cannot overwrite a new write generation',async()=>{
 const cache=createReadCache();let release;const old=cache.get('a',()=>new Promise(resolve=>release=resolve));await Promise.resolve();cache.clear();assert.equal(await cache.get('a',()=>2),2);release(1);assert.equal(await old,1);assert.equal(await cache.get('a',()=>3),2);
});
test('errors are retried and oversized documents are not retained',async()=>{
 const cache=createReadCache({maxBytes:4});await assert.rejects(cache.get('a',()=>{throw Error('offline');}));let loads=0;const load=()=>{loads++;return 'large document';};await cache.get('a',load);await cache.get('a',load);assert.equal(loads,2);
});
