// prefab-builder.mjs: build Hytale .prefab.json files from code. No dependencies; Node 20 or newer.
//
// Copy this file into a mod (tools/prefabs/prefab-builder.mjs) and write one build script per prefab next to it. A build
// script is a sequence of passes (skeleton, structure, details) over one Prefab; run it with node, then look at the result
// with the prefab_render tool. See ../references/builder.md for the whole API with examples.
//
// Coordinates are blocks: x east, y up, z south (north is -Z). The anchor (default 0, 0, 0) is the paste point.
// Rules come from the server's prefab reader and from vanilla prefabs; ../references/research/ holds the evidence.

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname } from 'node:path';

export const BUILDER_VERSION = 2;
/** The block id version the current server writes; older prefabs get renamed blocks migrated on load. */
export const BLOCK_ID_VERSION = 11;

// ---- Rotation --------------------------------------------------------------------------------------------------------

/**
 * Yaw quarter turns that point a block's front at a compass direction. A model's front faces south (+Z) unturned: a stair or
 * roof slope runs downhill to the south, a door or chair faces south. Each quarter turn moves it south, east, north, west.
 */
export const FACING = { south: 0, east: 1, north: 2, west: 3 };
/** The rotation index the server stores: roll x 16 + pitch x 4 + yaw, each in quarter turns, applied roll, pitch, then yaw. */
export const rotationIndex = ({ yaw = 0, pitch = 0, roll = 0 } = {}) => (((roll % 4) + 4) % 4) * 16 + (((pitch % 4) + 4) % 4) * 4 + (((yaw % 4) + 4) % 4);
/**
 * Rotation for a block whose front should face a compass direction. upsideDown flips a stair or slope under a ceiling:
 * the half turn about x also turns its front round, so the yaw takes a half turn more (8 + (yaw + 2) % 4, the legal
 * UpDownNESW index with the back on the same side as an upright stair facing the same way).
 */
export function facing(direction, { upsideDown = false } = {}) {
  if (!(direction in FACING)) throw new Error('Unknown facing "' + direction + '": use north, south, east or west.');
  return upsideDown ? 8 + (FACING[direction] + 2) % 4 : FACING[direction];
}
/** Rotation that lays a log, beam or pillar along an axis (as vanilla builds do: 0 upright, 4 along z, 5 along x). */
export const AXIS = { y: 0, z: 4, x: 5 };
const OPPOSITE = { north: 'south', south: 'north', east: 'west', west: 'east' };
const STEP = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0] };
export const opposite = d => OPPOSITE[d];
/** Entity yaw (radians) that makes an entity look a compass direction: an entity looks north (-Z) at yaw 0, unlike blocks. */
export const ENTITY_YAW = { north: 0, west: Math.PI / 2, south: Math.PI, east: -Math.PI / 2 };
/**
 * Components for a chest that fills itself from a drop list when it is placed (a Server/Drops id), for set(..., { components }):
 *   p.set(4, 1, 6, 'Furniture_Village_Chest', { facing: 'south', components: lootChest('Drop_Goblin_Thief') })
 */
export const lootChest = (droplist, capacity = 18) => ({ Components: { ItemContainerBlock: { Droplist: droplist, ItemContainer: { Capacity: capacity, Items: {} }, Capacity: capacity } } });
export const step = d => STEP[d];

/** 3x3 integer matrix of a rotation index (R = Ry(yaw) Rx(pitch) Rz(roll)), row major. */
function matrix(index) {
  const c = [1, 0, -1, 0], s = [0, 1, 0, -1], y = index % 4, p = Math.floor(index / 4) % 4, r = Math.floor(index / 16) % 4;
  const cy = c[y], sy = s[y], cp = c[p], sp = s[p], cr = c[r], sr = s[r];
  return [cy * cr + sy * sp * sr, -cy * sr + sy * sp * cr, sy * cp, cp * sr, cp * cr, -sp, -sy * cr + cy * sp * sr, sy * sr + cy * sp * cr, cy * cp];
}
const INDEX_OF = new Map();
for (let i = 0; i < 64; i++) { const key = matrix(i).join(','); if (!INDEX_OF.has(key)) INDEX_OF.set(key, i); }
const mul = (a, b) => { const o = new Array(9).fill(0); for (let r = 0; r < 3; r++) for (let k = 0; k < 3; k++) for (let j = 0; j < 3; j++) o[r * 3 + j] += a[r * 3 + k] * b[k * 3 + j]; return o; };
/** The rotation index of turning a block by `turns` quarter turns about the vertical axis after its own rotation. */
export function turnRotation(index, turns) {
  const t = ((turns % 4) + 4) % 4;
  return t ? INDEX_OF.get(mul(matrix(t), matrix(index)).join(',')) : index;
}

// ---- Deterministic variety -----------------------------------------------------------------------------------------

/** Small seeded random number generator (mulberry32); the same seed builds the same prefab. */
export function random(seed = 1) {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
/** A number in 0..1 fixed for a position and salt, so a palette gives the same block at the same place on every run. */
export function noise(x, y, z, salt = 0) {
  let h = Math.imul(x | 0, 73856093) ^ Math.imul(y | 0, 19349663) ^ Math.imul(z | 0, 83492791) ^ Math.imul(salt | 0, 2654435761);
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b); h = Math.imul(h ^ (h >>> 16), 0x45d9f3b);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

/**
 * Weighted mix of blocks, picked per position: new Palette({ Rock_Stone_Brick: 6, Rock_Stone_Brick_Mossy: 2, Rock_Stone_Brick_Decorative: 1 }).
 * Anything that takes a block id also takes a Palette or a function (x, y, z) => id.
 */
export class Palette {
  constructor(weights, { salt = 0 } = {}) {
    this.entries = Object.entries(typeof weights === 'string' ? { [weights]: 1 } : weights).filter(([, w]) => w > 0);
    if (!this.entries.length) throw new Error('A palette needs at least one block with a weight above 0.');
    this.total = this.entries.reduce((s, [, w]) => s + w, 0); this.salt = salt;
  }
  pick(x, y, z) {
    let r = noise(x, y, z, this.salt) * this.total;
    for (const [id, w] of this.entries) { if (r < w) return id; r -= w; }
    return this.entries[this.entries.length - 1][0];
  }
  /** The same blocks with another salt: two surfaces using one palette then do not repeat the same pattern. */
  with(salt) { return new Palette(Object.fromEntries(this.entries), { salt }); }
}
const resolve = (block, x, y, z) => block == null ? null : typeof block === 'string' ? block : block instanceof Palette ? block.pick(x, y, z) : typeof block === 'function' ? block(x, y, z) : (() => { throw new Error('A block is an id, a Palette or a function (x, y, z) => id.'); })();

/**
 * A style file (style.json beside a style guide): roles mapped to blocks or weighted palettes, shared by every build in a set.
 * { "roles": { "wall": { "Rock_Stone_Brick": 6, "Rock_Stone_Brick_Mossy": 1 }, "trim": "Wood_Darkwood_Planks", ... }, "dimensions": {...} }
 * Returns { roles: { wall: Palette, ... }, ...rest of the file }.
 */
export function loadStyle(path) {
  const style = JSON.parse(readFileSync(path, 'utf8'));
  const roles = {};
  for (const [role, value] of Object.entries(style.roles ?? {})) roles[role] = typeof value === 'string' ? value : new Palette(value, { salt: [...role].reduce((h, c) => h * 31 + c.charCodeAt(0), 7) & 0xffff });
  return { ...style, roles };
}

// ---- Trigger volume vocabulary (TriggerVolumesPlugin registrations, research/triggers-and-interactions.md) ----------

export const TRIGGER_EVENTS = ['VOLUME_CREATE', 'ENTER', 'TICK', 'EXIT', 'TAG_ADDED', 'TAG_REMOVED', 'BLOCK_PLACED', 'BLOCK_BROKEN', 'BLOCK_USED', 'SIGNAL_RECEIVED', 'ENTITY_DIED'];
export const TRIGGER_EFFECTS = ['PlaySound', 'PlayVfx', 'CancelParticles', 'SendMessage', 'ShowEventTitle', 'SetMusic', 'SetWeather', 'Time', 'Teleport', 'SetVelocity',
  'EntityEffect', 'DamageEntity', 'GiveItem', 'SetGameMode', 'SetRespawnPoint', 'SetWorldSpawn', 'ClearRespawnPoints', 'SpawnNpc', 'TriggerNpcMarkers', 'RemoveEntities',
  'PlayAnimation', 'PlaceBlock', 'ReplaceBlockType', 'ControlDoors', 'PastePrefab', 'RunRootInteraction', 'EnableVolume', 'DisableVolume', 'DeleteVolume', 'ModifyTags',
  'SendSignal', 'ModifyRules'];
export const TRIGGER_CONDITIONS = ['TagCondition', 'ItemCondition', 'BlockTypeCondition', 'CooldownCondition', 'PlayerCountCondition', 'EntityCountCondition',
  'GameModeCondition', 'PermissionCondition', 'RandomChanceCondition', 'TimeOfDay'];
export const TRIGGER_RULES = ['NoBuild', 'NoDestroy', 'NoHarvest', 'NoUse', 'NoDoorOpen', 'NoDamage', 'DamageMultiplier', 'NoHeal', 'Fly', 'CreativePlacement', 'NoTick'];

// Enum-valued fields, spelt as the server's EnumCodec reads them: an all-caps Java constant (PER_PLAYER) is written in camel
// case (PerPlayer) and matched exactly; a legacy() codec also takes the constant's own name in any case. An effect, condition
// or rule with a value the server cannot read is skipped with only a log warning (TriggerVolumeCodecs.tolerantArray).
const legacy = values => Object.assign([...values], { legacy: true });
const constantName = key => key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
const RECIPIENT = ['TriggeringPlayer', 'NearestPlayer', 'PlayersInVolume', 'AllPlayers'];
const ORIGIN = ['VolumeOrigin', 'Entity', 'Event', 'WorldAbsolute'];
const CENTER = ['Volume', 'Entity', 'Event'];
const ROTATION = ['None', 'Ninety', 'OneEighty', 'TwoSeventy'];
const COMPARISON = ['AtLeast', 'AtMost', 'Exactly', 'NotEquals', 'MoreThan', 'LessThan'];
const AXIS_ROTATION = ['Any', 'Deg0', 'Deg90', 'Deg180', 'Deg270'];
const GAME_MODE = ['Adventure', 'Creative'];
const ENTITY_FILTER = ['All', 'Except', 'Only'];
const DENY_SIGNAL = { Target: ['Self', 'Other', 'Both'] };
const INTERACTION_TYPE = ['Primary', 'Secondary', 'Ability1', 'Ability2', 'Ability3', 'Ability4', 'Use', 'Pick', 'Pickup', 'CollisionEnter',
  'CollisionLeave', 'Collision', 'EntityStatEffect', 'SwapTo', 'SwapFrom', 'Death', 'Wielding', 'ProjectileSpawn', 'ProjectileHit', 'ProjectileMiss',
  'ProjectileBounce', 'Held', 'HeldOffhand', 'Equipped', 'Dodge', 'GameModeSwap', 'OnBreak', 'OnBreakImpact'];
/** Each built-in effect, condition and rule type's enum-valued fields and their values (effect/builtin/ in the server source). */
export const TRIGGER_ENUMS = {
  ShowEventTitle: { Style: ['Default', 'Major', 'GoblinBreach', 'VoidEviction'], Recipient: RECIPIENT },
  SendMessage: { Recipient: RECIPIENT },
  PlaySound: { Location: ['VolumeCenter', 'Entity', 'Event', 'Player'] },
  PlayVfx: { Anchor: ['Volume', 'Entity', 'Event'] },
  Time: { Mode: ['Pause', 'Resume', 'Set'] },
  SetVelocity: { RelativeMode: ['Absolute', 'VolumeOrigin', 'HorizontalFacing', 'FullLook'] },
  EntityEffect: { Mode: ['Apply', 'Remove'] },
  DamageEntity: { Mode: ['Flat', 'PercentMax', 'PercentCurrent'] },
  GiveItem: { OverflowBehavior: ['DropRemainder', 'IgnoreRemainder', 'RequireFullStack'] },
  SetGameMode: { GameMode: GAME_MODE },
  SetRespawnPoint: { Origin: ORIGIN },
  SetWorldSpawn: { Origin: ORIGIN },
  SpawnNpc: { Origin: ORIGIN },
  TriggerNpcMarkers: { Center: CENTER },
  RemoveEntities: { Mode: ['Kill', 'Remove'] },
  PlayAnimation: { Target: ['TriggeringEntity', 'NpcsInVolume'] },
  PlaceBlock: { Origin: ORIGIN, ReplaceMode: ['Always', 'OnlyAir'], Rotation: ROTATION, Pitch: ROTATION, Roll: ROTATION },
  ReplaceBlockType: { Bounds: ['Shape', 'Aabb'], Origin: ORIGIN, Rotation: ROTATION, Pitch: ROTATION, Roll: ROTATION },
  ControlDoors: { Action: ['Open', 'Close'] },
  PastePrefab: { Origin: ORIGIN, Rotation: ROTATION },
  RunRootInteraction: { InteractionType: INTERACTION_TYPE },
  EnableVolume: { Center: CENTER },
  DisableVolume: { Center: CENTER },
  DeleteVolume: { Center: CENTER },
  ModifyTags: { Operation: ['Set', 'Remove', 'Increment', 'Toggle', 'Replace', 'Append'], Center: CENTER },
  SendSignal: { Center: CENTER },
  ModifyRules: { Operation: ['Set', 'Append', 'SetField', 'Remove', 'SetRulesActive'], Center: CENTER },
  TagCondition: { Source: ['Event', 'Self', 'Group', 'Radius'], Comparison: COMPARISON, CompareAgainst: ['Value', 'Tag'], Center: CENTER },
  ItemCondition: { Location: ['InHand', 'Hotbar', 'Inventory', 'Carried'], Comparison: COMPARISON },
  BlockTypeCondition: { PositionSource: ['Event', 'VolumeOrigin', 'Entity', 'WorldAbsolute'], RotationX: AXIS_ROTATION, RotationY: AXIS_ROTATION, RotationZ: AXIS_ROTATION },
  CooldownCondition: { Scope: legacy(['PerPlayer', 'WholeVolume']) },
  PlayerCountCondition: { Comparison: COMPARISON },
  EntityCountCondition: { Comparison: COMPARISON },
  GameModeCondition: { GameMode: GAME_MODE },
  NoBuild: DENY_SIGNAL, NoDestroy: DENY_SIGNAL, NoHarvest: DENY_SIGNAL, NoUse: DENY_SIGNAL, NoDoorOpen: DENY_SIGNAL,
  NoDamage: { EntityFilter: ENTITY_FILTER },
  DamageMultiplier: { Direction: ['Received', 'Dealt', 'Both'], EntityFilter: ENTITY_FILTER },
};
/** The volume's and the group's own enum fields (component/TriggerVolume.java, TriggerVolumeGroup.java); TargetTypes is a list of them. */
export const TRIGGER_VOLUME_ENUMS = {
  TargetTypes: ['Player', 'Npc', 'ItemDrop', 'Projectile'],
  ConditionTiming: ['BeforeVolumeDelay', 'AfterVolumeDelay'],
  RejectionDelayMode: legacy(['Immediate', 'UseVolumeDelay']),
  ProjectileSource: ['Shooter', 'Projectile'],
};
function checkEnum(value, values, field, label, outcome) {
  if (values.includes(value) || (values.legacy && typeof value === 'string' && values.some(v => constantName(v) === value.toUpperCase()))) return;
  const near = typeof value === 'string' && values.find(v => v.toLowerCase() === value.replace(/_/g, '').toLowerCase());
  throw new Error('Unknown ' + field + ' ' + JSON.stringify(value) + ' on ' + label + ': it is one of ' + values.join(', ')
    + (values.legacy ? ' (or ' + values.map(constantName).join(', ') + ' in any case).' : ', in exactly that case.')
    + (near ? ' Did you mean "' + near + '"?' : '') + ' The server cannot read any other value' + (outcome ? ' and ' + outcome : '') + '.');
}
function checkFields(item, fields, label, outcome) {
  for (const [field, values] of Object.entries(fields ?? {})) if (item[field] != null) checkEnum(item[field], values, field, label, outcome);
}
function checkRule(rule, extraTypes, what = 'rule', outcome = 'skips the whole rule, with only a log warning') {
  if (!rule || typeof rule.Type !== 'string') throw new Error('Every trigger ' + what + ' needs a Type.');
  if (!TRIGGER_RULES.includes(rule.Type) && !extraTypes.includes(rule.Type)) throw new Error('Unknown trigger ' + what + ' "' + rule.Type + '". Built in: ' + TRIGGER_RULES.join(', ') + '.');
  if (TRIGGER_RULES.includes(rule.Type)) checkFields(rule, TRIGGER_ENUMS[rule.Type], 'trigger ' + what + ' ' + rule.Type, outcome);
}
function checkTrigger(list, known, what, extraTypes) {
  for (const item of list) {
    if (!item || typeof item.Type !== 'string') throw new Error('Every trigger ' + what + ' needs a Type.');
    if (!known.includes(item.Type) && !extraTypes.includes(item.Type)) throw new Error('Unknown trigger ' + what + ' "' + item.Type + '". Built in: ' + known.join(', ') + '. A mod\'s own type goes in extraTypes.');
    if (item.Event === undefined) throw new Error('Trigger ' + what + ' ' + item.Type + ' needs an Event (' + TRIGGER_EVENTS.join(', ') + ').');
    if (!TRIGGER_EVENTS.includes(item.Event)) throw new Error('Unknown trigger event "' + item.Event + '" on ' + item.Type + ': events are ' + TRIGGER_EVENTS.join(', ') + ' (a misspelt event never fires).');
    if (!known.includes(item.Type)) continue;
    const outcome = 'skips the whole ' + what + ', with only a log warning';
    checkFields(item, TRIGGER_ENUMS[item.Type], 'trigger ' + what + ' ' + item.Type, outcome);
    if (item.Type === 'ModifyRules' && item.Rule != null) checkRule(item.Rule, extraTypes, 'rule in ModifyRules', 'skips the whole ModifyRules ' + what + ', with only a log warning');
  }
}
/** A volume or group document as it will be written (after extra): its effects, conditions, rules and its own enum fields. */
function checkVolume(doc, extraTypes, label) {
  checkTrigger(doc.Effects ?? [], TRIGGER_EFFECTS, 'effect', extraTypes);
  checkTrigger(doc.RejectionEffects ?? [], TRIGGER_EFFECTS, 'rejection effect', extraTypes);
  checkTrigger(doc.Conditions ?? [], TRIGGER_CONDITIONS, 'condition', extraTypes);
  for (const rule of doc.Rules ?? []) checkRule(rule, extraTypes);
  const { TargetTypes, ...single } = TRIGGER_VOLUME_ENUMS;
  if (doc.TargetTypes != null && !Array.isArray(doc.TargetTypes)) throw new Error('TargetTypes on ' + label + ' is a list, such as [\'Player\'].');
  for (const target of doc.TargetTypes ?? []) checkEnum(target, TargetTypes, 'TargetTypes entry', label);
  checkFields(doc, single, label);
  // The volume upper-cases CooldownMode and looks the constant up, so any case works but the underscore matters.
  if (doc.CooldownMode != null && !(typeof doc.CooldownMode === 'string' && ['TOTAL', 'PER_ENTITY'].includes(doc.CooldownMode.toUpperCase())))
    throw new Error('cooldownMode is TOTAL or PER_ENTITY (any case), not ' + JSON.stringify(doc.CooldownMode) + ' on ' + label + ': the volume upper-cases the value and silently ignores one it cannot read.');
}

// ---- The prefab ----------------------------------------------------------------------------------------------------

const key = (x, y, z) => x + ',' + y + ',' + z;
const order = (a, b) => [Math.min(a, b), Math.max(a, b)];

export class Prefab {
  /** @param {{ anchor?: [number, number, number], name?: string }} options */
  constructor({ anchor = [0, 0, 0], name = 'prefab' } = {}) {
    this.anchor = anchor; this.name = name;
    this.blocks = new Map(); this.fluids = new Map(); this.entities = [];
    this.log = [];
  }

  /** Set one cell. block: id, Palette, function, or null to remove. options: rotation, facing, upsideDown, axis, components, keep (do not overwrite). */
  set(x, y, z, block, options = {}) {
    [x, y, z] = [x, y, z].map(v => Math.round(v));
    const k = key(x, y, z);
    if (options.keep && this.blocks.has(k)) return this;
    const id = resolve(block, x, y, z);
    if (id == null) { this.blocks.delete(k); return this; }
    let rotation = options.rotation ?? 0;
    if (options.facing) rotation = facing(options.facing, { upsideDown: options.upsideDown });
    if (options.axis) rotation = AXIS[options.axis];
    if (!Number.isInteger(rotation) || rotation < 0 || rotation > 63) throw new Error('Rotation must be an index 0..63 (see rotationIndex).');
    const entry = { x, y, z, name: id };
    if (rotation) entry.rotation = rotation;
    if (options.components) entry.components = options.components;
    this.blocks.set(k, entry);
    return this;
  }
  get(x, y, z) { return this.blocks.get(key(x, y, z))?.name; }
  has(x, y, z) { const b = this.blocks.get(key(x, y, z)); return !!b && b.name !== 'Empty'; }
  remove(x, y, z) { this.blocks.delete(key(x, y, z)); return this; }

  /** Run fn(x, y, z) for every cell of an inclusive box. */
  each([x0, y0, z0], [x1, y1, z1], fn) {
    [x0, x1] = order(x0, x1); [y0, y1] = order(y0, y1); [z0, z1] = order(z0, z1);
    for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) fn(x, y, z);
    return this;
  }
  /** Fill an inclusive box. */
  box(a, b, block, options) { return this.each(a, b, (x, y, z) => this.set(x, y, z, block, options)); }
  /** Force air: cells written as "Empty" clear whatever was in the world when pasted. Cells left unset keep the world. */
  carve(a, b) { return this.box(a, b, 'Empty'); }
  /** Forget cells (neither block nor forced air). */
  clear(a, b) { return this.each(a, b, (x, y, z) => this.remove(x, y, z)); }
  /** The four vertical walls of a box, one block thick. */
  walls([x0, y0, z0], [x1, y1, z1], block, options) {
    [x0, x1] = order(x0, x1); [z0, z1] = order(z0, z1);
    return this.each([x0, y0, z0], [x1, y1, z1], (x, y, z) => { if (x === x0 || x === x1 || z === z0 || z === z1) this.set(x, y, z, block, options); });
  }
  /** A horizontal layer at y over a rectangle. */
  floor(y, [x0, z0], [x1, z1], block, options) { return this.box([x0, y, z0], [x1, y, z1], block, options); }
  /** Hollow room: walls, a floor below and a ceiling above the given inside box, and air carved inside. */
  room([x0, y0, z0], [x1, y1, z1], { walls, floor, ceiling, carve = true } = {}) {
    [x0, x1] = order(x0, x1); [y0, y1] = order(y0, y1); [z0, z1] = order(z0, z1);
    if (carve) this.carve([x0, y0, z0], [x1, y1, z1]);
    if (walls) this.walls([x0 - 1, y0, z0 - 1], [x1 + 1, y1, z1 + 1], walls);
    if (floor) this.floor(y0 - 1, [x0 - 1, z0 - 1], [x1 + 1, z1 + 1], floor);
    if (ceiling) this.floor(y1 + 1, [x0 - 1, z0 - 1], [x1 + 1, z1 + 1], ceiling);
    return this;
  }
  /** A straight run of cells from a to b (3D, inclusive). */
  line([x0, y0, z0], [x1, y1, z1], block, options) {
    const n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0), Math.abs(z1 - z0));
    for (let i = 0; i <= n; i++) { const t = n ? i / n : 0; this.set(Math.round(x0 + (x1 - x0) * t), Math.round(y0 + (y1 - y0) * t), Math.round(z0 + (z1 - z0) * t), block, options); }
    return this;
  }
  /** A log or beam from a to b along one axis, turned to lie along it. */
  beam(a, b, block) {
    const axis = a[0] !== b[0] ? 'x' : a[2] !== b[2] ? 'z' : 'y';
    if ([a[0] !== b[0], a[1] !== b[1], a[2] !== b[2]].filter(Boolean).length > 1) throw new Error('A beam runs along one axis.');
    return this.line(a, b, block, { axis });
  }
  /** Solid or hollow ellipsoid; radii in blocks. */
  ellipsoid([cx, cy, cz], [rx, ry, rz], block, { hollow = false, options } = {}) {
    return this.each([cx - rx, cy - ry, cz - rz], [cx + rx, cy + ry, cz + rz], (x, y, z) => {
      const d = ((x - cx) / (rx + 0.5)) ** 2 + ((y - cy) / (ry + 0.5)) ** 2 + ((z - cz) / (rz + 0.5)) ** 2;
      if (d <= 1 && (!hollow || ((x - cx) / (rx - 0.5)) ** 2 + ((y - cy) / (ry - 0.5)) ** 2 + ((z - cz) / (rz - 0.5)) ** 2 > 1)) this.set(x, y, z, block, options);
    });
  }
  /** Vertical cylinder (tower, well, round room) from y0 up `height` cells. */
  cylinder([cx, y0, cz], radius, height, block, { hollow = false, options } = {}) {
    return this.each([cx - radius, y0, cz - radius], [cx + radius, y0 + height - 1, cz + radius], (x, y, z) => {
      const d = Math.hypot(x - cx, z - cz);
      if (d <= radius + 0.5 && (!hollow || d > radius - 0.5)) this.set(x, y, z, block, options);
    });
  }
  /** Replace blocks in a box that match (id, RegExp or function(id)) with another block. */
  replace(a, b, match, block, options) {
    const test = typeof match === 'string' ? id => id === match : match instanceof RegExp ? id => match.test(id) : match;
    return this.each(a, b, (x, y, z) => { const id = this.get(x, y, z); if (id && test(id)) this.set(x, y, z, block, options); });
  }
  /**
   * Recolour the blocks already in a box along a gradient, as the game's gradient tool does (BuilderToolsPlugin
   * applyGradient): materials are { block, weight } with weights as percentages of the way from `from` to `to`, and
   * blocks near a boundary between two materials are dithered into the neighbour over `blend` (a fraction of the
   * way; 0.5 chance at the boundary, falling to 0 at the band's edge). Shapes: 'linear' along from -> to, 'radial'
   * from `from` out to the distance of `to`. Only cells that hold a block (not air, not Empty) change, unless fill is
   * true. `only` limits it to ids that match (id, RegExp or function). A material block can be a Palette.
   *   p.gradient([0, 0, 0], [20, 6, 0], [{ block: 'Rock_Stone_Cobble_Mossy', weight: 30 }, { block: 'Rock_Stone_Cobble', weight: 40 },
   *     { block: 'Rock_Stone_Brick', weight: 30 }], { from: [0, 0, 0], to: [0, 6, 0], blend: 0.15 })
   */
  gradient(a, b, materials, { from, to, axis, shape = 'linear', blend = 0.15, invert = false, fill = false, only, salt = 17 } = {}) {
    if (materials.length < 2) throw new Error('A gradient needs two materials or more.');
    const total = materials.reduce((s, m) => s + m.weight, 0);
    const parts = materials.map(m => ({ block: m.block, width: m.weight / total }));
    if (axis && !from) {
      // Along one axis of the box, low to high.
      const lo = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])], hi = [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])];
      const i = { x: 0, y: 1, z: 2 }[axis];
      from = [...lo]; to = [...lo]; from[i] = lo[i]; to[i] = hi[i];
    }
    if (!from || !to) throw new Error('A gradient needs from and to points, or an axis.');
    const d = [to[0] - from[0], to[1] - from[1], to[2] - from[2]], length = Math.hypot(...d) || 1;
    const test = only === undefined ? () => true : typeof only === 'string' ? id => id === only : only instanceof RegExp ? id => only.test(id) : only;
    const pick = (t, x, y, z) => {
      let upper = 0, seg = parts.length - 1;
      for (let i = 0; i < parts.length; i++) { upper += parts[i].width; if (t <= upper) { seg = i; break; } }
      const lower = upper - parts[seg].width, r = noise(x, y, z, salt);
      if (blend > 0 && seg < parts.length - 1 && upper - t < blend) {
        const band = Math.min(blend, upper - lower, parts[seg + 1].width);
        if (upper - t < band && r < 0.5 * (1 - (upper - t) / band)) return parts[seg + 1].block;
      } else if (blend > 0 && seg > 0 && t - lower < blend) {
        const band = Math.min(blend, upper - lower, parts[seg - 1].width);
        if (t - lower < band && r < 0.5 * (1 - (t - lower) / band)) return parts[seg - 1].block;
      }
      return parts[seg].block;
    };
    return this.each(a, b, (x, y, z) => {
      const id = this.get(x, y, z);
      if (!fill && (!id || id === 'Empty')) return;
      if (id && !test(id)) return;
      const v = [x - from[0], y - from[1], z - from[2]];
      let t = shape === 'radial' ? Math.hypot(...v) / length : (v[0] * d[0] + v[1] * d[1] + v[2] * d[2]) / (length * length);
      t = Math.max(0, Math.min(1, t));
      if (invert) t = 1 - t;
      const old = this.blocks.get(key(x, y, z));
      this.set(x, y, z, pick(t, x, y, z), { rotation: old?.rotation ?? 0 });
    });
  }
  /**
   * Swap a share of one block for another in clusters, not single specks: moss on a wall base, cracks around damage,
   * soot over a hearth. chance is the share replaced (0..1); size is the cluster size in blocks (1 = scattered cells);
   * bias shifts the share with height: 'low' more near the bottom of the box, 'high' near the top.
   *   p.scatter([0, 1, 0], [12, 4, 0], 'Rock_Stone_Cobble', 'Rock_Stone_Cobble_Mossy', { chance: 0.3, size: 2, bias: 'low' })
   */
  scatter(a, b, match, block, { chance = 0.25, size = 2, bias, salt = 29 } = {}) {
    const test = typeof match === 'string' ? id => id === match : match instanceof RegExp ? id => match.test(id) : match;
    const y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]), s = Math.max(1, size), cells = [];
    this.each(a, b, (x, y, z) => {
      const id = this.get(x, y, z);
      if (!id || id === 'Empty' || !test(id)) return;
      // Smooth value noise at the cluster scale, plus a little per-cell noise so cluster edges are ragged.
      const fx = x / s, fy = y / s, fz = z / s, ix = Math.floor(fx), iy = Math.floor(fy), iz = Math.floor(fz);
      const sm = t => t * t * (3 - 2 * t), tx = sm(fx - ix), ty = sm(fy - iy), tz = sm(fz - iz);
      const n = (dx, dy, dz) => noise(ix + dx, iy + dy, iz + dz, salt);
      const lerp = (p, q, t) => p + (q - p) * t;
      const v = lerp(lerp(lerp(n(0, 0, 0), n(1, 0, 0), tx), lerp(n(0, 1, 0), n(1, 1, 0), tx), ty), lerp(lerp(n(0, 0, 1), n(1, 0, 1), tx), lerp(n(0, 1, 1), n(1, 1, 1), tx), ty), tz);
      const h = y1 > y0 ? (y - y0) / (y1 - y0) : 0.5, weight = bias === 'low' ? 1.6 - 1.2 * h : bias === 'high' ? 0.4 + 1.2 * h : 1;
      const value = s > 1 ? v * 0.8 + noise(x, y, z, salt + 1) * 0.2 : noise(x, y, z, salt);
      cells.push([value / weight, x, y, z]);
    });
    // The lowest-valued share of the matching cells changes, so chance is the share replaced and clusters stay together.
    cells.sort((p, q) => p[0] - q[0]);
    for (const [, x, y, z] of cells.slice(0, Math.round(chance * cells.length))) { const old = this.blocks.get(key(x, y, z)); this.set(x, y, z, block, { rotation: old?.rotation ?? 0 }); }
    return this;
  }
  /** Change cells for which fn(x, y, z, id) returns a block (or null to remove); return undefined to leave a cell. */
  edit(a, b, fn) { return this.each(a, b, (x, y, z) => { const out = fn(x, y, z, this.get(x, y, z)); if (out !== undefined) this.set(x, y, z, out); }); }

  /**
   * A run of stairs climbing from `from` towards `climb` (north, south, east, west) for `steps` steps, `width` wide.
   * Stairs face downhill, so a stair climbing north faces south.
   */
  stairs(from, climb, steps, block, { width = 1, fill } = {}) {
    const [dx, , dz] = STEP[climb], [sx, , sz] = [dz !== 0 ? 1 : 0, 0, dx !== 0 ? 1 : 0];
    for (let i = 0; i < steps; i++) for (let w = 0; w < width; w++) {
      const x = from[0] + dx * i + sx * w, y = from[1] + i, z = from[2] + dz * i + sz * w;
      this.set(x, y, z, block, { facing: OPPOSITE[climb] });
      if (fill) for (let yy = from[1]; yy < y; yy++) this.set(x, yy, z, fill);
    }
    return this;
  }

  /**
   * Gable roof over the inclusive rectangle [x0, z0]..[x1, z1], eaves at height y, ridge along `ridge` ('x' or 'z').
   * slope: a roof block family such as 'Wood_Softwood_Roof' (its slope runs downhill to the south unturned); ridgeBlock caps an
   * odd width (default slope + '_Flat'); gable fills the triangular end walls; overhang extends the eaves outwards and down.
   */
  gableRoof([x0, z0], [x1, z1], y, { slope, ridge = 'x', ridgeBlock, gable, overhang = 1 } = {}) {
    if (!slope) throw new Error('gableRoof needs slope: a roof block id.');
    [x0, x1] = order(x0, x1); [z0, z1] = order(z0, z1);
    const along = ridge === 'x', lo = along ? z0 : x0, hi = along ? z1 : x1, a0 = (along ? x0 : z0) - overhang, a1 = (along ? x1 : z1) + overhang;
    const put = (u, w, yy, block, options) => along ? this.set(u, yy, w, block, options) : this.set(w, yy, u, block, options);
    const down = along ? ['north', 'south'] : ['west', 'east'];
    for (let i = -overhang; ; i++) {
      const near = lo + i, far = hi - i, yy = y + i;
      if (near > far) break;
      for (let u = a0; u <= a1; u++) {
        if (near === far) put(u, near, yy, ridgeBlock ?? slope + '_Flat');
        else { put(u, near, yy, slope, { facing: down[0] }); put(u, far, yy, slope, { facing: down[1] }); }
      }
      // Gable ends: fill under the slope between the two rows.
      if (gable && i >= 0) for (const u of [along ? x0 : z0, along ? x1 : z1]) for (let w = near + 1; w < far; w++) put(u, w, yy, gable);
    }
    return this;
  }
  /** Shed (lean-to) roof sloping down towards `down` over a rectangle, starting at height y on the high side. */
  shedRoof([x0, z0], [x1, z1], y, { slope, down = 'south', overhang = 1 } = {}) {
    [x0, x1] = order(x0, x1); [z0, z1] = order(z0, z1);
    const [dx, , dz] = STEP[down], along = dx === 0;
    const rows = along ? z1 - z0 + 1 : x1 - x0 + 1;
    for (let i = 0; i < rows + overhang; i++) {
      const yy = y - i;
      if (along) { const z = dz > 0 ? z0 + i : z1 - i; for (let x = x0 - overhang; x <= x1 + overhang; x++) this.set(x, yy, z, slope, { facing: down }); }
      else { const x = dx > 0 ? x0 + i : x1 - i; for (let z = z0 - overhang; z <= z1 + overhang; z++) this.set(x, yy, z, slope, { facing: down }); }
    }
    return this;
  }

  /** A fluid in a cell: sources ("Water_Source", "Lava_Source") are level 1; flowing "Water" goes 1..8. */
  fluid(x, y, z, name, level = 1) { this.fluids.set(key(x, y, z), { x, y, z, name, level }); return this; }
  /** Fill a box with a fluid. */
  pool(a, b, name, level = 1) { return this.each(a, b, (x, y, z) => this.fluid(x, y, z, name, level)); }

  /** Add a raw entity (a full component document); see references/prefab-format.md for props, spawns and triggers. */
  entity(components) { this.entities.push({ Components: components }); return this; }

  /**
   * A trigger volume (references/triggers.md). On paste the server copies it into the world's trigger data; the entity is
   * never spawned, so it needs only Transform and TriggerVolume. Give the place either as cells (region: [a, b], inclusive,
   * the usual case) or as an exact position plus a shape (box: [w, h, d] centred, sphere: radius, cylinder: { radius,
   * height } standing on the position, or a raw shape). Effects, conditions and rules are checked against the server's
   * built-in types (pass extraTypes for a mod's own) and every Event against the server's event names, because a
   * misspelt event or type does nothing and says nothing in game.
   *   p.trigger({ region: [[4, 1, 0], [6, 3, 1]], name: 'ruin_entrance', tags: { MyMod_Ruin: 'Entrance' },
   *     effects: [{ Type: 'ShowEventTitle', Event: 'ENTER', PrimaryTitle: 'server.mymod.ruin.title' }],
   *     conditions: [{ Type: 'CooldownCondition', Event: 'ENTER', Cooldown: 600, Scope: 'PerPlayer' }] })
   */
  trigger({ region, position, box, sphere, cylinder, shape, name, tags, effects = [], conditions = [], rules = [], rejectionEffects = [], targets = ['Player'],
    enabled = true, keepLoaded = false, cooldown, cooldownMode, activationDelay, group, color, extraTypes = [], extra = {} } = {}) {
    let origin, geometry;
    if (region) {
      const [[x0, y0, z0], [x1, y1, z1]] = region.map(c => c.map(Math.round)), lo = [Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)], hi = [Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)];
      const half = [0, 1, 2].map(i => (hi[i] - lo[i] + 1) / 2);
      origin = [0, 1, 2].map(i => lo[i] + half[i]);
      geometry = { Type: 'Box', Min: { X: -half[0], Y: -half[1], Z: -half[2] }, Max: { X: half[0], Y: half[1], Z: half[2] } };
    } else {
      if (!position) throw new Error('A trigger needs region (cells) or position plus a shape.');
      origin = position;
      if (shape) geometry = shape;
      else if (box) geometry = { Type: 'Box', Min: { X: -box[0] / 2, Y: -box[1] / 2, Z: -box[2] / 2 }, Max: { X: box[0] / 2, Y: box[1] / 2, Z: box[2] / 2 } };
      else if (sphere) geometry = { Type: 'Sphere', Center: { X: 0, Y: 0, Z: 0 }, Radius: sphere };
      else if (cylinder) geometry = { Type: 'Cylinder', Center: { X: 0, Y: 0, Z: 0 }, Radius: cylinder.radius, Height: cylinder.height };
      else throw new Error('A trigger with a position needs box, sphere, cylinder or shape: without a shape it contains nothing.');
    }
    const index = this.entities.filter(e => e.Components?.TriggerVolume).length;
    const volume = { Shape: geometry, Effects: effects, Conditions: conditions, Enabled: enabled, TargetTypes: targets, KeepLoaded: keepLoaded, PrefabIndex: index, Name: name ?? 'trigger_' + index };
    if (rules.length) volume.Rules = rules;
    if (rejectionEffects.length) volume.RejectionEffects = rejectionEffects;
    if (tags) volume.Tags = tags;
    if (cooldown !== undefined) volume.Cooldown = cooldown;
    if (cooldownMode) volume.CooldownMode = cooldownMode;
    if (activationDelay !== undefined) volume.ActivationDelay = activationDelay;
    if (group) volume.GroupLinkId = group;
    if (color) volume.Color = { X: color[0], Y: color[1], Z: color[2] };
    Object.assign(volume, extra);
    checkVolume(volume, extraTypes, 'trigger volume "' + volume.Name + '"');
    return this.entity({ Transform: { Position: { X: origin[0], Y: origin[1], Z: origin[2] }, Rotation: { Pitch: 0, Yaw: 0, Roll: 0 } }, TriggerVolume: volume });
  }
  /**
   * A trigger volume group: several volumes (trigger({ group: id })) that act as one, with shared effects and conditions.
   * position is the group's origin; color is an RGB integer.
   */
  triggerGroup({ id, position, effects = [], conditions = [], rules = [], color, extraTypes = [], extra = {} }) {
    if (!id) throw new Error('A trigger group needs an id, shared by its member volumes.');
    const groupDoc = { GroupLinkId: id, Effects: effects, Conditions: conditions, ...(rules.length ? { Rules: rules } : {}), ...(color !== undefined ? { Color: color } : {}), ...extra };
    checkVolume(groupDoc, extraTypes, 'trigger group "' + id + '"');
    return this.entity({ Transform: { Position: { X: position[0], Y: position[1], Z: position[2] }, Rotation: { Pitch: 0, Yaw: 0, Roll: 0 } }, TriggerVolumeGroup: groupDoc });
  }
  /** A UUID component, fixed by the prefab's own counter so a rebuild writes the same file. Paste gives every entity a fresh one. */
  uuid() {
    this.uuids = (this.uuids ?? 0) + 1;
    const bytes = Buffer.alloc(16);
    for (let i = 0; i < 4; i++) bytes.writeUInt32BE(Math.floor(noise(this.uuids, i, this.name.length, 977) * 4294967296) >>> 0, i * 4);
    bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
    return { UUID: { $binary: bytes.toString('base64'), $type: '04' } };
  }
  /**
   * A prop: an item shown in the world, placed off the block grid, turned and scaled freely (references/prefab-format.md).
   * position is the visual centre for a block prop (block: true, the default: the item is a block, drawn as that block) and
   * the model's origin for an item prop (block: false: a sword on a rack, drawn as the item's own model). facing is the
   * compass direction the prop's front looks; yaw, pitch and roll (degrees) turn it further. scale 1 is natural size.
   */
  prop(id, [x, y, z], { facing: look = 'south', yaw = 0, pitch = 0, roll = 0, scale = 1, block = true } = {}) {
    const rotation = { Pitch: pitch * Math.PI / 180, Yaw: ENTITY_YAW[look] + yaw * Math.PI / 180, Roll: roll * Math.PI / 180 };
    const components = { Transform: { Position: { X: x, Y: y, Z: z }, Rotation: rotation } };
    if (block) components.BlockEntity = { Version: 1, BlockTypeKey: id };
    Object.assign(components, { EntityScale: { Scale: scale }, Item: { Item: { Id: id, Quantity: 1, OverrideDroppedItemAnimation: true } },
      Prop: {}, PreventPickup: {}, PreventItemMerging: {}, Invulnerable: {}, PrefabCopyable: {}, UUID: this.uuid() });
    // A block prop must not carry HeadRotation (it overwrites the rotation on load); an item prop carries the same rotation there.
    if (!block) components.HeadRotation = { Rotation: { ...rotation } };
    return this.entity(components);
  }
  /** A static decorative model (a Server/Models asset) standing at a point, facing a compass direction. */
  model(modelId, [x, y, z], { facing: look = 'south', yaw = 0, scale = 1 } = {}) {
    const rotation = { Pitch: 0, Yaw: ENTITY_YAW[look] + yaw * Math.PI / 180, Roll: 0 };
    return this.entity({ Transform: { Position: { X: x, Y: y, Z: z }, Rotation: rotation }, Model: { Model: { Id: modelId, Scale: scale, Static: true } },
      HeadRotation: { Rotation: { ...rotation } }, Prop: {}, PrefabCopyable: {}, UUID: this.uuid() });
  }
  /**
   * An NPC spawn marker: markerId names a Server/NPC/Spawn/Markers asset (which lists the roles and weights), not a role. The
   * marker is hidden from adventure players. Place it standing on the floor: [x + 0.5, floor + 1, z + 0.5].
   */
  spawnMarker(markerId, [x, y, z], { facing: look = 'south', label } = {}) {
    return this.entity({ SpawnMarkerComponent: { SpawnMarker: markerId, RespawnTime: 0, SpawnCount: 0, NPCReferences: [] },
      Transform: { Position: { X: x, Y: y, Z: z }, Rotation: { Pitch: 0, Yaw: ENTITY_YAW[look], Roll: 0 } },
      Model: { Model: { Id: 'NPC_Spawn_Marker', Scale: 1, Static: false } }, Nameplate: { Text: label ?? markerId },
      HiddenFromAdventurePlayer: {}, Intangible: {}, PrefabCopyable: {}, UUID: this.uuid() });
  }
  /** A patrol path: waypoints [{ at: [x, y, z], pause: seconds }] in order. The NPC's role needs a CurrentPrefabPath path sensor. */
  patrol(name, points) {
    points.forEach((p, order) => this.entity({ PatrolPathMarker: { Version: 5, PathName: name, PathLength: points.length, Order: order, PauseTime: p.pause ?? 0, ObsvAngle: 0 },
      Transform: { Position: { X: p.at[0], Y: p.at[1], Z: p.at[2] }, Rotation: { Pitch: 0, Yaw: 0, Roll: 0 } }, HeadRotation: { Rotation: { Pitch: 0, Yaw: 0, Roll: 0 } },
      Model: { Model: { Id: 'NPC_Path_Marker', Scale: 1, Static: false } }, HiddenFromAdventurePlayer: {}, PrefabCopyable: {}, UUID: this.uuid() }));
    return this;
  }
  /** An encounter: an EncounterManager running a Server/EncounterManager asset (scripted waves), shown to builders as a marker. */
  encounter(encounterId, [x, y, z]) {
    return this.entity({ Transform: { Position: { X: x, Y: y, Z: z }, Rotation: { Pitch: 0, Yaw: 0, Roll: 0 } }, EncounterManager: { EncounterId: encounterId },
      Intangible: {}, PrefabCopyable: {}, HiddenFromAdventurePlayer: {}, Nameplate: { Text: encounterId }, Model: { Model: { Id: 'Encounter_Marker', Scale: 1, Static: false } } });
  }
  /**
   * A looping ambient sound at a point (references/audio.md): an AmbientEmitter with the markers that make it visible to
   * builders in creative mode and hidden from adventure players. The SoundEvent must loop and its files be mono.
   */
  ambient(soundEventId, [x, y, z]) {
    return this.entity({ Transform: { Position: { X: x, Y: y, Z: z }, Rotation: { Pitch: 0, Yaw: 0, Roll: 0 } }, AmbientEmitter: { SoundEventId: soundEventId },
      Intangible: {}, PrefabCopyable: {}, HiddenFromAdventurePlayer: {}, Nameplate: { Text: soundEventId }, Model: { Model: { Id: 'NPC_Spawn_Marker', Scale: 1, Static: false } } });
  }

  /**
   * A child prefab (references/world-placement.md): a Prefab_Spawner_Block at the cell, which pastes another prefab there
   * turned like a block facing `facing`. `path` is a folder under Server/Prefabs (split by / or dots); one prefab from it
   * is pasted. Server/HytaleGenerator turns the dots into slashes, so it cannot name a file: put the child alone in its
   * folder. The spawner block is pasted too and stays unless the child has a cell at its own anchor. Never use one in a
   * prefab world generation molds (MoldingDirection DOWN): molding works one chunk buffer at a time and pastes the child
   * only in the buffer that holds the spawner, cutting it in half. A building beside a molded ground goes in a Union prop
   * instead. Keep children out of the folder a world generation prop picks from, or they are placed on their own too.
   */
  childPrefab(path, [x, y, z], { facing: look = 'south', fitHeightmap = false, inheritSeed = true, inheritHeightCondition = true } = {}) {
    const PrefabPath = String(path).replace(/\.prefab\.json$/i, '').replace(/[\\/]+/g, '.');
    return this.set(x, y, z, 'Prefab_Spawner_Block', { facing: look, components: { Components: { PrefabSpawner: { PrefabPath, FitHeightmap: fitHeightmap, InheritSeed: inheritSeed, InheritHeightCondition: inheritHeightCondition } } } });
  }

  /**
   * Ground that thins out into the terrain round a footprint ([[x0, z0], [x1, z1]] in x and z): every column inside it,
   * then columns out to `outer` blocks beyond it kept with a chance that falls with distance and wobbles with noise, so the
   * edge is ragged and never a rectangle. Each kept column gets `top` at y and `fill` below it, `depth` blocks in all.
   * Returns the x,z columns it covered: put loose dressing (scrap, plants, crates) only on those, never past them.
   */
  groundPatch([[x0, z0], [x1, z1]], { y = -1, outer = 3, top = 'Soil_Grass', fill = 'Soil_Dirt', depth = 2, salt = 41, keep = false } = {}) {
    [x0, x1] = order(x0, x1); [z0, z1] = order(z0, z1);
    const covered = new Set();
    for (let x = x0 - outer; x <= x1 + outer; x++) for (let z = z0 - outer; z <= z1 + outer; z++) {
      const d = Math.hypot(Math.max(x0 - x, 0, x - x1), Math.max(z0 - z, 0, z - z1));
      if (d > outer) continue;
      const wobble = noise(x, 0, z, salt) * 0.5 + noise(Math.floor(x / 2), 1, Math.floor(z / 2), salt + 1) * 0.5;
      if (d > 0 && wobble > 1.1 - d / (outer + 1)) continue;
      for (let yy = y; yy > y - depth; yy--) this.set(x, yy, z, yy === y ? top : fill, { keep });
      covered.add(x + ',' + z);
    }
    return covered;
  }

  /**
   * Copy another prefab into this one, turned `turns` quarter turns about the vertical axis (block rotations turned with it),
   * then moved by offset. For modular kits: build a piece once, stamp it wherever the layout needs it.
   */
  stamp(other, [ox, oy, oz] = [0, 0, 0], { turns = 0 } = {}) {
    const t = ((turns % 4) + 4) % 4, turn = ([x, y, z]) => t === 0 ? [x, y, z] : t === 1 ? [z, y, -x] : t === 2 ? [-x, y, -z] : [-z, y, x];
    for (const b of other.blocks.values()) {
      const [x, y, z] = turn([b.x, b.y, b.z]);
      // 4 and 5 are logs and beams lying along z and x (Pipe rotations allow only 0, 4 and 5): an odd turn swaps the axis.
      const r = b.rotation ?? 0, rotation = r === 4 || r === 5 ? (t % 2 ? 9 - r : r) : turnRotation(r, t);
      this.set(x + ox, y + oy, z + oz, b.name, { rotation, components: b.components });
    }
    for (const f of other.fluids.values()) { const [x, y, z] = turn([f.x, f.y, f.z]); this.fluid(x + ox, y + oy, z + oz, f.name, f.level); }
    for (const e of other.entities) {
      // Entities turn about the middle of cell 0, as the blocks do (a block cell turns as its centre point).
      const copy = structuredClone(e), p = copy.Components?.Transform?.Position;
      if (p) { const [x, y, z] = turn([p.X - 0.5, p.Y, p.Z - 0.5]); Object.assign(p, { X: x + 0.5 + ox, Y: y + oy, Z: z + 0.5 + oz }); }
      for (const r of [copy.Components?.Transform?.Rotation, copy.Components?.HeadRotation?.Rotation]) if (r) r.Yaw = (r.Yaw ?? 0) + t * Math.PI / 2;
      this.entities.push(copy);
    }
    return this;
  }

  /** Smallest box holding every block, fluid and entity. */
  bounds() {
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    const add = (x, y, z) => { lo[0] = Math.min(lo[0], x); lo[1] = Math.min(lo[1], y); lo[2] = Math.min(lo[2], z); hi[0] = Math.max(hi[0], x); hi[1] = Math.max(hi[1], y); hi[2] = Math.max(hi[2], z); };
    for (const b of this.blocks.values()) if (b.name !== 'Empty') add(b.x, b.y, b.z);
    for (const f of this.fluids.values()) add(f.x, f.y, f.z);
    return Number.isFinite(lo[0]) ? { min: lo, max: hi, size: [hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1] } : null;
  }
  /** Counts per block id, most used first. */
  stats() {
    const counts = new Map();
    for (const b of this.blocks.values()) counts.set(b.name, (counts.get(b.name) ?? 0) + 1);
    return { blocks: this.blocks.size, fluids: this.fluids.size, entities: this.entities.length, bounds: this.bounds(), ids: [...counts].sort((a, b) => b[1] - a[1]) };
  }
  /** The prefab document, version 8, blocks sorted by y, z, x like the game writes them. */
  toJSON() {
    const sort = list => [...list].sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
    const doc = { version: 8, blockIdVersion: BLOCK_ID_VERSION, anchorX: this.anchor[0], anchorY: this.anchor[1], anchorZ: this.anchor[2], blocks: sort(this.blocks.values()) };
    if (this.fluids.size) doc.fluids = sort(this.fluids.values());
    if (this.entities.length) doc.entities = this.entities;
    return doc;
  }
  /**
   * Write the prefab (creating folders) and print one line of stats and the file's SHA-256. Then run the prefab_finalize tool
   * with that hash: it adds the filler cells of multi-cell blocks and the support values the game needs.
   */
  save(path) {
    mkdirSync(dirname(path), { recursive: true });
    const text = JSON.stringify(this.toJSON(), null, 2) + '\n';
    writeFileSync(path, text);
    const s = this.stats();
    console.log('wrote ' + path + ': ' + s.blocks + ' blocks, ' + s.fluids + ' fluids, ' + s.entities + ' entities' + (s.bounds ? ', size ' + s.bounds.size.join(' x ') : ''));
    console.log('sha256 ' + createHash('sha256').update(text).digest('hex') + ' (pass it to prefab_finalize as expectedHash)');
    return this;
  }
}
