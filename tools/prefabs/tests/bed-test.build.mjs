// Test sheet: vanilla beds side by side, to pick the guest room beds.
import { Prefab } from '../prefab-builder.mjs';
import { here } from '../eternia-kit.mjs';

const p = new Prefab({ name: 'bed_test' });
const beds = ['Desert', 'Lumberjack', 'Village', 'Tavern', 'Feran', 'Jungle', 'Royal_Magic', 'Temple_Light', 'Frozen_Castle', 'Human_Ruins', 'Kweebec', 'Ancient', 'Crude'];
p.box([-1, 0, -1], [beds.length * 3, 0, 4], 'Wood_Softwood_Planks');
beds.forEach((b, i) => p.set(i * 3, 1, 0, 'Furniture_' + b + '_Bed', { facing: 'south' }));
p.save(here('../../docs/builds/Eternia_Tavern/tests/bed_test.prefab.json'));
