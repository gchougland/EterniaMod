import {api,me,el,link,identityBar,notice} from '/js/api.js';import {PrefabViewer} from '/prefab-viewer/PrefabViewer.js';
await identityBar();let selected,viewer;
async function input(){const d=document.querySelector('#definition').files[0],p=document.querySelector('#prefab').files[0];if(!d)throw new Error('Choose a content definition first.');if(d.size>1024*1024||p?.size>10*1024*1024)throw new Error('Definition max 1 MB; prefab max 10 MB.');return {definition:JSON.parse(await d.text()),prefab:p?JSON.parse(await p.text()):undefined};}
async function busy(button,fn){button.disabled=true;try{await fn();}catch(e){notice(e.message,true);}finally{button.disabled=false;}}
document.querySelector('#validate').onclick=e=>busy(e.target,async()=>{const report=await api('/api/admin/content/validate',{method:'POST',body:JSON.stringify(await input())});document.querySelector('#validation').textContent=JSON.stringify(report,null,2);});
document.querySelector('#import-form').onsubmit=e=>{e.preventDefault();busy(e.submitter,async()=>{const record=await api('/api/admin/content',{method:'POST',body:JSON.stringify(await input())});notice('Revision imported. It has not been published to the game.');await refresh();await select(record.key);});};
async function refresh(){const records=await api('/api/admin/content'),root=document.querySelector('#revisions');root.replaceChildren();for(const r of records){const b=el('button',(r.definition.displayName||r.definition.id)+' · r'+r.definition.revision,'revision');b.onclick=()=>select(r.key).catch(e=>notice(e.message,true));b.dataset.key=r.key;root.append(b);}if(!records.length)root.append(el('p','No revisions imported yet.','muted'));await jobs();}
async function select(key){selected=await api('/api/admin/content/'+key);document.querySelector('#selected-name').textContent=selected.definition.displayName||selected.definition.id;document.querySelector('#details').textContent=JSON.stringify({id:selected.definition.id,revision:selected.definition.revision,sha256:selected.sha256,validation:selected.validation},null,2);
 const exportLink=document.querySelector('#export');exportLink.hidden=false;exportLink.href='/api/admin/content/'+key;exportLink.download=selected.definition.id.replace(/[^a-z0-9_-]/g,'_')+'.json';
 document.querySelectorAll('.revision').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.key===key)));
 document.querySelector('#screenshot').disabled=!selected.prefab;document.querySelector('#icon').disabled=!selected.prefab;
 document.querySelector('#native-export').disabled=!['house','prop','addition'].includes(selected.definition.kind);
 viewer?.dispose();const host=document.querySelector('#viewer');host.replaceChildren();if(!selected.prefab){host.append(el('p','This content has no prefab.'));return;}
 viewer=new PrefabViewer(host,{assetBase:'/hytale-assets',fullscreenButton:false});try{await viewer.loadPrefabUrl('/api/admin/content/'+key+'/prefab',{frontFacing:selected.definition.frontFacing||'North'});}catch(e){host.append(el('p','Preview unavailable: '+e.message,'error'));}}
for(const mode of ['screenshot','icon'])document.querySelector('#'+mode).onclick=e=>busy(e.target,async()=>{await api('/api/admin/renders',{method:'POST',body:JSON.stringify({contentKey:selected.key,mode})});await jobs();});
document.querySelector('#native-export').onclick=e=>busy(e.target,async()=>{
 const state=await me(),response=await fetch('/api/admin/exports/native',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':state.csrf},body:JSON.stringify({contentKeys:[selected.key]})});
 if(!response.ok)throw new Error((await response.json()).error||'Native export failed');
 const url=URL.createObjectURL(await response.blob()),a=link('Download',url);a.download='eternia-native-catalog.tar.gz';a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);notice('Native catalog bundle downloaded. Merge its index entries and validate it in a local server before publishing.');
});
async function jobs(){const list=await api('/api/admin/renders'),host=document.querySelector('#jobs');host.replaceChildren();for(const job of list.slice().reverse()){const row=el('div',undefined,'job');row.append(el('strong',job.mode),el('span',job.status,'pill'));
 if(job.error)row.append(el('span',job.error,'danger'));
 if(job.status==='complete'){const img=el('img');img.src='/api/admin/renders/'+job.id+'/image';img.alt=job.mode+' preview';row.append(img,link('Download PNG',img.src));}
 if(job.status==='failed'){const retry=el('button','Retry');retry.onclick=()=>busy(retry,async()=>{await api('/api/admin/renders/'+job.id+'/retry',{method:'POST'});await jobs();});row.append(retry);}host.append(row);}if(!list.length)host.append(el('p','No render jobs yet.','muted'));}
await refresh();setInterval(()=>jobs().catch(()=>{}),4000);
