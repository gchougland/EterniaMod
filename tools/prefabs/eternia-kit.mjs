// Shared pieces for the Eternia Hub kit: style loading, round towers, cone roofs, timber framing, windows and dressing.
// Every Hub build script imports this so the town reads as one place.
import { fileURLToPath } from 'node:url';
import { Palette, loadStyle } from './prefab-builder.mjs';

export const here = p => fileURLToPath(new URL(p, import.meta.url));
export const MASSING = process.argv.includes('--massing');

/** The set's style. With --massing every role becomes a flat grey block, for the grey box the concept is painted from. */
export function eterniaStyle() {
  const style = loadStyle(here('../../docs/build-styles/Eternia_Kingdom/style.json'));
  if (MASSING) {
    const grey = { foundation: 'Rock_Stone', wall: 'Rock_Stone', quoin: 'Rock_Stone', plaster: 'Cloth_Block_Wool_White', frame: 'Wood_Softwood_Planks',
      trim: 'Wood_Softwood_Planks', roof: 'Build_Grey_Roof', floor: 'Wood_Softwood_Planks', stoneFloor: 'Rock_Stone', ceiling: 'Wood_Softwood_Planks', path: 'Rock_Stone' };
    Object.assign(style.roles, grey);
  }
  return style;
}

export const prefabOut = name => here('../../src/main/resources/Server/Prefabs/EterniaMod/Hub/' + name + '.prefab.json');

/** Ids that are one block family: a roof role can be a Palette of slope ids; its flat cap is the same family + _Flat. */
export function roofParts(roof) {
  if (typeof roof === 'string') return { slope: roof, flat: roof + '_Flat', steep: roof + '_Steep', shallow: roof + '_Shallow' };
  const entries = Object.fromEntries(roof.entries);
  const suffixed = (s, salt) => new Palette(Object.fromEntries(Object.entries(entries).map(([id, w]) => [id + s, w])), { salt: roof.salt + salt });
  return { slope: roof, flat: suffixed('_Flat', 1), steep: suffixed('_Steep', 2), shallow: suffixed('_Shallow', 3) };
}

/** Facing of the dominant axis from a centre to a cell (for slopes and things that face outward from a tower). */
export function outward(dx, dz) {
  if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? 'east' : 'west';
  return dz >= 0 ? 'south' : 'north';
}

/** Hollow round tower wall from y0 to y1, radius r (cells within r +/- 0.5 of the centre line). skip(x, y, z) leaves a cell out. */
export function towerWall(p, [cx, cz], r, y0, y1, block, { skip } = {}) {
  for (let y = y0; y <= y1; y++) for (let x = cx - r; x <= cx + r; x++) for (let z = cz - r; z <= cz + r; z++) {
    const d = Math.hypot(x - cx, z - cz);
    if (d > r + 0.5 || d <= r - 0.5) continue;
    if (skip?.(x, y, z)) continue;
    p.set(x, y, z, block);
  }
}
/** Cells strictly inside a round tower of radius r (the room). */
export function towerInside(p, [cx, cz], r, y0, y1, block) {
  for (let y = y0; y <= y1; y++) for (let x = cx - r; x <= cx + r; x++) for (let z = cz - r; z <= cz + r; z++)
    if (Math.hypot(x - cx, z - cz) <= r - 0.5) p.set(x, y, z, block);
}

/**
 * A cone roof: rings of slope blocks facing outward from radius r at y up to a point. steep (the default, the town's tower
 * look) uses the _Steep family two layers per ring; steep false is a 45 degree cone of plain slopes. Returns the top y.
 */
export function coneRoof(p, [cx, cz], r, y, roof, { steep = true, finial = true, fill = 'Metal_Copper', square = false } = {}) {
  const parts = roofParts(roof), slope = steep ? parts.steep : parts.slope, rise = steep ? 2 : 1;
  // square: a pyramid spire whose four hips take corner pieces, so it closes completely. A round cone's rings are
  // stepped circles; where they step, no corner piece fits and notches stay.
  const dist = square ? (dx, dz) => Math.max(Math.abs(dx), Math.abs(dz)) : Math.hypot;
  let yy = y;
  // A _Steep piece is one wide and two high: it goes in the lower cell of each ring and fills the one above itself.
  for (let rr = r; rr >= 1; rr--) for (let k = 0; k < rise; k++, yy++) {
    for (let x = cx - rr; x <= cx + rr; x++) for (let z = cz - rr; z <= cz + rr; z++) {
      const dx = x - cx, dz = z - cz, d = dist(dx, dz);
      if (d > rr + 0.5) continue;
      if (d > rr - 0.5) { if (k === 0) p.set(x, yy, z, slope, { facing: outward(dx, dz) }); else p.remove(x, yy, z); }
      else p.set(x, yy, z, 'Empty');
    }
    // A ring is a stepped circle: where it steps, a piece's open end (the triangle across its facing) meets the
    // inside of the cone. Fill those inside cells so the notch shows roof metal, not the room below.
    if (fill && !square) for (let x = cx - rr; x <= cx + rr; x++) for (let z = cz - rr; z <= cz + rr; z++) {
      const dx = x - cx, dz = z - cz, d = Math.hypot(dx, dz);
      if (d > rr + 0.5 || d <= rr - 0.5) continue;
      const ends = /east|west/.test(outward(dx, dz)) ? [[0, 1], [0, -1]] : [[1, 0], [-1, 0]];
      for (const [ex, ez] of ends) for (let k = 0; k < rise; k++)
        if (Math.hypot(dx + ex, dz + ez) <= rr - 0.5) p.set(x + ex, yy - rise + k, z + ez, fill);
    }
  }
  // The spire: the last ring's middle is filled solid (its top is the point) and a beam rises from it. A flat cap
  // with a post on it reads as floating; a beam alone in the middle leaves a gap round itself.
  if (finial) {
    for (let k = rise; k >= 1; k--) p.set(cx, yy - k, cz, 'Wood_Darkwood_Planks');
    p.beam([cx, yy, cz], [cx, yy + 2, cz], finial === true ? 'Wood_Darkwood_Beam' : finial);
  } else p.set(cx, yy, cz, parts.flat);
  if (square) roofCorners(p, steep ? /_Roof_Steep$/ : /_Roof$/);
  return yy;
}

/**
 * A 45 degree gable with its ridge along z over x0..x1, eaves at y, from z0 to z1 plus `overhang` rows past z1, meeting
 * a main roof whose surface is above(x, z). Only pieces at or above that surface are placed and the main roof is cleared
 * inside, so nothing pokes into the attic; same pitch as the main roof, so roofCorners can close the valleys. The z1
 * end gets a gable wall. Returns top(x), the y of the piece over column x.
 */
export function crossGable(p, [x0, z0], [x1, z1], y, { roof, gable, overhang = 1, above, clear = /_Roof/ }) {
  const parts = roofParts(roof);
  const top = x => y + Math.min(x - x0, x1 - x);
  for (let z = z0; z <= z1 + overhang; z++) for (let x = x0; x <= x1; x++) {
    if (top(x) < above(x, z)) continue;
    // Past the gable wall the main roof's eave row (one lower) would run across the gable's foot: clear it too.
    for (let yy = z > z1 ? y - 1 : y; yy < top(x); yy++) if (clear.test(p.get(x, yy, z) ?? '')) p.set(x, yy, z, 'Empty');
    const mid = x - x0 === x1 - x;
    p.set(x, top(x), z, mid ? parts.flat : parts.slope, mid ? {} : { facing: x - x0 < x1 - x ? 'west' : 'east' });
  }
  if (gable) for (let x = x0 + 1; x < x1; x++) for (let yy = y; yy < top(x); yy++) p.set(x, yy, z1, gable);
  return top;
}

/**
 * A steep gable roof (two layers of _Steep per step) with its ridge along z over x0..x1, eaves at y, running z0..z1, with
 * `overhang` rows past the z1 end and `backOverhang` past z0. gables lists the z of the end walls to fill.
 * When it meets a main roof, pass above(x, z), the main roof's surface y there: a steep piece is kept only where its top
 * reaches that surface (so nothing pokes into the attic), and main roof pieces inside the gable are cleared. Returns
 * the bottom y of the steep piece over column x.
 */
export function steepGableZ(p, [x0, z0], [x1, z1], y, { roof, gable, overhang = 1, backOverhang = overhang, gables = [z0, z1], above, clear = /_Roof/ }) {
  const parts = roofParts(roof);
  const bottom = x => y + 2 * Math.min(x - x0, x1 - x);
  const shown = (x, z) => !above || bottom(x) + 1 >= above(x, z);
  for (let z = z0 - backOverhang; z <= z1 + overhang; z++) for (let x = x0; x <= x1; x++) {
    if (!shown(x, z)) continue;
    // Inside the gable, under its pieces: main roof pieces there would show from the room or the attic.
    for (let yy = y; yy < bottom(x); yy++) if (clear.test(p.get(x, yy, z) ?? '')) p.set(x, yy, z, 'Empty');
    const mid = x - x0 === x1 - x;
    if (mid) { p.set(x, bottom(x), z, gable ?? parts.flat); p.set(x, bottom(x) + 1, z, parts.flat); continue; }
    // _Steep is two cells high: set it in the lower cell; the upper one is its filler.
    const west = x - x0 < x1 - x;
    p.set(x, bottom(x), z, parts.steep, { facing: west ? 'west' : 'east' });
    p.remove(x, bottom(x) + 1, z);
    // A steep piece is low on its outer side, so an attic cell beside it under the main roof would open to the sky.
    const nx = west ? x - 1 : x + 1;
    if (above && gable) for (const yy of [bottom(x), bottom(x) + 1]) if (yy < above(nx, z) && !p.has(nx, yy, z)) p.set(nx, yy, z, gable);
  }
  if (gable) for (const z of gables) for (let x = x0 + 1; x < x1; x++) for (let yy = y; yy < bottom(x); yy++) p.set(x, yy, z, gable);
  return bottom;
}

/**
 * A gabled dormer on a main roof slope (ridge along x). c is its centre column, front the row of its window wall, base
 * the y of its sill (the main slope's height at the front row), dir the way it looks ('south' or 'north'). slopeY(z)
 * gives the main slope's height at each row. Three wide: trunk posts and the window in front, plaster cheeks back to
 * the main slope, its own 45 degree roof (ridge at base + 4) back to where that meets the main slope. Pieces below the
 * main slope are never placed and main roof pieces are cleared only inside it, so it adds no holes and nothing pokes
 * into the attic. Run roofCorners afterwards for the valleys.
 */
export function dormer(p, { c, front, base, dir, slopeY, roof, wall, frame, window }) {
  const s = dir === 'south' ? -1 : 1; // one row towards the ridge
  const parts = roofParts(roof);
  const rows = [];
  for (let i = -1; slopeY(front + s * i) <= base + 4; i++) rows.push(front + s * i);
  for (const z of rows) {
    const inFront = z === front - s, surf = slopeY(z);
    const put = (x, y, block, opts) => { if (y >= surf || inFront) p.set(x, y, z, block, opts); };
    // Its roof: slopes at c-2 (base + 2), c-1 (base + 3), a flat ridge at c (base + 4); the front row is the overhang.
    put(c - 2, base + 2, parts.slope, { facing: 'west' }); put(c + 2, base + 2, parts.slope, { facing: 'east' });
    put(c - 1, base + 3, parts.slope, { facing: 'west' }); put(c + 1, base + 3, parts.slope, { facing: 'east' });
    // The flat ridge is half a block lower than a slope: on the row where the main slope reaches it, the main slope
    // piece stays, or a gap opens under the next row.
    if (surf < base + 4 || inFront) put(c, base + 4, parts.flat);
    if (inFront) continue;
    if (z === front) {
      for (let y = base; y <= base + 2; y++) { p.set(c - 1, y, z, frame, { axis: 'y' }); p.set(c + 1, y, z, frame, { axis: 'y' }); }
      p.set(c, base, z, window); p.set(c, base + 1, z, window); p.set(c, base + 2, z, wall); p.set(c, base + 3, z, wall);
      continue;
    }
    // Cheeks and the space inside, only above the main slope; the inside opens down into the attic.
    for (let y = base; y <= base + 2; y++) put(c - 1, y, wall), put(c + 1, y, wall);
    for (let y = Math.max(base, surf); y <= base + 3; y++) p.set(c, y, z, 'Empty');
  }
}

/**
 * Roof corners. Corner states are never worked out on paste, so where two slopes meet at a hip or a valley the pieces
 * stay straight and leave a notch you can see through. Run this after every roof is placed: a slope whose neighbour behind
 * (uphill) is turned a quarter becomes an outer corner, else one whose neighbour in front (downhill) is turned a quarter
 * becomes an inner corner (a valley). Steep and upside-down pieces are left alone.
 */
export function roofCorners(p, test = /_Roof$/) {
  const STEP = [[0, 1], [1, 0], [0, -1], [-1, 0]]; // yaw 0..3 faces south, east, north, west
  const base = n => n?.replace(/^\*/, '').replace(/_State_Definitions_.*$/, '');
  const yawOf = b => (b.rotation ?? 0) & 3;
  const isSlope = b => b && test.test(base(b.name)) && (b.rotation ?? 0) < 4;
  const changes = [];
  for (const b of p.blocks.values()) {
    if (!isSlope(b) || b.name.startsWith('*')) continue;
    const yaw = yawOf(b), [dx, dz] = STEP[yaw];
    const look = (s) => { const n = p.blocks.get(`${b.x + dx * s},${b.y},${b.z + dz * s}`); return isSlope(n) ? (yawOf(n) - yaw + 4) % 4 : 0; };
    const behind = look(-1), front = look(1);
    let state;
    if (behind === 1 || behind === 3) state = behind === 1 ? 'Corner_Left' : 'Corner_Right';
    else if (front === 1 || front === 3) state = front === 1 ? 'Inverted_Corner_Left' : 'Inverted_Corner_Right';
    if (state) changes.push([b, '*' + b.name + '_State_Definitions_' + state]);
  }
  for (const [b, id] of changes) b.name = id;
  return changes.length;
}

/**
 * Close the open ends of roof slopes. A slope piece is a wedge: its two ends (the sides across its facing) are
 * triangles open above the slope. Where an end meets an attic cell instead of another roof piece (the back of a
 * gable or dormer that runs into a main roof), the attic shows through. inside(x, y, z) says whether an empty cell is
 * indoors; every such cell beside a plain or steep slope's end is filled with block. Run after roofCorners.
 */
export function sealRoofEnds(p, inside, block, test = /_Roof(_Steep)?$/) {
  const ENDS = [[[1, 0], [-1, 0]], [[0, 1], [0, -1]]]; // yaw even (south/north): ends east and west; odd: north and south
  let n = 0;
  for (const b of [...p.blocks.values()]) {
    if (b.name.startsWith('*') || !test.test(b.name) || (b.rotation ?? 0) >= 4) continue;
    for (const [dx, dz] of ENDS[(b.rotation ?? 0) & 1]) {
      const x = b.x + dx, z = b.z + dz;
      if (!p.has(x, b.y, z) && inside(x, b.y, z)) { p.set(x, b.y, z, block); n++; }
    }
  }
  return n;
}

/** Timber framing on one face of a wall plane: posts every `every` blocks (and at both ends), rails at the listed heights. */
export function timberFrame(p, { axis, at, from, to, y0, y1, rails = [], every = 3, block }) {
  const put = (u, y) => axis === 'x' ? p.set(u, y, at, block, { axis: 'y' }) : p.set(at, y, u, block, { axis: 'y' });
  for (let u = from; u <= to; u++) if ((u - from) % every === 0 || u === to) for (let y = y0; y <= y1; y++) put(u, y);
  for (const y of rails) for (let u = from; u <= to; u++) axis === 'x' ? p.set(u, y, at, block, { axis: 'x' }) : p.set(at, y, u, block, { axis: 'z' });
}

/** Item props (food, mugs, books) on a surface: an off-grid entity with a slight turn so rows do not look stamped. */
export function propOn(p, id, [x, y, z], { yaw = 0, scale = 1, block = false, facing = 'south' } = {}) {
  return p.prop(id, [x, y, z], { yaw, scale, block, facing });
}
