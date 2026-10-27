// Test sheet: which prop facing hangs a shield and a sword flat on a wall to the north of them.
import { Prefab } from '../prefab-builder.mjs';
import { here } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'shield_test' });
p.box([-1, 0, -1], [12, 0, 3], 'Rock_Calcite_Brick');
p.box([-1, 1, -1], [12, 4, -1], 'Rock_Sandstone_White_Brick');
['north', 'east', 'south', 'west'].forEach((f, i) => {
  p.prop('Weapon_Shield_Iron', [i * 3 + 0.5, 2.5, -0.4], { facing: f, scale: 1.4, block: false });
  p.prop('Weapon_Sword_Steel', [i * 3 + 1.5, 2.5, -0.4], { facing: f, scale: 1.2, block: false, roll: 40 });
});
p.save(here('../../docs/builds/Eternia_Tavern/tests/shield_test.prefab.json'));
