import fs from 'node:fs/promises';
import path from 'node:path';
import pg from 'pg';
export async function createRepository(cfg){
 if(!cfg.fixtures){
  const pool=new pg.Pool({connectionString:cfg.databaseUrl,max:6});
  return {pool,async list(kind){const t=table(kind);return (await pool.query('SELECT document FROM eternia_web.'+t+' ORDER BY created_at DESC')).rows.map(x=>x.document);},
   async put(kind,id,doc){if(kind==='content')await pool.query('INSERT INTO eternia_web.content_revisions(key,content_id,revision,document) VALUES($1,$2,$3,$4)',[id,doc.definition.id,doc.definition.revision,doc]);else await pool.query('INSERT INTO eternia_web.render_jobs(id,document) VALUES($1,$2) ON CONFLICT(id) DO UPDATE SET document=EXCLUDED.document',[id,doc]);},
   async get(kind,id){return (await pool.query('SELECT document FROM eternia_web.'+table(kind)+' WHERE '+(kind==='content'?'key':'id')+'=$1',[id])).rows[0]?.document;},async close(){await pool.end();}};
 }
 await fs.mkdir(cfg.dataDir,{recursive:true});
 const file=path.join(cfg.dataDir,'fixture-store.json');
 let data;try{data=JSON.parse(await fs.readFile(file,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;data={content:{},jobs:{}};}
 let write=Promise.resolve();
 return {async list(kind){return Object.values(data[kind]);},async get(kind,id){return data[kind][id];},async put(kind,id,doc){
  data[kind][id]=structuredClone(doc);const snapshot=JSON.stringify(data);write=write.then(async()=>{await fs.writeFile(file+'.tmp',snapshot);await fs.rename(file+'.tmp',file);});await write;
 },async close(){await write;}};
}
function table(kind){if(kind==='content')return 'content_revisions';if(kind==='jobs')return 'render_jobs';throw new Error('Invalid repository kind');}

