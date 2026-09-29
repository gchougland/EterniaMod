let identity;
export async function me(){const res=await fetch('/api/me',{cache:'no-store'});identity=await res.json();return identity;}
export async function api(url,options={}){if(!identity)await me();const response=await fetch(url,{...options,headers:{'Content-Type':'application/json','X-CSRF-Token':identity.csrf,...options.headers}});let body;try{body=await response.json();}catch{body={error:'Unexpected server response'};}if(!response.ok)throw new Error(body.error||'Request failed');return body;}
export function el(tag,text,cls){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;}
export function link(text,url,cls='button'){const node=el('a',text,cls);node.href=url;return node;}
export function notice(text,error=false){const root=document.querySelector('#notice');root.replaceChildren(el('div',text,'notice'+(error?' error':'')));}
export async function identityBar(){const state=await me(),root=document.querySelector('#identity');root.replaceChildren();
 if(state.fixtures)notice('Local development · synthetic accounts and data. No real purchases.');
 if(state.user){root.append(el('span',state.user.name,'pill'));if(state.user.admin)root.append(link('Admin','/admin'));const logout=el('button','Sign out');logout.onclick=async()=>{await api('/auth/logout',{method:'POST'});location.href='/';};root.append(logout);}
 else if(state.authConfigured)root.append(link('Sign in with Hytale','/auth/login','button primary'));
 else if(state.fixtures){const b=el('button','Use local test account','primary');b.onclick=async()=>{await api('/api/dev/login',{method:'POST'});location.reload();};root.append(b);}
 else root.append(el('span','Hytale sign-in coming soon','pill'));return state;}

