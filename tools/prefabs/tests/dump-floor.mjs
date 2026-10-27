// Prints a floor plan of one layer of the finalized tavern (filler cells included) for x/z ranges.
// node tools/prefabs/tests/dump-floor.mjs <y> <x0> <x1> <z0> <z1>
import { readFileSync } from 'node:fs';
const doc = JSON.parse(readFileSync(new URL('../../../src/main/resources/Server/Prefabs/EterniaMod/Hub/Eternia_Tavern.prefab.json', import.meta.url), 'utf8'));
const [y, x0, x1, z0, z1] = process.argv.slice(2).map(Number);
const m = new Map(doc.blocks.map(b => [b.x + ',' + b.y + ',' + b.z, b]));
const short = n => n.replace(/^\*/, '').replace(/^(Furniture|Wood|Rock|Deco)_/, '').replace(/_State_Definitions.*/, '').slice(0, 11);
let head = '      '; for (let x = x0; x <= x1; x++) head += String(x).padEnd(12); console.log(head);
for (let z = z0; z <= z1; z++) {
  let s = String(z).padEnd(6);
  for (let x = x0; x <= x1; x++) { const b = m.get(x + ',' + y + ',' + z); s += (b ? short(b.name) + (b.filler ? '+' : '') : '.').padEnd(12); }
  console.log(s);
}
const props = doc.entities.filter(e => e.Components?.Prop).map(e => [e.Components.Transform.Position, e.Components.Item.Item.Id]);
for (const [p, id] of props) if (Math.floor(p.Y) === y && p.X >= x0 && p.X < x1 + 1 && p.Z >= z0 && p.Z < z1 + 1) console.log('prop', id, p.X.toFixed(2), p.Y.toFixed(2), p.Z.toFixed(2));
