// Test sheet: how far banners hang below their cell, and where an item prop meets a hanging chain.
import { Prefab } from '../prefab-builder.mjs';
import { here } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'hang_test' });
p.box([-1, 0, -1], [14, 0, 3], 'Rock_Calcite_Brick');
p.box([-1, 1, -1], [14, 7, -1], 'Rock_Sandstone_White_Brick');
for (let y = 1; y <= 7; y++) p.set(-1, y, 0, 'Wood_Fir_Trunk');
// Banners hung at y 5 against the wall to their north, facing south.
p.set(1, 5, 0, 'Furniture_Human_Ruins_Banner', { facing: 'south' });
p.set(3, 5, 0, 'Furniture_Human_Ruins_Banner_Double', { facing: 'south' });
p.set(5, 5, 0, 'Furniture_Human_Ruins_Banner_Triple', { facing: 'south' });
// Chains from a beam at y 6, food props at three heights.
p.box([7, 6, 1], [13, 6, 1], 'Wood_Fir_Trunk', { axis: 'x' });
[[8, 4.4], [10, 4.7], [12, 5.0]].forEach(([x, y]) => {
  p.set(x, 5, 1, 'Deco_Iron_Chain_Small');
  p.prop('Food_Wildmeat_Raw', [x + 0.5, y, 1.5], { block: false, scale: 1.1 });
});
p.set(7, 1, 2, 'Wood_Fir_Trunk'); p.set(7, 2, 2, 'Wood_Fir_Trunk'); p.set(7, 3, 2, 'Deco_Lantern');
p.save(here('../../docs/builds/Eternia_Tavern/tests/hang_test.prefab.json'));
