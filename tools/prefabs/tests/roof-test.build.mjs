// Test sheet: the copper roof family's pitches, and two cone builds, to choose tower roofs.
import { Prefab } from '../prefab-builder.mjs';
import { here, outward } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'roof_test' });
const fam = 'Metal_Copper_Roof';
['', '_Steep', '_Shallow', '_Flat', '_Hollow'].forEach((s, i) => { for (let k = 0; k < 3; k++) p.set(i * 2, k, 0, 'Rock_Stone'); p.set(i * 2, 3, 0, fam + s, { facing: 'south' }); });
// Steep cone: each ring two blocks high using _Steep on the upper and _Steep on the lower.
function cone(cx, cz, r, y, suffix, rise) {
  let yy = y;
  for (let rr = r; rr >= 1; rr--) for (let k = 0; k < rise; k++, yy++) for (let x = cx - rr; x <= cx + rr; x++) for (let z = cz - rr; z <= cz + rr; z++) {
    const dx = x - cx, dz = z - cz, d = Math.hypot(dx, dz);
    if (d > rr + 0.5 || d <= rr - 0.5) continue;
    p.set(x, yy, z, fam + suffix, { facing: outward(dx, dz) });
  }
  p.set(cx, yy, cz, fam + '_Flat');
}
for (const [x, z] of [[4, 10], [16, 10]]) { for (let y = 0; y < 4; y++) for (let a = -3; a <= 3; a++) for (let b = -3; b <= 3; b++) if (Math.hypot(a, b) <= 3.5) p.set(x + a, y, z + b, 'Rock_Stone'); }
cone(4, 10, 4, 4, '', 1);
cone(16, 10, 4, 4, '_Steep', 2);
p.save(here('../../docs/builds/Eternia_Tavern/tests/roof_test.prefab.json'));
