// Eternia_Tavern (The Gilded Gull): build script. node tools/prefabs/Eternia_Tavern.build.mjs [--massing]
// One function per pass. Never edit the .prefab.json by hand. Plan and decisions: docs/builds/Eternia_Tavern/.
import { Prefab, Palette, step } from './prefab-builder.mjs';
import { here, eterniaStyle, prefabOut, roofParts, towerWall, towerInside, coneRoof, dormer, crossGable, timberFrame, roofCorners, sealRoofEnds, MASSING } from './eternia-kit.mjs';
import { lint } from './prefab-lint.mjs';

const { roles: R } = eterniaStyle();
const roof = roofParts(R.roof);

// Layout. Main body x 0..18, z 0..12; ground floor block y 0, ground storey air y 1..5, upper slab y 6,
// upper storey (jettied one block north and south) walls y 7..10, ceiling y 11, main eaves y 11, ridge y 18.
const X0 = 0, X1 = 18, Z0 = 0, Z1 = 12;
const UP = 6, UPTOP = 10, EAVES = 11;
const JZ0 = Z0 - 1, JZ1 = Z1 + 1;                 // jettied upper walls
const TOWER = [19, 13], TR = 3, TTOP = 17;        // round corner tower (south east), wall top y 17, steep cone above
const LOOKOUT = 12;                               // tower lookout floor
const TLADDER = [21, 13];                         // ladder from the tower's upper room to the lookout
const CROSS = { x0: 6, x1: 13, z0: 7, z1: JZ1 };  // front cross gable over the entrance
const DOOR = { x0: 9, x1: 10, z: Z1 };
const CHIMNEY = { x0: -3, x1: -1, z0: 4, z1: 8, top: 20, step: 10 }; // wide breast to y 10, a narrower stack above
const STAIR = { x0: 2, z0: 1, z1: 2 };            // climbs east, six steps, x 2..7
const ATTIC_LADDER = [1, 5];                      // corridor west end up to the attic
const TERRACE = { x0: 19, x1: 25, z0: -1, z1: 8 };

const p = new Prefab({ name: 'Eternia_Tavern', anchor: [0, 0, 0] });
const inBody = (x, z) => x > X0 && x < X1 && z > Z0 && z < Z1;
const inUpper = (x, z) => x > X0 && x < X1 && z > JZ0 && z < JZ1;
const towerD = (x, z) => Math.hypot(x - TOWER[0], z - TOWER[1]);
const slopeS = z => EAVES + (JZ1 - z), slopeN = z => EAVES + (z - JZ0);

// Prop helpers. Block props are placed by their visual centre (a model sits on the bottom of its cell, so a block
// prop of scale s resting on a surface at height top is at top + s / 2); item props by their model origin at the surface.
const bp = (id, x, top, z, { scale = 1, yaw = 0, facing = 'south' } = {}) => p.prop(id, [x, top + scale / 2, z], { scale, yaw, facing });
const ip = (id, x, top, z, { scale = 1.3, yaw = 0, facing = 'south', pitch = 0, roll = 0 } = {}) => p.prop(id, [x, top, z], { scale, yaw, facing, pitch, roll, block: false });
// A banner as a block hangs in the middle of its cell, clear of the wall. As a prop pulled 0.4 towards the wall behind
// it, its rod ends touch the wall. x, y, z is the cell in front of the wall; facing is the way the banner looks.
const banner = (x, y, z, facing) => { const [dx, , dz] = step(facing); p.prop('Furniture_Human_Ruins_Banner', [x + 0.5 - 0.4 * dx, y + 0.5, z + 0.5 - 0.4 * dz], { facing }); };
const jitter = (x, z, salt) => Math.round((Math.sin(x * 12.9898 + z * 78.233 + salt) * 43758.5453 % 1) * 40);

// Two-cell furniture (benches, counters: hitbox x -1..1) covers its main cell and the one west of it when it faces
// south; facing north the footprint turns round, so the main cell is the west one. Each piece covers x0 + 2i, + 1.
function long(id, x0, y, z, count, facing = 'south') {
  for (let i = 0; i < count; i++) {
    const x = x0 + i * 2;
    p.set(facing === 'north' ? x : x + 1, y, z, id, { facing });
  }
}
// Windows rotate only as a wall: 0 in an east-west wall, 1 in a north-south wall.
const winOpts = facing => ({ rotation: facing === 'east' || facing === 'west' ? 1 : 0 });

function skeleton() {
  // Ground: floor and plinth that steps out one block.
  p.box([X0 - 1, -1, Z0 - 1], [X1 + 1, 0, Z1 + 1], R.foundation);
  p.floor(0, [X0 + 1, Z0 + 1], [X1 - 1, Z1 - 1], R.floor);

  // Ground storey: stone walls, air inside.
  p.walls([X0, 1, Z0], [X1, UP - 1, Z1], R.wall);
  p.carve([X0 + 1, 1, Z0 + 1], [X1 - 1, UP - 1, Z1 - 1]);

  // Upper floor slab, jettied north and south.
  p.box([X0, UP, JZ0], [X1, UP, JZ1], R.trim);
  p.floor(UP, [X0 + 1, JZ0 + 1], [X1 - 1, JZ1 - 1], R.floor);

  // Upper storey: plaster walls on the jetty line, air inside, ceiling (the attic floor).
  p.walls([X0, UP + 1, JZ0], [X1, UPTOP, JZ1], R.plaster);
  p.carve([X0 + 1, UP + 1, JZ0 + 1], [X1 - 1, UPTOP, JZ1 - 1]);
  p.floor(EAVES, [X0 + 1, JZ0 + 1], [X1 - 1, JZ1 - 1], R.ceiling);
  p.carve([X0 + 1, EAVES + 1, JZ0 + 2], [X1 - 1, EAVES + 5, JZ1 - 2]); // attic air, below the roof

  // Main roof, ridge along x, broken by dormers on both slopes.
  p.gableRoof([X0, JZ0], [X1, JZ1], EAVES, { slope: roof.slope, ridgeBlock: roof.flat, ridge: 'x', gable: R.plaster, overhang: 1 });
  const dormerParts = { roof: R.roof, wall: R.plaster, frame: R.frame, window: R.window, base: EAVES + 1 };
  dormer(p, { ...dormerParts, c: 3, front: JZ1 - 1, dir: 'south', slopeY: slopeS });
  for (const c of [5, 12]) dormer(p, { ...dormerParts, c, front: JZ0 + 1, dir: 'north', slopeY: slopeN });

  // Cross gable over the entrance, at the main roof's pitch so its valleys take corner pieces. Only the pieces above
  // the main slope are kept and the main roof is cleared inside it; its inside is part of the attic.
  crossGable(p, [CROSS.x0, CROSS.z0], [CROSS.x1, CROSS.z1], EAVES, { roof: R.roof, gable: R.plaster, overhang: 1, above: (x, z) => slopeS(z) });
  if (!MASSING) {
    roofCorners(p);
    // Indoors under the main roof: the attic between the ceiling and the main slope.
    const mainSurface = z => z <= 6 ? slopeN(z) : slopeS(z);
    sealRoofEnds(p, (x, y, z) => x > X0 && x < X1 && z > JZ0 && z < JZ1 && y > EAVES && y < mainSurface(z), R.plaster);
  }

  // Doorway, porch and front step.
  p.carve([DOOR.x0, 1, DOOR.z], [DOOR.x1, 2, DOOR.z]);
  p.set(DOOR.x0, 1, DOOR.z, R.door, { facing: 'south' }); p.set(DOOR.x1, 1, DOOR.z, R.door, { facing: 'north' }); // a double door
  p.box([7, 0, Z1 + 1], [12, 0, Z1 + 3], R.stoneFloor);
  p.stairs([8, 0, Z1 + 4], 'north', 1, 'Rock_Sandstone_White_Brick_Stairs', { width: 4 });
  p.carve([7, 1, Z1 + 1], [12, 5, Z1 + 3]);
  for (const x of [7, 12]) p.beam([x, 1, Z1 + 3], [x, 3, Z1 + 3], R.post);
  p.beam([7, 4, Z1 + 3], [12, 4, Z1 + 3], R.post); // post to post: ends past a post read as floating
  p.shedRoof([7, Z1 + 2], [12, Z1 + 3], 6, { slope: roof.slope, down: 'south', overhang: 0 });

  // Stairs to the upper floor along the north wall, climbing east; the slab is cut above them.
  p.carve([STAIR.x0 + 1, UP, STAIR.z0], [STAIR.x0 + 5, UP, STAIR.z1]);
  for (let i = 0; i < 6; i++) for (let z = STAIR.z0; z <= STAIR.z1; z++) {
    p.set(STAIR.x0 + i, 1 + i, z, R.stairs, { facing: 'west' });
    for (let y = 1; y < 1 + i; y++) p.set(STAIR.x0 + i, y, z, R.trim);
  }

  // Round tower on the south east corner: open to the hall and the upper floor where it overlaps the body.
  p.cylinder([TOWER[0], -1, TOWER[1]], TR, 2, R.foundation);
  const skipInside = (x, y, z) => y <= UPTOP && (inBody(x, z) || inUpper(x, z));
  towerWall(p, TOWER, TR, 1, TTOP, R.wall, { skip: skipInside });
  towerInside(p, TOWER, TR, 1, UP - 1, 'Empty');
  towerInside(p, TOWER, TR, UP + 1, TTOP, 'Empty');
  towerInside(p, TOWER, TR, UP, UP, R.floor);
  towerInside(p, TOWER, TR, 0, 0, R.floor);
  towerInside(p, TOWER, TR, LOOKOUT, LOOKOUT, R.floor);
  for (let x = 16; x <= X1; x++) for (let z = 10; z <= JZ1; z++) if (towerD(x, z) <= TR - 0.5) {
    for (let y = 1; y < UP; y++) p.set(x, y, z, 'Empty');
    for (let y = UP + 1; y <= UPTOP; y++) p.set(x, y, z, 'Empty');
  }
  coneRoof(p, TOWER, TR, TTOP + 1, R.roof, { finial: !MASSING, square: true });

  // Chimney on the west gable, fireplace opening into the hall.
  p.box([CHIMNEY.x0, 0, CHIMNEY.z0], [CHIMNEY.x1, CHIMNEY.step, CHIMNEY.z1], R.foundation);
  p.box([CHIMNEY.x0 + 1, CHIMNEY.step + 1, CHIMNEY.z0 + 1], [CHIMNEY.x1, CHIMNEY.top, CHIMNEY.z1 - 1], R.foundation);
  for (let z = CHIMNEY.z0; z <= CHIMNEY.z1; z++) p.set(CHIMNEY.x0, CHIMNEY.step + 1, z, 'Rock_Calcite_Cobble_Stairs', { facing: 'west' });
  p.carve([CHIMNEY.x1, 1, CHIMNEY.z0 + 1], [X0, 2, CHIMNEY.z1 - 1]);
}

function structure() {
  // Ground storey stone: grey base courses blending into sandstone; quoins at the corners.
  p.gradient([X0, 1, Z0], [X1, UP - 1, Z1], [{ block: R.foundation, weight: 22 }, { block: R.wall, weight: 78 }], { axis: 'y', blend: 0.2, only: /Rock_(Calcite|Sandstone)/ });
  for (const [x, z] of [[X0, Z0], [X1, Z0], [X0, Z1]]) for (let y = 2; y < UP; y++) {
    p.set(x, y, z, R.quoin);
    if (y % 2 === 0) { p.set(x + (x === X0 ? 1 : -1), y, z, R.quoin); } else p.set(x, y, z + (z === Z0 ? 1 : -1), R.quoin);
  }
  // Tower: base course, string courses at each floor, a cornice under the cone.
  for (let y = 1; y <= TTOP; y++) for (let x = TOWER[0] - TR; x <= TOWER[0] + TR; x++) for (let z = TOWER[1] - TR; z <= TOWER[1] + TR; z++) {
    const d = towerD(x, z); if (d > TR + 0.5 || d <= TR - 0.5 || !p.has(x, y, z)) continue;
    if (y === 1) p.set(x, y, z, R.foundation);
    else if (y === UP || y === LOOKOUT || y === TTOP) p.set(x, y, z, R.quoin);
  }
  p.gradient([TOWER[0] - TR, 1, TOWER[1] - TR], [TOWER[0] + TR, 3, TOWER[1] + TR], [{ block: R.foundation, weight: 40 }, { block: R.wall, weight: 60 }], { axis: 'y', blend: 0.3, only: /Rock_(Calcite|Sandstone_White)(_Cobble|_Brick)?(_Smooth)?$/ });

  // Upper storey timber framing on all four faces, posts every three blocks, sill and head rails.
  for (const z of [JZ0, JZ1]) timberFrame(p, { axis: 'x', at: z, from: X0, to: X1, y0: UP + 1, y1: UPTOP, rails: [UPTOP], every: 3, block: R.frame });
  for (const x of [X0, X1]) timberFrame(p, { axis: 'z', at: x, from: JZ0, to: JZ1, y0: UP + 1, y1: UPTOP, rails: [UPTOP], every: 3, block: R.frame });
  // Gable ends: tie beam, king post and two queen posts in the plaster triangle.
  for (const x of [X0, X1]) {
    p.beam([x, EAVES, JZ0 + 1], [x, EAVES, JZ1 - 1], R.frame); // stops inside the eave rows, which are roof
    for (const z of [2, 6, 10]) for (let y = EAVES + 1; y <= EAVES + 8; y++) if (/Salt|Chalk|Lime/.test(p.get(x, y, z) ?? '')) p.set(x, y, z, R.frame, { axis: 'y' });
  }
  // Front cross gable: a post either side of a two by two window.
  for (const x of [8, 11]) for (const y of [EAVES, EAVES + 1]) p.set(x, y, JZ1, R.frame, { axis: 'y' });
  for (const x of [9, 10]) for (const y of [EAVES + 1, EAVES + 2]) p.set(x, y, JZ1, R.window, winOpts('south'));
  // Jetty brackets under the overhang at every post: upside down stairs facing out, their full side against the wall.
  for (let x = X0; x <= X1; x += 3) {
    if (towerD(x, JZ1) > TR + 0.5) p.set(x, UP - 1, JZ1, R.stairs, { facing: 'south', upsideDown: true });
    p.set(x, UP - 1, JZ0, R.stairs, { facing: 'north', upsideDown: true });
  }
  // Ground floor ceiling beams (east-west), clear of the stairwell.
  for (const z of [3, 7, 11]) p.beam([X0 + 1, UP - 1, z], [X1 - 1, UP - 1, z], R.frame);
  for (let x = 13; x <= X1 - 1; x++) p.set(x, UP - 1, 11, towerD(x, 11) <= TR - 0.5 ? 'Empty' : p.get(x, UP - 1, 11));

  // Windows. Ground floor: stone frames with a decorative lintel; upper floor: in the timber bays with shutters.
  const W = R.window;
  const groundWin = (cells, facing) => {
    for (const [x, z] of cells) { p.set(x, 2, z, W, winOpts(facing)); p.set(x, 3, z, W, winOpts(facing)); p.set(x, 4, z, R.quoin); }
  };
  groundWin([[2, Z1], [3, Z1], [5, Z1], [6, Z1], [14, Z1]], 'south');
  groundWin([[9, Z0], [10, Z0], [15, Z0]], 'north');
  groundWin([[X0, 2], [X0, 3], [X0, 9], [X0, 10]], 'west');
  groundWin([[X1, 7], [X1, 8]], 'east');
  const upperWin = (cells, facing) => { for (const [x, z] of cells) for (const y of [UP + 2, UP + 3]) p.set(x, y, z, W, winOpts(facing)); };
  upperWin([[1, JZ1], [2, JZ1], [4, JZ1], [5, JZ1], [13, JZ1], [14, JZ1]], 'south');
  upperWin([[9, JZ1], [10, JZ1]], 'south');
  upperWin([[1, JZ0], [2, JZ0], [4, JZ0], [5, JZ0], [7, JZ0], [8, JZ0], [13, JZ0], [14, JZ0], [16, JZ0], [17, JZ0]], 'north');
  upperWin([[X0, 1], [X0, 2], [X0, 9], [X0, 10]], 'west');
  upperWin([[X1, 1], [X1, 2], [X1, 5], [X1, 6], [X1, 8], [X1, 9]], 'east');
  // Shutters beside the south upper windows.
  for (const [x, f] of [[0, 'south'], [3, 'south'], [6, 'south'], [12, 'south'], [15, 'south']]) for (const y of [UP + 2, UP + 3]) p.set(x, y, JZ1 + 1, 'Deco_Shutter', { facing: f });
  // Tower windows: two tall slits per floor, four at the lookout.
  const tw = (x, y, z, facing) => { p.set(x, y, z, W, winOpts(facing)); p.set(x, y + 1, z, W, winOpts(facing)); };
  tw(TOWER[0] + TR, 2, TOWER[1], 'east'); tw(TOWER[0], 2, TOWER[1] + TR, 'south');
  tw(TOWER[0] + TR, 8, TOWER[1], 'east'); tw(TOWER[0], 8, TOWER[1] + TR, 'south');
  tw(TOWER[0] + TR, 14, TOWER[1], 'east'); tw(TOWER[0], 14, TOWER[1] + TR, 'south'); tw(TOWER[0], 14, TOWER[1] - TR, 'north'); tw(TOWER[0] - TR, 14, TOWER[1], 'west');

  // Chimney: a stepped cap and two pots.
  p.box([CHIMNEY.x0, CHIMNEY.top - 1, CHIMNEY.z0], [CHIMNEY.x1 + 1, CHIMNEY.top - 1, CHIMNEY.z1], 'Rock_Calcite_Brick');
  p.set(-2, CHIMNEY.top + 1, 6, 'Rock_Calcite_Brick'); p.set(-1, CHIMNEY.top + 1, 6, 'Rock_Calcite_Brick');
  // The body's frame posts and rails cross the tower's rooms where the two overlap: clear them out again.
  for (let x = TOWER[0] - TR; x <= TOWER[0] + TR; x++) for (let z = TOWER[1] - TR; z <= TOWER[1] + TR; z++) if (towerD(x, z) <= TR - 0.5) {
    for (let y = 1; y < UP; y++) p.set(x, y, z, 'Empty');
    for (let y = UP + 1; y <= UPTOP; y++) p.set(x, y, z, 'Empty');
  }
}

function partitions() {
  // Kitchen behind the bar (north east), with a door to the bar and one to the terrace.
  p.walls([12, 1, Z0], [X1, UP - 1, 4], R.wall);
  p.carve([13, 1, 1], [17, UP - 1, 3]);
  p.carve([15, 1, 4], [15, 2, 4]); p.set(15, 1, 4, R.door, { facing: 'south' });
  p.carve([X1, 1, 2], [X1, 2, 2]); p.set(X1, 1, 2, R.door, { facing: 'east' });
  p.floor(0, [13, 1], [17, 3], R.stoneFloor);
  // Upper floor: north rooms, corridor z 5..6, three guest rooms south split at x 6 and x 13.
  const PW = R.plaster;
  p.box([10, UP + 1, Z0], [10, UPTOP, 4], PW); p.box([10, UP + 1, 4], [X1 - 1, UPTOP, 4], PW);
  p.box([X0 + 1, UP + 1, 7], [X1 - 1, UPTOP, 7], PW);
  p.box([6, UP + 1, 8], [6, UPTOP, JZ1 - 1], PW); p.box([13, UP + 1, 8], [13, UPTOP, JZ1 - 1], PW);
  // Every partition is timber framed like the outside: posts every three blocks, sill and head rails.
  timberFrame(p, { axis: 'x', at: 7, from: X0 + 1, to: X1 - 1, y0: UP + 1, y1: UPTOP, rails: [UP + 1, UPTOP], every: 3, block: R.frame });
  timberFrame(p, { axis: 'x', at: 4, from: 10, to: X1 - 1, y0: UP + 1, y1: UPTOP, rails: [UPTOP], every: 3, block: R.frame });
  for (const x of [6, 13]) timberFrame(p, { axis: 'z', at: x, from: 7, to: JZ1, y0: UP + 1, y1: UPTOP, rails: [UPTOP], every: 3, block: R.frame });
  timberFrame(p, { axis: 'z', at: 10, from: Z0, to: 4, y0: UP + 1, y1: UPTOP, rails: [UPTOP], every: 4, block: R.frame });
  for (const [x, z, f] of [[12, 4, 'north'], [3, 7, 'south'], [9, 7, 'south'], [15, 7, 'south']]) { p.carve([x, UP + 1, z], [x, UP + 2, z]); p.set(x, UP + 1, z, R.door, { facing: f }); }
  // Stairwell railing on the upper floor: on the slab along the open side of the well only. The top step (x 7) and the
  // landing east of it stay open.
  // A fence unturned runs east to west; turned once, north to south. The knee is a corner joining east and north.
  for (let x = STAIR.x0 + 1; x <= STAIR.x0 + 4; x++) p.set(x, UP + 1, STAIR.z1 + 1, R.fence);
  for (let z = STAIR.z0; z <= STAIR.z1; z++) p.set(STAIR.x0, UP + 1, z, R.fence, { rotation: 1 });
  p.set(STAIR.x0, UP + 1, STAIR.z1 + 1, '*' + R.fence + '_State_Definitions_Corner', { rotation: 2 });
  // Ladders: corridor to attic, tower room to lookout.
  for (let y = UP + 1; y <= EAVES; y++) p.set(ATTIC_LADDER[0], y, ATTIC_LADDER[1], 'Furniture_Tavern_Ladder', { facing: 'east' });
  for (let y = UP + 1; y <= LOOKOUT; y++) p.set(TLADDER[0], y, TLADDER[1], 'Furniture_Tavern_Ladder', { facing: 'west' });
}

function hall() {
  // Floors: flagstones round the hearth, at the bar and inside the door; planks elsewhere.
  p.floor(0, [1, 3], [3, 9], R.stoneFloor);
  p.floor(0, [12, 5], [17, 9], R.stoneFloor);
  p.floor(0, [8, 10], [11, 11], R.stoneFloor);
  // Hearth: three fires on logs at the back of the opening, an iron grate across its front, stone pillars either
  // side carrying a solid mantel (a full block, so what stands on it stands on something).
  for (let z = 5; z <= 7; z++) {
    p.set(CHIMNEY.x1, 1, z, 'Deco_Fire');
    bp('Deco_Campfire_Off', CHIMNEY.x1 + 0.5, 1, z + 0.5, { scale: 0.8, yaw: 30 * z }); // the logs each fire burns on
    p.set(X0, 1, z, 'Deco_Iron_Bars', { rotation: 1 }); // a run north to south
    p.set(X0, 3, z, R.quoin);
  }
  for (const z of [4, 8]) p.beam([1, 1, z], [1, 2, z], 'Rock_Calcite_Brick');
  p.box([1, 3, 4], [1, 3, 8], 'Wood_Darkwood_Planks');
  for (const z of [4.4, 5.1, 7.9, 8.6]) bp('Furniture_Tavern_Candle', 1.5, 4, z, { scale: z % 1 > 0.5 ? 0.45 : 0.6 });
  bp('Deco_Mug', 1.5, 4, 6.4, { scale: 0.5 }); bp('Deco_Book_Pile_Small', 1.5, 4, 7.2, { scale: 0.5, yaw: 80 });
  p.set(1, 1, 3, 'Furniture_Tavern_Barrel');
  // Long tables (2 x 2 tavern tables, x 5..8) with benches either side, laid for supper.
  for (const z of [5, 9]) {
    p.set(5, 1, z, 'Furniture_Tavern_Table'); p.set(7, 1, z, 'Furniture_Tavern_Table');
    long('Furniture_Tavern_Bench', 5, 1, z - 1, 2, 'south');
    long('Furniture_Tavern_Bench', 5, 1, z + 2, 2, 'north');
    // About half the seats are laid: a plate with the drink beside it, never on it. A couple hold only a drink.
    const laid = z === 5 ? [[5, 0, 'plate'], [7, 0, 'plate'], [6, 1, 'plate'], [8, 1, 'drink']] : [[6, 0, 'plate'], [8, 0, 'drink'], [5, 1, 'plate'], [7, 1, 'plate']];
    for (const [x, side, kind] of laid) {
      const j = jitter(x, z + side, 1), zz = z + (side ? 1.7 : 0.3);
      if (kind === 'plate') bp('Deco_Plate', x + 0.35, 2, zz, { scale: 0.5, yaw: j });
      if ((x + side) % 2) bp('Deco_Tankard', x + 0.85, 2, zz, { scale: 1.1, yaw: j });
      else bp('Deco_Mug', x + 0.85, 2, zz, { scale: 0.55, yaw: j });
    }
    bp('Furniture_Tavern_Candle', 6.95, 2, z + 1, { scale: 0.5 });
    ip(z === 5 ? 'Food_Pie_Meat' : 'Food_Pie_Apple', 5.7, 2, z + 1, { yaw: jitter(5, z, 4) });
    ip('Food_Bread', 7.7, 2, z + 0.95, { yaw: jitter(7, z, 5) });
    ip('Food_Cheese', 8.5, 2, z + 1.1, { scale: 0.6, yaw: 30 });
    ip(z === 5 ? 'Food_Fish_Grilled' : 'Food_Kebab_Meat', 6.3, 2, z + 1.15, { yaw: jitter(6, z, 6) });
  }
  // A table for two in the front window corner.
  p.set(1, 1, 10, 'Furniture_Tavern_Table');
  p.set(1, 1, 9, 'Furniture_Tavern_Chair', { facing: 'south' }); p.set(3, 1, 10, 'Furniture_Tavern_Chair', { facing: 'west' });
  bp('Deco_Tankard', 1.5, 2, 10.5, { scale: 1.1, yaw: 20 }); bp('Deco_Tankard', 2.5, 2, 10.4, { scale: 1.1, yaw: -40 });
  ip('Food_Wildmeat_Cooked', 1.9, 2, 11.2, { yaw: 15 });
  bp('Furniture_Tavern_Candle', 2.6, 2, 11.3, { scale: 0.5 });
  // A table by the stairs.
  p.set(9, 1, 1, 'Furniture_Tavern_Table');
  p.set(9, 1, 3, 'Furniture_Tavern_Chair', { facing: 'north' }); p.set(11, 1, 1, 'Furniture_Tavern_Chair', { facing: 'west' });
  bp('Deco_Mug', 9.4, 2, 1.5, { scale: 0.55 }); ip('Deco_Map', 10.1, 2.02, 1.9, { scale: 1.6, yaw: 12 });

  // The bar: counters facing the hall, stools, a back bar of barrels and shelves on the kitchen wall.
  long('Furniture_Village_Counter', 12, 1, 7, 3);
  for (const x of [12, 13.5, 15, 16.5]) p.set(Math.floor(x), 1, 8, 'Furniture_Tavern_Stool');
  for (const x of [12, 14, 16]) {
    bp('Deco_Tankard', x + 0.4, 2, 7.4, { scale: 1.1, yaw: jitter(x, 7, 2) });
    bp('Deco_Mug', x + 1.4, 2, 7.6, { scale: 0.55, yaw: jitter(x, 7, 3) });
  }
  bp('Deco_Lantern', 17.4, 2, 7.5, { scale: 0.8 });
  bp('Deco_Potion', 13.1, 2, 7.3, { scale: 0.7 });
  for (const x of [13, 14, 16, 17]) p.set(x, 1, 5, 'Furniture_Tavern_Barrel', { facing: 'south' });
  for (let x = 13; x <= 17; x++) p.set(x, 4, 5, 'Furniture_Tavern_Shelf', { facing: 'south' });
  // Bottles and mugs stand on the shelves (their top is at the top of the shelf cell).
  for (let x = 13; x <= 17; x++) {
    bp(x % 2 ? 'Deco_Potion' : 'Deco_Mug', x + 0.3, 4.95, 5.25, { scale: x % 2 ? 0.7 : 0.5 });
    bp('Deco_Potion', x + 0.72, 4.95, 5.25, { scale: 0.6, yaw: 40 });
  }
  for (let x = 13; x <= 17; x++) p.set(x, 3, 5, 'Furniture_Tavern_Shelf', { facing: 'south' });
  // The lower shelf's things stay short enough to clear the shelf above.
  for (let x = 13; x <= 17; x++) bp(x % 2 ? 'Deco_Tankard' : 'Deco_Potion', x + 0.5, 3.95, 5.25, { scale: x % 2 ? 0.6 : 0.55, yaw: jitter(x, 3, 8) });
  // Teal house banners high on flat stretches of wall, clear of windows, chairs and beams.
  banner(4, 4, Z1 - 1, 'north');
  banner(11, 4, Z0 + 1, 'south');
}

function kitchen() {
  // The cooking fire: too little room for a chimney breast, so two fires sit in the north wall itself, backed by a
  // stone bulge outside (under the jetty), with an iron grate across the front and a brick lintel over the opening.
  p.box([16, 0, Z0 - 1], [17, 3, Z0 - 1], 'Rock_Calcite_Brick'); // clear of the window at x 15
  for (const x of [16, 17]) p.set(x, 4, Z0 - 1, 'Rock_Calcite_Cobble_Stairs', { facing: 'north' });
  for (const x of [16, 17]) {
    p.set(x, 1, Z0, 'Deco_Fire'); p.set(x, 2, Z0, 'Empty'); p.set(x, 3, Z0, 'Rock_Calcite_Brick');
    bp('Deco_Campfire_Off', x + 0.5, 1, Z0 + 0.5, { scale: 0.8, yaw: 40 * x });
    p.set(x, 1, Z0 + 1, 'Deco_Iron_Bars'); // a run east to west
  }
  long('Furniture_Village_Counter', 13, 1, 1, 1, 'south');
  ip('Food_Beef_Raw', 13.5, 2, 1.4, { yaw: 20 }); ip('Food_Fish_Raw', 14.4, 2, 1.5, { yaw: -50 });
  ip('Food_Vegetable_Cooked', 13.8, 2, 1.8);
  p.set(13, 1, 2, 'Furniture_Tavern_Table');
  ip('Food_Pie_Pumpkin', 13.5, 2, 2.6); ip('Food_Bread', 14.3, 2, 3.3, { yaw: 60 }); ip('Food_Egg', 14.6, 2, 2.5, { scale: 1.5 });
  // Stores stand in the corners; the path from the bar door (15, 4) to the terrace door (18, 2) stays clear.
  bp('Furniture_Ancient_Sack', 17.5, 1, 3.5, { scale: 0.9, yaw: 25 });
  bp('Furniture_Village_Crate', 15.5, 1, 1.45, { scale: 0.8, yaw: -15 }); ip('Plant_Crop_Cauliflower_Item', 15.6, 1.8, 1.45, { scale: 1.5 });
  ip('Plant_Fruit_Apple', 15.3, 1.8, 1.6, { scale: 1.2 });
  bp('Deco_Bucket', 16.6, 1, 3.6, { scale: 0.8 });
  p.set(15, 4, 3, 'Deco_Lantern_Ceiling'); // hangs under the ceiling beam at z 3
  // Shelves of jars and plates on the north wall, hams and fish hanging from chains, a barrel of flour.
  for (let x = 13; x <= 15; x++) {
    p.set(x, 3, 1, 'Furniture_Tavern_Shelf', { facing: 'south' });
    bp(x % 2 ? 'Deco_Potion' : 'Deco_Plate', x + 0.3, 3.95, 1.25, { scale: x % 2 ? 0.7 : 0.45 });
    bp('Deco_Mug', x + 0.75, 3.95, 1.25, { scale: 0.5, yaw: 30 * x });
  }
  // A chain in cell y ends at y itself: the food's origin goes there so it hangs from the last link.
  for (const [x, z, food] of [[13, 2, 'Food_Wildmeat_Raw'], [15, 2, 'Food_Fish_Raw'], [16, 2, 'Food_Pork_Raw']]) {
    p.set(x, 5, z, 'Deco_Iron_Chain_Small');
    ip(food, x + 0.5, 5, z + 0.5, { scale: 1.1 });
  }
}

function snug() {
  // The round tower snug: a sea chart on a table, chairs, books, a ladder of stories.
  const [cx, cz] = TOWER;
  p.set(cx - 1, 1, cz, 'Furniture_Tavern_Table');
  // Table 2 x 2 at x cx-1..cx, z cz..cz+1, a chair on each side.
  p.set(cx - 1, 1, cz - 1, 'Furniture_Tavern_Chair', { facing: 'south' }); p.set(cx - 1, 1, cz + 2, 'Furniture_Tavern_Chair', { facing: 'north' });
  p.set(cx + 1, 1, cz, 'Furniture_Tavern_Chair', { facing: 'west' }); p.set(cx - 2, 1, cz + 1, 'Furniture_Tavern_Chair', { facing: 'east' });
  ip('Deco_Map', cx - 0.1, 2.02, cz + 1, { scale: 2.2, yaw: 8 });
  ip('Deco_Scroll', cx + 0.6, 2, cz + 0.4, { scale: 1.5, yaw: 70 });
  bp('Deco_Inkwell', cx - 0.6, 2, cz + 1.6, { scale: 0.7 });
  bp('Furniture_Tavern_Candle', cx + 0.6, 2, cz + 1.6, { scale: 0.5 });
  bp('Deco_Tankard', cx - 0.7, 2, cz + 0.4, { scale: 1.2, yaw: 40 });
  p.set(cx + 2, 1, cz - 1, 'Furniture_Village_Bookcase', { facing: 'west' });
  p.set(cx + 2, 2, cz - 1, 'Furniture_Village_Bookcase', { facing: 'west' });
  bp('Deco_Book_Pile_Small', cx + 1.5, 1, cz + 2.2, { scale: 0.8, yaw: 30 });
  bp('Furniture_Tavern_Barrel', cx + 0.5, 1, cz + 2.4, { scale: 0.85 });
  p.set(cx, 5, cz, 'Furniture_Tavern_Chandelier');
}

function upstairs() {
  const F = UP; // floor block y; furniture stands at y 7
  const y = F + 1;
  // The landing (x 8..9, z 1..4) stays clear from the top step to the corridor. A reading corner against the north
  // wall beside it: a chair and a stool with a candle, books on the floor; bookcases on the west wall.
  p.set(9, y, 0, 'Furniture_Tavern_Chair', { facing: 'south' }); p.set(8, y, 0, 'Furniture_Tavern_Stool');
  bp('Deco_Book_Pile_Large', 1.5, y, 1.5, { scale: 0.7, yaw: 20 });
  p.set(1, y, 3, 'Furniture_Village_Bookcase', { facing: 'east' }); p.set(1, y, 4, 'Furniture_Village_Bookcase', { facing: 'east' });
  p.set(1, y + 1, 3, 'Furniture_Village_Bookcase', { facing: 'east' }); p.set(1, y + 1, 4, 'Furniture_Village_Bookcase', { facing: 'east' });
  p.set(5, UPTOP, 4, 'Deco_Lantern_Ceiling');
  // Innkeeper's room: bed, desk with the ledger, chest, wardrobe.
  p.set(16, y, 1, 'Furniture_Tavern_Bed', { facing: 'south' });
  p.set(11, y, 1, 'Furniture_Tavern_Table');
  p.set(11, y, 3, 'Furniture_Tavern_Chair', { facing: 'north' });
  bp('Deco_Book_Pile_Small', 11.4, y + 1, 1.4, { scale: 0.7 }); bp('Deco_Inkwell', 12.2, y + 1, 1.5, { scale: 0.7 });
  ip('Deco_Scroll', 11.8, y + 1, 1.7, { scale: 1.3, yaw: 25 });
  bp('Furniture_Tavern_Candle', 12.6, y + 1, 1.3, { scale: 0.5 });
  bp('Furniture_Tavern_Chest_Small', 14.5, y, 3.4, { scale: 1, facing: 'north' });
  p.set(14, y, 1, 'Furniture_Tavern_Wardrobe', { facing: 'south' });
  p.set(15, UPTOP, 2, 'Deco_Lantern_Ceiling');
  // Corridor: a darker board runner set into the floor (wool would show on the hall ceiling below), lanterns.
  for (let x = 2; x <= 16; x++) p.set(x, F, 5, 'Wood_Darkwood_Planks');
  for (const x of [5, 12]) p.set(x, UPTOP, 6, 'Deco_Lantern_Ceiling');
  // Room A (west): bed, wardrobe, chest at the foot, candle on a stool.
  p.set(1, y, 8, 'Furniture_Village_Bed', { facing: 'south' });
  p.set(5, y, 10, 'Furniture_Tavern_Wardrobe', { facing: 'west' });
  p.set(3, y, 12, 'Furniture_Tavern_Stool');
  bp('Furniture_Tavern_Chest_Small', 1.5, y, 11.5, { facing: 'east' });
  p.set(3, UPTOP, 10, 'Deco_Lantern_Ceiling');
  // Room B (the gable room): teal bed, desk under the window, bookshelf, wardrobe, rug, rafters and a hanging lantern.
  p.set(7, y, 8, 'Furniture_Human_Ruins_Bed', { facing: 'south' });
  bp('Furniture_Tavern_Chest_Small', 7.9, y, 11.4, { facing: 'south' });
  // The desk stands in the south east corner with its chair beside it, so the middle of the room and the way in stay open.
  p.set(11, y, 11, 'Furniture_Tavern_Table'); p.set(10, y, 11, 'Furniture_Tavern_Chair', { facing: 'east' });
  bp('Deco_Book_Pile_Small', 11.4, y + 1, 12.5, { scale: 0.7, yaw: -10 }); bp('Deco_Inkwell', 12.4, y + 1, 12.5, { scale: 0.6 });
  ip('Deco_Map', 11.9, y + 1.02, 11.6, { scale: 1.4, yaw: -8 }); bp('Furniture_Tavern_Candle', 12.6, y + 1, 11.4, { scale: 0.5 });
  p.set(12, y, 8, 'Furniture_Village_Bookcase', { facing: 'west' });
  p.set(12, y, 10, 'Furniture_Tavern_Wardrobe', { facing: 'west' });
  p.set(9, UPTOP, 10, 'Deco_Lantern_Ceiling');
  p.set(7, y + 2, 11, 'Furniture_Tavern_Painting', { facing: 'east' });
  bp('Furniture_Human_Ruins_Pot_Small', 7.5, y, 12.5, { scale: 0.9 });
  // Room C and the tower room: the captain's suite.
  p.set(16, y, 8, 'Furniture_Lumberjack_Bed', { facing: 'south' });
  p.set(14, y, 8, 'Furniture_Tavern_Stool');
  // The captain's chart table against the tower's south wall, its chair beside it; the ladder (east) stays clear.
  p.set(TOWER[0], y, TOWER[1] + 1, 'Furniture_Tavern_Table'); p.set(TOWER[0] - 1, y, TOWER[1] + 2, 'Furniture_Tavern_Chair', { facing: 'east' });
  ip('Deco_Map', TOWER[0] + 1, y + 1.02, TOWER[1] + 2, { scale: 1.8, yaw: -15 });
  bp('Furniture_Tavern_Chest_Small', 16.5, y, 12.4, { facing: 'north' });
  p.set(TOWER[0], UPTOP, TOWER[1], 'Deco_Lantern_Ceiling');
}

function attic() {
  // A dusty storeroom: crates, sacks and barrels as props, cobwebs in the rafters, one lantern.
  const y = EAVES + 1;
  const crates = [[3, 3], [4, 3], [3, 4], [14, 3], [15, 4], [16, 9], [15, 9], [2, 9]];
  crates.forEach(([x, z], i) => bp(i % 3 ? 'Furniture_Village_Crate' : 'Furniture_Ancient_Sack', x + 0.5, y, z + 0.5, { scale: 0.9, yaw: jitter(x, z, 9) }));
  // Only crates stack, and only on crates (crates[1] is a crate; a sack is soft and round).
  bp('Furniture_Village_Crate', 4.5, y + 0.9, 3.5, { scale: 0.8, yaw: 20 });
  for (const [x, z] of [[6, 4], [11, 4], [16, 6]]) bp('Furniture_Tavern_Barrel', x + 0.5, y, z + 0.5, { scale: 0.9 });
  bp('Furniture_Tavern_Chest_Small', 9.5, y, 4.5, { facing: 'south' });
  // Cobwebs in corners: the gable wall on one side and the roof slope over them.
  for (const [x, z] of [[1, 1], [17, 1], [1, 11]]) p.set(x, y, z, 'Deco_SpiderWeb');
  p.set(9, 16, 5, 'Deco_Lantern_Ceiling');
}

function lookout() {
  const y = LOOKOUT + 1, [cx, cz] = TOWER;
  p.set(cx - 1, y, cz - 1, 'Furniture_Tavern_Bench', { facing: 'south' });
  bp('Furniture_Village_Crate', cx - 1.5, y, cz + 1.5, { scale: 0.9, yaw: 15 });
  ip('Deco_Map', cx - 1.5, y + 0.92, cz + 1.5, { scale: 1.6, yaw: 10 });
  p.set(cx, TTOP, cz, 'Deco_Lantern_Ceiling');
}

function exterior() {
  // Approach: cobbles from the step out to the street, flowers and bushes along the plinth.
  p.box([6, -1, Z1 + 4], [13, -1, Z1 + 6], R.path);
  p.box([8, -1, Z1 + 5], [11, -1, Z1 + 8], R.path);
  const flowers = ['Plant_Flower_Common_Orange', 'Plant_Flower_Common_Yellow', 'Plant_Flower_Common_Blue', 'Plant_Flower_Common_White'];
  for (const x of [1, 2, 4, 5, 13, 14]) p.set(x, 1, Z1 + 1, flowers[x % 4]);
  // Porch: lanterns, the gull sign, barrels, crates and a bench.
  p.set(8, 5, Z1 + 2, 'Deco_Lantern_Ceiling'); p.set(11, 5, Z1 + 2, 'Deco_Lantern_Ceiling');
  p.set(13, 3, Z1 + 3, 'Furniture_Tavern_Sign', { facing: 'south' });
  p.set(13, 1, Z1 + 1, 'Furniture_Tavern_Barrel'); p.set(14, 1, Z1 + 1, 'Furniture_Tavern_Barrel');
  // Crates and a sack on the porch floor beside the door (the ground past the plinth is the world's, not ours).
  bp('Furniture_Village_Crate', 11.5, 1, Z1 + 1.5, { scale: 1, yaw: 12 }); bp('Furniture_Village_Crate', 12.4, 1, Z1 + 2.3, { scale: 0.8, yaw: -20 });
  bp('Furniture_Ancient_Sack', 11.5, 1, Z1 + 2.5, { scale: 0.8, yaw: 40 });
  ip('Deco_Starfish', 12.4, 1.8, Z1 + 2.3, { scale: 1.5, yaw: 35 });
  long('Furniture_Tavern_Bench', 7, 1, Z1 + 1, 1, 'south');
  // Window boxes under the south upper windows.
  // Window boxes stand on upside down stair brackets fixed to the jetty.
  for (const x of [1, 2, 4, 5, 13, 14]) {
    p.set(x, UP, JZ1 + 1, R.stairs, { facing: 'south', upsideDown: true });
    p.set(x, UP + 1, JZ1 + 1, 'Furniture_Tavern_Planter');
    p.set(x, UP + 2, JZ1 + 1, flowers[(x + 1) % 4]);
  }
  // A teal banner on the flat face of the tower between its windows, a striped awning over its east window.
  banner(TOWER[0], LOOKOUT, TOWER[1] + TR + 1, 'south');
  for (let z = TOWER[1] - 1; z <= TOWER[1] + 1; z++) p.set(TOWER[0] + TR + 1, 4, z, z === TOWER[1] ? 'Cloth_Roof_White' : 'Cloth_Modern_DarkGreen_Roof', { facing: 'east' });

  // Terrace east of the kitchen: paving, a low wall, a striped canvas awning off the kitchen wall, tables for drinkers.
  const T = TERRACE;
  p.box([T.x0, -1, T.z0], [T.x1, 0, T.z1], R.stoneFloor);
  p.carve([T.x0, 1, T.z0], [T.x1, 5, T.z1]);
  // A low parapet of full blocks; wall pieces need their joins set by hand.
  // The parapet runs round all three open sides (north, east, south), with the gate in the east side.
  for (const z of [T.z0, T.z1]) for (let x = T.x0; x <= T.x1; x++) p.set(x, 1, z, R.quoin);
  for (let z = T.z0; z <= T.z1; z++) if (z < 5 || z > 6) p.set(T.x1, 1, z, R.quoin);
  // The awning: shallow canvas (each piece two blocks long: facing east it fills its cell and the one west of it) from
  // the jetty slab at y 6 down to y 4, white and green stripes running down the slope, inside the parapet's north row.
  for (let i = 0; i < 3; i++) for (let z = T.z0 + 1; z <= T.z1; z++)
    p.set(T.x0 + 1 + 2 * i, 6 - i, z, z % 2 ? 'Cloth_Modern_DarkGreen_Roof_Shallow' : 'Cloth_Roof_White_Shallow', { facing: 'east' });
  // Beam posts under its low edge: at the north end, the middle and the south end (on the parapet there).
  const postX = T.x0 + 5;
  p.beam([postX, 1, T.z0 + 1], [postX, 3, T.z0 + 1], R.post);
  p.beam([postX, 1, 3], [postX, 3, 3], R.post);
  p.beam([postX, 2, T.z1], [postX, 3, T.z1], R.post);
  for (const z of [1, 5]) {
    p.set(21, 1, z, 'Furniture_Tavern_Table');
    p.set(21, 1, z - 1, 'Furniture_Tavern_Chair', { facing: 'south' }); p.set(22, 1, z + 2, 'Furniture_Tavern_Chair', { facing: 'north' });
    p.set(23, 1, z, 'Furniture_Tavern_Chair', { facing: 'west' });
    bp('Deco_Tankard', 21.4, 2, z + 0.4, { scale: 1.1, yaw: jitter(21, z, 7) }); bp('Deco_Tankard', 22.5, 2, z + 1.6, { scale: 1.1, yaw: jitter(22, z, 7) });
    bp('Deco_Lantern', 22, 2, z + 1, { scale: 0.7 });
  }
  ip('Food_Kebab_Fruit', 21.5, 2, 6.5, { yaw: 40 });
  // Flower planters standing on the parapet.
  for (const [x, z, f] of [[22, T.z0, 'Plant_Flower_Common_Orange'], [T.x1, 1, 'Plant_Flower_Common_Blue']]) { p.set(x, 2, z, 'Furniture_Tavern_Planter'); p.set(x, 3, z, f); }
  // Lamp posts at the terrace gate and the path.
  for (const [x, z] of [[T.x1, 4], [T.x1, 7], [6, Z1 + 6], [13, Z1 + 6]]) {
    p.set(x, 0, z, 'Rock_Calcite_Brick'); p.beam([x, 1, z], [x, 2, z], R.post); p.set(x, 3, z, 'Deco_Lantern');
  }
}

function gameplay() {
  const cd = (s, ev = 'ENTER') => ({ Type: 'CooldownCondition', Event: ev, Cooldown: s, Scope: 'PerPlayer' });
  // Welcome on the porch.
  p.trigger({ region: [[7, 1, Z1 + 1], [12, 3, Z1 + 3]], name: 'gull_welcome', tags: { Eternia_Hub: 'Tavern_Welcome' },
    effects: [{ Type: 'ShowEventTitle', Event: 'ENTER', PrimaryTitle: 'eterniamod_hub.tavern.title', SecondaryTitle: 'eterniamod_hub.tavern.subtitle', Style: 'Default', Duration: 3, Recipient: 'TriggeringPlayer' },
      { Type: 'PlaySound', Event: 'ENTER', SoundEvent: 'SFX_Door_Wooden_Open', Location: 'Player' }],
    conditions: [cd(600)] });
  // Music for the great hall: the campfire tune inside, the world's music back outside.
  p.trigger({ region: [[1, 1, 1], [17, 5, 11]], name: 'gull_hall_music', tags: { Eternia_Hub: 'Tavern_Hall' },
    effects: [{ Type: 'SetMusic', Event: 'ENTER', MusicContainer: 'Track_Z1AN_By_The_Campfire', ClearMusic: false },
      { Type: 'SetMusic', Event: 'EXIT', MusicContainer: '', ClearMusic: true }] });
  // Hearth: a short warm regen once a minute for whoever stands at the fire.
  p.trigger({ region: [[1, 1, 5], [3, 2, 7]], name: 'gull_hearth', tags: { Eternia_Hub: 'Tavern_Hearth' },
    effects: [{ Type: 'EntityEffect', Event: 'ENTER', Effect: 'HealthRegen_Buff_T1', Mode: 'Apply', Duration: 30 },
      { Type: 'SendMessage', Event: 'ENTER', Message: 'eterniamod_hub.tavern.hearth', Recipient: 'TriggeringPlayer' }],
    conditions: [cd(60)] });
  // Tower lookout.
  p.trigger({ region: [[TOWER[0] - 2, LOOKOUT + 1, TOWER[1] - 2], [TOWER[0] + 2, LOOKOUT + 3, TOWER[1] + 2]], name: 'gull_lookout', tags: { Eternia_Hub: 'Tavern_Lookout' },
    effects: [{ Type: 'ShowEventTitle', Event: 'ENTER', PrimaryTitle: 'eterniamod_hub.tavern.lookout.title', SecondaryTitle: 'eterniamod_hub.tavern.lookout.subtitle', Style: 'Default', Duration: 3, Recipient: 'TriggeringPlayer' }],
    conditions: [cd(300)] });
  // Ambient fire crackle at the hearth and the kitchen oven.
  p.ambient('SFX_Campfire_Default_Loop', [-0.5, 1.5, 6.5]);
  p.ambient('SFX_Campfire_Default_Loop', [17, 1.5, 0.5]);
}

skeleton();
if (!MASSING) {
  structure(); partitions(); hall(); kitchen(); snug(); upstairs(); attic(); lookout(); exterior(); gameplay();
  lint(p, {
    seeds: [['hall', [9, 2, 6]], ['kitchen', [14, 2, 2]], ['snug', [19, 2, 13]], ['corridor', [8, 8, 5]], ['room A', [3, 8, 10]],
      ['room B', [9, 8, 10]], ['room C', [15, 8, 10]], ['tower room', [19, 8, 13]], ['attic', [9, 13, 5]]],
    clear: [
      ['stair foot', [STAIR.x0 - 1, 1, STAIR.z0], [STAIR.x0 - 1, 2, STAIR.z1]],
      ['stair top landing', [STAIR.x0 + 6, UP + 1, STAIR.z0], [STAIR.x0 + 7, UP + 2, STAIR.z1 + 2]],
      ['corridor', [2, UP + 1, 5], [17, UP + 2, 6]],
      ['front door', [DOOR.x0, 1, DOOR.z - 2], [DOOR.x1, 2, DOOR.z + 1]],
      ['hall aisle', [9, 1, 4], [10, 2, 10]],
    ],
  });
}
p.save(MASSING ? here('../../docs/builds/Eternia_Tavern/concept/Eternia_Tavern_Massing.prefab.json') : prefabOut('Eternia_Tavern'));
