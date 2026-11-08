// Plot size for pages. Hex plots (a lattice id plus "q:r;q:r" cells) count hexagons; old rectangle plots keep width x depth.
const CELL=/^(-?\d{1,9}):(-?\d{1,9})$/;
/** Well formed, distinct cells of a "q:r;q:r" string. Anything else is skipped, so bad data reads as fewer cells, never an error. */
export function parseCells(value){
 if(typeof value!=='string')return [];const seen=new Set(),cells=[];
 for(const part of value.split(';')){const m=CELL.exec(part.trim());if(!m)continue;const q=Number(m[1]),r=Number(m[2]),key=q+':'+r;if(!seen.has(key)){seen.add(key);cells.push({q,r});}}
 return cells;
}
const whole=v=>Number.isSafeInteger(v)&&v>0?v:null;
/** Adds {shape,cellCount,cells,sizeLabel} to a housing row or plot footprint. Empty or missing cells mean a rectangle plot; with no size at all the value is returned as is. */
export function withPlotSize(plot){
 if(!plot||typeof plot!=='object'||Array.isArray(plot))return plot;
 const cells=parseCells(plot.cells);
 if(cells.length)return {...plot,shape:'hex',lattice:typeof plot.lattice==='string'?plot.lattice:'',cellCount:cells.length,cells,sizeLabel:cells.length+(cells.length===1?' hexagon':' hexagons')};
 const {cells:_,lattice:__,...rect}=plot,width=whole(plot.width??(Number.isSafeInteger(plot.maxX)&&Number.isSafeInteger(plot.minX)?plot.maxX-plot.minX+1:undefined)),depth=whole(plot.depth??(Number.isSafeInteger(plot.maxZ)&&Number.isSafeInteger(plot.minZ)?plot.maxZ-plot.minZ+1:undefined));
 return width&&depth?{...rect,shape:'rect',width,depth,cellCount:0,cells:[],sizeLabel:width+' × '+depth}:plot;
}
