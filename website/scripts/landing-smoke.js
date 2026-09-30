import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const output=new URL('../test-output/',import.meta.url);
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:3851';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Use a local preview');
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page=await browser.newPage();const errors=[];
page.on('pageerror',e=>errors.push(e.message));
page.on('response',r=>{if(r.status()>=400&&r.url().startsWith(base))errors.push(r.status()+': '+r.url());});
await fs.mkdir(output,{recursive:true});
try {
 for(const width of [1440,768,390,320]) {
  await page.setViewportSize({width,height:1000});await page.goto(base);await page.locator('.realm-features').waitFor();await page.evaluate(()=>document.fonts.ready);
  await page.locator('.realm-invitation').scrollIntoViewIfNeeded();await page.waitForFunction(()=>[...document.images].every(i=>i.complete&&i.naturalWidth>0));
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Overflow at '+width);
  assert.equal(await page.locator('h1').count(),1);assert.equal(await page.locator('link[rel="icon"]').getAttribute('href'),'/icons/eternia.svg');
  await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:fileURLToPath(new URL('landing-'+width+'.png',output)),fullPage:true});
 }
 await page.getByRole('link',{name:'Discover Eternia'}).click();await page.waitForURL('**/#discover');
 await page.getByRole('link',{name:'Explore season passes',exact:true}).click();await page.waitForURL('**/seasons');await page.getByRole('heading',{name:'Your next chapter.'}).waitFor();
 await page.goto(base+'/store');await page.locator('h1').waitFor();
 assert.deepEqual(errors,[]);console.log('Landing passed: desktop, tablet, 390px and 320px, art loads, navigation, store, no browser errors.');
}finally{await browser.close();}
