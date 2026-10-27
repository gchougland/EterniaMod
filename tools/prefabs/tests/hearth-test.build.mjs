// Test sheet: hearth and oven candidates in the open, on a floor with a wall behind.
import { Prefab } from '../prefab-builder.mjs';
import { here } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'hearth_test' });
p.box([-1, 0, -2], [24, 0, 4], 'Rock_Calcite_Brick');
p.box([-1, 1, -2], [24, 4, -2], 'Rock_Calcite_Brick');
const fills = [
  ['Bench_Campfire'], ['Furniture_Village_Brazier'], ['Deco_Campfire_Off', 'Deco_Fire'], ['Bench_Cooking'], ['Bench_Furnace'],
  ['Deco_Fire', 'Alchemy_Cauldron'], ['Alchemy_Cauldron_Big'], ['Furniture_Human_Ruins_Brazier'],
];
fills.forEach(([a, b], i) => {
  const x = i * 3;
  p.set(x, 1, 0, a, { facing: 'south' });
  if (b) p.set(x, 2, 0, b, { facing: 'south' });
});
p.save(here('../../docs/builds/Eternia_Tavern/tests/hearth_test.prefab.json'));
