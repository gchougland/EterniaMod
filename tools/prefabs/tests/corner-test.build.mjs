// Test sheet: roof corner states. Left half straight pieces, right half after roofCorners. A square hip roof and a
// main gable with a smaller gable meeting it at right angles (two valleys).
import { Prefab } from '../prefab-builder.mjs';
import { here, roofCorners, outward } from '../eternia-kit.mjs';

const R = 'Metal_Copper_Roof';
function sheet(ox, corners) {
  const p = new Prefab({ name: 'part' });
  p.box([0, 0, 0], [6, 2, 6], 'Rock_Sandstone_White_Brick');
  for (let r = 3, y = 3; r >= 0; r--, y++) for (let x = 3 - r; x <= 3 + r; x++) for (let z = 3 - r; z <= 3 + r; z++) {
    if (Math.max(Math.abs(x - 3), Math.abs(z - 3)) !== r) continue;
    p.set(x, y, z, r ? R : R + '_Flat', r ? { facing: outward(x - 3, z - 3) } : {});
  }
  p.box([0, 0, 10], [12, 2, 15], 'Rock_Sandstone_White_Brick');
  p.gableRoof([0, 10], [12, 15], 3, { slope: R, ridge: 'x', overhang: 0 });
  p.box([5, 0, 16], [7, 3, 19], 'Rock_Sandstone_White_Brick');
  p.gableRoof([4, 16], [8, 19], 4, { slope: R, ridge: 'z', overhang: 0 });
  for (let z = 15; z >= 13; z--) for (const [x, f] of [[4, 'west'], [5, 'west'], [7, 'east'], [8, 'east']]) {
    const y = { 4: 4, 5: 5, 7: 5, 8: 4 }[x];
    if (!p.has(x, y, z) || /Roof/.test(p.get(x, y, z))) p.set(x, y, z, R, { facing: f });
  }
  for (let z = 15; z >= 12; z--) p.set(6, 6, z, R + '_Flat');
  if (corners) roofCorners(p);
  return [p, ox];
}
const out = new Prefab({ name: 'corner_test' });
for (const [p, ox] of [sheet(0, false), sheet(16, true)]) out.stamp(p, [ox, 0, 0]);
out.save(here('../../docs/builds/Eternia_Tavern/tests/corner_test.prefab.json'));
