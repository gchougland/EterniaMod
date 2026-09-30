import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const packages=JSON.parse(await fs.readFile(path.join(root,'website/config/tebex-crowns.json'),'utf8'));
const output=path.join(root,'build/tebex-setup');
await fs.mkdir(path.join(output,'Products'),{recursive:true});
for(const p of packages){
 if(!/^\d+$/.test(p.id)||!Number.isSafeInteger(p.crowns)||p.crowns<1||!Number.isSafeInteger(p.revision)||p.revision<1)throw new Error('Invalid Crown package');
 const product={packageId:p.id,revision:p.revision,name:p.name,subscription:false,benefits:[{contentId:'eternia:currency/crowns',kind:'QUANTITY',quantity:p.crowns,expiresWithSubscription:false}]};
 await fs.writeFile(path.join(output,'Products',`crowns-${p.id}-v${p.revision}.json`),JSON.stringify(product,null,2)+'\n');
}
await fs.writeFile(path.join(output,'tebex-packages.json'),JSON.stringify(Object.fromEntries(packages.map(p=>[p.id,p.revision])),null,2)+'\n');
await fs.writeFile(path.join(output,'README.txt'),`Eternia Crown delivery configuration\n\nRead docs/implementation/tebex-headless.md first.\nStop the game server before installation.\nConfigure ETERNIA_BRIDGE_TOKEN and ETERNIA_TEBEX_WEBHOOK_SECRET before copying these mappings.\nMerge Products/ into mods/Hexvane_EterniaMod/Products/.\nMerge tebex-packages.json into mods/Hexvane_EterniaMod/tebex-packages.json; preserve other existing package mappings.\nUse the new EterniaMod jar with the Crown delivery status endpoint.\nRestart the game server. No store-offers.json edits are needed for Headless checkout.\n\nTebex initial console command for each package:\neternia-tebex {transaction} {packageId} {id} {purchaseQuantity}\n\nThese files contain no credentials and do not enable payments by themselves.\n`);
console.log('Wrote '+output);
