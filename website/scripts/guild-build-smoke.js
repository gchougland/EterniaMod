import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:3851',out=new URL('../test-output/',import.meta.url);
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Use a local preview');
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
await fs.mkdir(out,{recursive:true});
try {
 for(const width of [1440,390]){
  const page=await browser.newPage({viewport:{width,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.locator('.guild-story').waitFor();
  const scrub=async progress=>{await page.locator('.guild-story').evaluate((el,p)=>{const r=el.getBoundingClientRect();scrollTo(0,scrollY+r.top+(r.height-innerHeight)*p);},progress);};
  await scrub(.05);await page.locator('.guild-story[data-ready=true]').waitFor();
  for(const [progress,min,max]of [[.05,1,4],[.5,22,25],[1,46,47]]){
   await scrub(progress);await page.waitForFunction(([min,max])=>{const n=Number(document.querySelector('.guild-story').dataset.frame);return n>=min&&n<=max;},[min,max]);
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await page.screenshot({path:fileURLToPath(new URL('guild-build-'+width+'-'+progress+'.png',out))});
  }
  await scrub(.1);await page.waitForFunction(()=>Number(document.querySelector('.guild-story').dataset.frame)<8);
  await page.getByRole('button',{name:'See completed hall'}).click();assert.equal(await page.locator('.guild-story').getAttribute('data-frame'),'47');
  await page.getByRole('button',{name:'Follow the scroll'}).click();await page.waitForFunction(()=>Number(document.querySelector('.guild-story').dataset.frame)<8);
  assert.deepEqual(errors,[]);await page.close();
 }
 const reduced=await browser.newPage({viewport:{width:390,height:844},reducedMotion:'reduce'});const requests=[];reduced.on('request',r=>requests.push(r.url()));
 await reduced.goto(base);await reduced.locator('.guild-story').scrollIntoViewIfNeeded();await reduced.waitForFunction(()=>document.querySelector('.build-poster').complete);
 assert.equal(await reduced.locator('.guild-sticky').evaluate(el=>getComputedStyle(el).position),'relative');
 assert.equal(await reduced.locator('canvas').isVisible(),false);
 assert.equal(requests.filter(u=>/guild-build\/\d+\.webp/.test(u)&&!u.endsWith('/47.webp')).length,0);
 assert.equal(await reduced.locator('.brand').innerText(),'ETERNIA');
 assert.equal(await reduced.locator('.brand img').evaluate(i=>i.complete&&i.naturalWidth>0),true);
 console.log('Guild animation passed: forward/reverse scroll, full-view control, desktop/mobile, reduced motion, header emblem.');
}finally{await browser.close();}
