// Lists catalogue block ids matching a pattern: node tools/prefabs/tests/ids.mjs "Metal_Copper_Roof|Rug"
import { readFileSync } from 'node:fs';
const s = readFileSync(new URL('../../../website/web/hytale-assets/catalog/block_catalog.json', import.meta.url), 'utf8');
const re = new RegExp('"([A-Za-z0-9_]*(?:' + process.argv[2] + ')[A-Za-z0-9_]*)"', 'g');
console.log([...new Set([...s.matchAll(re)].map(m => m[1]))].join(' '));
