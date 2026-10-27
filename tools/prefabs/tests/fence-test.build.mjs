// Test sheet: fence runs and corners at each yaw, and a shallow cloth awning sloping east.
import { Prefab } from '../prefab-builder.mjs';
import { here } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'fence_test' });
const F = 'Wood_Darkwood_Fence', C = '*Wood_Darkwood_Fence_State_Definitions_Corner';
p.box([-1, 0, -1], [24, 0, 12], 'Rock_Calcite_Brick');
// An L for each corner yaw: corner at the knee, a run of two along x and along z.
for (let yaw = 0; yaw < 4; yaw++) {
  const ox = yaw * 6 + 2, oz = 2;
  p.set(ox, 1, oz, C, { rotation: yaw });
  const [dx, dz] = [[-1, 1], [1, 1], [1, -1], [-1, -1]][yaw]; // guess of which way the corner opens, checked by eye
  for (let i = 1; i <= 2; i++) { p.set(ox + dx * i, 1, oz, F); p.set(ox, 1, oz + dz * i, F, { rotation: 1 }); }
  p.set(ox, 2, oz, 'Rock_Sandstone_White_Brick_Decorative'); // marks the corner
  for (let i = 0; i <= yaw; i++) p.set(ox + 2, 1, oz + 4 + i, 'Rock_Calcite'); // yaw count
}
// Shallow cloth awning sloping down east from a wall, stripes along the slope.
p.box([0, 1, 8], [0, 6, 12], 'Rock_Sandstone_White_Brick');
for (let i = 0; i < 3; i++) for (let z = 8; z <= 12; z++)
  p.set(1 + i * 2, 5 - i, z, z % 2 ? 'Cloth_Modern_DarkGreen_Roof_Shallow' : 'Cloth_Roof_White_Shallow', { facing: 'east' });
for (const z of [8, 12]) p.beam([6, 1, z], [6, 2, z], 'Wood_Darkwood_Beam');
p.save(here('../../docs/builds/Eternia_Tavern/tests/fence_test.prefab.json'));
