import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:3847';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw new Error('Crown fixture visual check requires loopback');
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
await fs.mkdir('test-output',{recursive:true});
try{
  await page.goto(base);await page.getByRole('button',{name:'Use local test account'}).click();await page.getByRole('link',{name:'Admin',exact:true}).waitFor();
  for(const size of [{width:1440,height:1000,name:'desktop'},{width:390,height:844,name:'mobile'}]){
    await page.setViewportSize(size);await page.goto(base+'/store');await page.getByRole('heading',{name:'Your next find awaits.'}).waitFor();await page.getByText('5,000 Crowns',{exact:true}).waitFor();
    await page.getByText(/Crown top-ups will open/).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Treasury overflow: '+size.name);
    await page.screenshot({path:'test-output/crown-store-'+size.name+'.png',fullPage:true});
  }
  assert.deepEqual(errors,[]);console.log('Crown treasury passed: fixture balance, desktop/mobile layout, no overflow, no browser exceptions.');
}finally{await browser.close();}
