// Visual QA of the actual website fonts/CSS and game nine-slice images.
// This is a style sheet, not a claim that the Hytale client was rendered.
import express from 'express';
import {chromium} from 'playwright';
import path from 'node:path';
import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const app=express();
app.use(express.static(path.join(root,'web')));
app.use('/surfaces',express.static(path.resolve(root,'../src/main/resources/Common/UI/Custom/EterniaMod/Theme/Surfaces')));
app.get('/preview',(_req,res)=>res.type('html').send(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Eternia / Citadel style sheet</title><link rel="stylesheet" href="/theme.css"><link rel="stylesheet" href="/styles.css"><style>
.swatches{display:flex;gap:12px;margin:20px 0}.swatches i{width:36px;height:8px}.sample{border:12px solid transparent;border-image:url('/surfaces/Panel.png') 12 fill stretch;padding:18px}.native-button{border:12px solid transparent;border-image:url('/surfaces/Secondary.png') 12 fill stretch;padding:0 10px;min-height:44px;color:#f0e8d5}.native-button:hover{border-image-source:url('/surfaces/SecondaryHover.png')}.native-primary{border-image-source:url('/surfaces/Primary.png');color:#142528}.grid{margin-top:20px}h3{font-size:20px}.subtle{font-size:14px}.sample img{width:180px;height:40px;object-fit:contain}.sample h3{font-family:'Source Sans 3',sans-serif}.sample p{font-size:16px}.progress{height:6px;background:#101e22;margin:18px 0}.progress div{width:60%;height:100%;background:#85c5b3}
</style><header class="topbar"><a class="brand" href="#">ETERNIA</a><nav><a href="#" aria-current="page">Sanctuary</a><a href="#">Collection</a><a href="#">Crown store</a></nav><span class="pill">Citadel design study</span></header><main class="wrap"><div class="eyebrow">A place to call your own</div><h1>Welcome home, adventurer.</h1><p class="muted">Cinzel display lettering, clear Source Sans 3 text, brass rims and sage accents. Simple geometry with a little depth.</p><div class="swatches"><i style="background:#d8b66b"></i><i style="background:#85c5b3"></i><i style="background:#f0e8d5"></i><i style="background:#45615d"></i></div><div class="grid"><article class="card"><div class="eyebrow">Housing</div><h3>The Willow Cottage</h3><p class="muted">A welcoming home, ready for the little things that make it yours.</p><div class="actions"><button class="primary">Preview house</button><button>View collection</button></div></article><article class="card"><div class="eyebrow">Season journal</div><h3>On patrol</h3><p>Defeat 50 creatures</p><p class="muted">30 / 50 completed <span class="pill">8,000 XP</span></p><div class="progress"><div></div></div><button>View objectives</button></article><article class="card"><div class="eyebrow">Crown store</div><h3>A touch of your style</h3><p class="muted">Discover new furniture, companions and decorations for your home.</p><div class="actions"><button class="primary">Browse items</button></div></article></div><h2 style="margin-top:36px">Native surface samples</h2><p class="muted subtle">Actual game patch images at different sizes. This browser sheet does not simulate Hytale fonts or its layout engine.</p><section class="sample"><img src="/surfaces/EterniaWordmark.png" alt="Eternia"><h3>Road Designer</h3><p>Choose a paving style, shape your curve, then review the road.</p><div class="actions"><button class="native-button">Width &amp; style</button><button class="native-button native-primary">Review road</button><button class="native-button" disabled>Unavailable</button></div></section></main></html>`));
const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
let browser;
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||(process.platform==='win32'?'C:/Program Files/Google/Chrome/Application/chrome.exe':undefined)});
 const page=await browser.newPage({viewport:{width:1440,height:1100},deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`http://127.0.0.1:${server.address().port}/preview`);
 await page.evaluate(()=>document.fonts.ready);
 const result=await page.evaluate(()=>({cinzel:document.fonts.check('24px Cinzel'),body:document.fonts.check('16px "Source Sans 3"'),overflow:document.documentElement.scrollWidth>innerWidth}));
 assert.deepEqual(result,{cinzel:true,body:true,overflow:false});assert.deepEqual(errors,[]);
 const out=path.join(root,'test-output');await fs.mkdir(out,{recursive:true});await page.screenshot({path:path.join(out,'citadel-theme.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 await page.screenshot({path:path.join(out,'citadel-theme-mobile.png'),fullPage:true});
 console.log('Citadel preview passed: local fonts loaded; desktop/mobile have no horizontal overflow.');
}finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
