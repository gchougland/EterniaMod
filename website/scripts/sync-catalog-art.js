// Copies Eternia's own catalog icons and Citadel pictograms into web/media/catalog for public pages.
// Only the mod's authored item renders and vector pictograms are copied, never native Hytale assets.
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const ui=path.resolve(root,process.env.ETERNIA_UI_SRC||'../src/main/resources/Common/UI/Custom/EterniaMod');
const out=path.join(root,'web/media/catalog');

await fs.rm(out,{recursive:true,force:true});
await fs.mkdir(path.join(out,'icons'),{recursive:true});
let copied=0;
for(const kind of await fs.readdir(path.join(ui,'Catalog'))){
 for(const id of await fs.readdir(path.join(ui,'Catalog',kind))){
  const icon=path.join(ui,'Catalog',kind,id,'icon.png');
  try{await fs.access(icon);}catch{continue;}
  await fs.mkdir(path.join(out,kind),{recursive:true});
  await fs.copyFile(icon,path.join(out,kind,id+'.png'));copied++;
 }
}
for(const file of await fs.readdir(path.join(ui,'Icons')))if(file.endsWith('.svg')){await fs.copyFile(path.join(ui,'Icons',file),path.join(out,'icons',file));copied++;}
console.log('Copied '+copied+' catalog images to '+path.relative(root,out));
