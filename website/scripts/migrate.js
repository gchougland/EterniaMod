import 'dotenv/config';import pg from 'pg';import fs from 'node:fs/promises';
if(!process.env.DATABASE_URL)throw new Error('DATABASE_URL required');
const client=new pg.Client({connectionString:process.env.DATABASE_URL});await client.connect();
try{await client.query('BEGIN');await client.query(await fs.readFile(new URL('../migrations/001_web.sql',import.meta.url),'utf8'));await client.query('COMMIT');console.log('Eternia web schema ready');}finally{await client.end();}

