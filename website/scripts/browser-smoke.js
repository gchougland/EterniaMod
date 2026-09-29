import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const base = process.env.TEST_BASE_URL || 'http://127.0.0.1:3847';
if (!['127.0.0.1','localhost'].includes(new URL(base).hostname)) throw new Error('Smoke fixture writes require local target');
const browser = await chromium.launch({headless:true, executablePath:process.env.CHROMIUM_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page = await browser.newPage({viewport:{width:1440,height:1050}});
const failures=[];page.on('pageerror',e=>failures.push(e.message));
await fs.mkdir('test-output',{recursive:true});
try {
 await page.goto(base);await page.getByRole('button',{name:'Use local test account'}).click();
 await page.getByRole('link',{name:'Admin',exact:true}).waitFor();
 await page.goto(base+'/account');await page.getByText('12',{exact:true}).waitFor();
 await page.screenshot({path:'test-output/account-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.goto(base+'/seasons');await page.getByRole('progressbar').waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Mobile page overflows');
 await page.screenshot({path:'test-output/seasons-mobile.png',fullPage:true});
 await page.setViewportSize({width:1440,height:1050});await page.goto(base+'/admin');
 const definition=JSON.parse(await fs.readFile('dev/chair.definition.json','utf8'));definition.revision=Math.floor(Date.now()/1000);
 await page.locator('#definition').setInputFiles({name:'smoke.definition.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(definition))});
 await page.locator('#prefab').setInputFiles('dev/chair.prefab.json');
 await page.getByRole('button',{name:'Import',exact:true}).click();
 await page.locator('#selected-name').filter({hasText:'Preview Stone'}).waitFor();
 await page.locator('#viewer canvas').waitFor();
 await page.waitForTimeout(1500);
 await page.getByRole('button',{name:'Queue screenshot',exact:true}).click();
 await page.getByRole('button',{name:'Queue transparent icon',exact:true}).click();
 const deadline=Date.now()+100000;let jobs=[];
 while(Date.now()<deadline){jobs=await (await page.request.get(base+'/api/admin/renders')).json();const newest=jobs.slice(-2);if(newest.length===2&&newest.every(x=>['failed','complete'].includes(x.status)))break;await page.waitForTimeout(1000);}
 const newest=jobs.slice(-2);assert.equal(newest.length,2);assert.ok(newest.every(x=>x.status==='complete'),JSON.stringify(newest));
 for(const job of newest){const response=await page.request.get(base+'/api/admin/renders/'+job.id+'/image');assert.equal(response.status(),200);await fs.writeFile('test-output/'+job.mode+'.png',await response.body());}
 await page.locator('#jobs img').first().waitFor({timeout:10000});
 await page.screenshot({path:'test-output/admin-desktop.png',fullPage:true});
 assert.deepEqual(failures,[]);console.log('Browser smoke passed: desktop/mobile, immutable import, actual screenshot + transparent icon rendering.');
} finally { await browser.close(); }
