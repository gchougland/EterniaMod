// Test sheet: how props sit on a tavern table, to calibrate heights for the Hub kit.
import { Prefab } from '../prefab-builder.mjs';
import { here } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'prop_test' });
p.box([-1, 0, -1], [12, 0, 4], 'Rock_Stone_Brick_Smooth');
for (let x = 0; x <= 10; x += 2) p.set(x, 1, 1, 'Furniture_Tavern_Table');
p.set(0, 1, 3, 'Furniture_Village_Counter');
// Block props (Deco blocks): the visual centre, so a table top at about y 2 wants y 2.5 for a full-cell model.
p.prop('Deco_Mug', [0.5, 2.5, 1.5]);
p.prop('Deco_Tankard', [2.5, 2.5, 1.5]);
p.prop('Deco_Plate', [4.5, 2.5, 1.5]);
// Item props: drawn with their own model, position is the model origin.
p.prop('Food_Bread', [6.5, 2.0, 1.5], { block: false });
p.prop('Food_Cheese', [8.5, 2.0, 1.5], { block: false });
p.prop('Food_Pie_Apple', [10.5, 2.0, 1.5], { block: false });
p.prop('Weapon_Shield_Orbis_Knight', [4.5, 3.5, 3.9], { block: false, facing: 'south' });
p.prop('Furniture_Tavern_Chest_Small', [8.5, 1.5, 3.5]);
p.prop('Furniture_Village_Crate', [10.5, 1.5, 3.5], { yaw: 20 });
p.prop('Deco_Potion', [0.5, 2.5, 3.5]);
p.set(-1, 1, 4, 'Rock_Stone_Brick_Smooth'); p.box([2, 1, 4], [6, 4, 4], 'Rock_Sandstone_White_Brick');
p.save(here('../../docs/builds/Eternia_Tavern/tests/prop_test.prefab.json'));
