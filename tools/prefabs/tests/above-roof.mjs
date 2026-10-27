// Lists non-roof blocks of the tavern sitting at or above the main roof surface (things poking through the roof).
import { readFileSync } from 'node:fs';
const doc = JSON.parse(readFileSync(new URL('../../../src/main/resources/Server/Prefabs/EterniaMod/Hub/Eternia_Tavern.prefab.json', import.meta.url), 'utf8'));
const surf = z => z <= 6 ? 11 + (z + 1) : 11 + (13 - z);
for (const b of doc.blocks) {
  if (b.x < -1 || b.x > 19 || b.z < -2 || b.z > 14 || b.y < surf(b.z)) continue;
  if (/Roof|Empty|Window/.test(b.name) || (b.x >= 7 && b.x <= 12 && b.z >= 7)) continue;
  console.log(b.x, b.y, b.z, b.name);
}
