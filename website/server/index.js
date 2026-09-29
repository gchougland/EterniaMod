import 'dotenv/config';import {config} from './config.js';import {createRepository} from './repository.js';import {createApp} from './app.js';
const cfg=config(),repo=await createRepository(cfg),{app,queue}=createApp(cfg,repo);
const server=app.listen(cfg.port,cfg.host,async()=>{console.log('Eternia website: '+cfg.baseUrl+(cfg.fixtures?' [LOCAL FIXTURES]':''));await queue.start('http://127.0.0.1:'+server.address().port);});
let stopping=false;async function stop(){if(stopping)return;stopping=true;await queue.close();server.close(async()=>{await repo.close();process.exit(0);});setTimeout(()=>process.exit(1),10000).unref();}
process.on('SIGTERM',stop);process.on('SIGINT',stop);

