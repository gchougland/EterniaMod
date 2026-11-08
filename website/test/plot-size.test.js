import test from 'node:test';import assert from 'node:assert/strict';
import {parseCells,withPlotSize} from '../server/plot-size.js';import {createBridge} from '../server/bridge.js';
test('hex housing rows count their hexagons',()=>{
 const row={worldId:'hub',lattice:'hex:32:20:0:0',cells:'0:1;1:1;0:2',minX:-16,minZ:10,width:64,depth:60};
 const plot=withPlotSize(row);assert.equal(plot.shape,'hex');assert.equal(plot.cellCount,3);assert.equal(plot.sizeLabel,'3 hexagons');
 assert.deepEqual(plot.cells,[{q:0,r:1},{q:1,r:1},{q:0,r:2}]);assert.equal(plot.lattice,'hex:32:20:0:0');
 assert.equal(withPlotSize({lattice:'hex:32:20:0:0',cells:'-2:-3'}).sizeLabel,'1 hexagon');
});
test('rectangle rows keep width and depth',()=>{
 for(const extra of [{},{lattice:'',cells:''}]){const plot=withPlotSize({minX:0,minZ:0,width:24,depth:16,...extra});assert.equal(plot.shape,'rect');assert.equal(plot.width,24);assert.equal(plot.depth,16);assert.equal(plot.cellCount,0);assert.equal(plot.sizeLabel,'24 × 16');}
 assert.equal(withPlotSize({minX:-4,minZ:2,maxX:19,maxZ:25}).sizeLabel,'24 × 24');
 assert.deepEqual(withPlotSize({state:'unclaimed'}),{state:'unclaimed'});
});
test('malformed cells are skipped',()=>{
 assert.deepEqual(parseCells('0:1;;bad;1:x;2:3:4; 1:1 ;0:1;9999999999:0'),[{q:0,r:1},{q:1,r:1}]);
 assert.deepEqual(parseCells(null),[]);assert.deepEqual(parseCells(42),[]);
 const plot=withPlotSize({lattice:'hex:32:20:0:0',cells:'junk;;',minX:0,minZ:0,width:32,depth:20});assert.equal(plot.shape,'rect');assert.equal(plot.sizeLabel,'32 × 20');
});
test('the account overview carries the hexagon count from the game',async t=>{
 const original=globalThis.fetch;t.after(()=>{globalThis.fetch=original;});
 globalThis.fetch=async()=>new Response(JSON.stringify({owned:[],housing:{state:'ACTIVE',lattice:'hex:32:20:0:0',cells:'0:0;0:1;1:0;1:1;-1:1;0:-1;-1:0'}}),{status:200});
 const overview=await createBridge({bridgeUrl:'http://game.invalid',bridgeToken:'x'}).getOverview('11111111-1111-4111-8111-111111111111');
 assert.equal(overview.housing.cellCount,7);assert.equal(overview.housing.sizeLabel,'7 hexagons');
});
