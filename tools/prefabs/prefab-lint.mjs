// Checks a built Prefab for the mistakes that show up in game but not in a quick render: rooms that see the sky, fences
// floating in the air, thin blocks in walls, half slabs on floors, blocked doors and walkways, banners that clip.
// Call lint(p, { seeds, clear }) before p.save(); it prints every problem and returns how many it found.

const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
const YAW_STEP = [[0, 1], [1, 0], [0, -1], [-1, 0]]; // rotation yaw 0..3 = facing south, east, north, west, as [dx, dz]
const key = (x, y, z) => x + ',' + y + ',' + z;

// Cells a player can see or walk through: air, and anything that is not a full wall (furniture, plants, fences, deco).
// Windows and doors are deliberate openings and count as closed.
const OPEN = /Fence|Ladder|Chain|Plant_|Deco_|Furniture_|_Half$|Rope|Banner|Shutter|Wall$|_Beam$/;
const CLOSED = /_Window|_Door/;

export function lint(p, { seeds = [], clear = [], quiet = false } = {}) {
  const problems = [];
  const at = (x, y, z) => p.blocks.get(key(x, y, z));
  const name = (x, y, z) => { const b = at(x, y, z); return b && b.name !== 'Empty' ? b.name : undefined; };
  // Two-high blocks (steep roof pieces, doors) fill the cell above themselves.
  const steepBelow = (x, y, z) => /_Steep$|_Door$/.test(name(x, y - 1, z) ?? '');
  const solid = (x, y, z) => { const n = name(x, y, z); if (!n) return steepBelow(x, y, z); return CLOSED.test(n) || !OPEN.test(n); };
  const { min, max } = bounds(p);

  // 1. Leaks: from each inside cell, walk through open cells; reaching the edge of the build means a hole to the sky.
  const indoor = new Set();
  for (const [label, seed] of seeds) {
    const parent = new Map([[key(...seed), null]]), queue = [seed];
    const walked = parent;
    let out;
    while (queue.length && !out) {
      const c = queue.shift();
      for (const [dx, dy, dz] of DIRS) {
        const n = [c[0] + dx, c[1] + dy, c[2] + dz], k = key(...n);
        if (parent.has(k) || solid(...n)) continue;
        parent.set(k, c);
        if (n.some((v, i) => v < min[i] || v > max[i])) { out = n; break; }
        queue.push(n);
      }
    }
    if (out) {
      const path = []; for (let c = out; c; c = parent.get(key(...c))) path.push(c);
      path.reverse();
      // The hole is where the path first reaches a cell with open sky above it.
      const covered = c => { for (let y = c[1] + 1; y <= max[1]; y++) if (solid(c[0], y, c[2])) return true; return false; };
      const i = path.findIndex(c => !covered(c));
      problems.push(`leak: ${label} reaches outside through ${(path[Math.max(0, i - 1)] ?? path[0]).join(' ')} -> ${(path[i] ?? out).join(' ')}`);
      if (process.env.LINT_PATHS) console.log(label, path.map(c => c.join(',') + '=' + (name(...c) ?? '.')).join(' '));
    } else for (const k of walked.keys()) indoor.add(k);
  }
  // 1b. Open roof ends: a slope piece is a wedge whose two ends are open triangles. An end against an indoor cell
  // shows the inside through the roof (the flood fill counts roof pieces as solid, so it cannot see these).
  for (const b of p.blocks.values()) {
    if (b.name.startsWith('*') || !/_Roof(_Steep)?$/.test(b.name) || (b.rotation ?? 0) >= 4) continue;
    const ends = (b.rotation ?? 0) & 1 ? [[0, 1], [0, -1]] : [[1, 0], [-1, 0]];
    for (const [dx, dz] of ends) if (indoor.has(key(b.x + dx, b.y, b.z + dz))) problems.push(`open roof end: ${b.name} at ${b.x} ${b.y} ${b.z} shows the inside at ${b.x + dx} ${b.y} ${b.z + dz}`);
  }

  for (const b of p.blocks.values()) {
    if (b.name === 'Empty') continue;
    const { x, y, z, name: n } = b, yaw = (b.rotation ?? 0) & 3;
    // 2. A fence must stand on something solid (or on another fence); a fence is never a post or a beam.
    if (/_Fence$/.test(n) && !solid(x, y - 1, z) && !/_Fence$/.test(name(x, y - 1, z) ?? '')) problems.push(`floating fence ${n} at ${x} ${y} ${z}`);
    if (/_Fence$/.test(n) && /_Fence$/.test(name(x, y - 1, z) ?? '')) problems.push(`stacked fences used as a post at ${x} ${y} ${z}: use a trunk`);
    // 3. Thin blocks in walls: a beam is narrower than its cell, so a wall of them is see-through. A beam with wall on
    // both sides along a line is part of that wall: use a full trunk there. Free-standing posts stay beams.
    if (/_Beam$/.test(n) && ((solid(x - 1, y, z) && solid(x + 1, y, z)) || (solid(x, y, z - 1) && solid(x, y, z + 1)))) problems.push(`beam ${n} in a wall at ${x} ${y} ${z}: use a full trunk`);
    // Fence runs: unturned a fence runs east to west, turned once north to south; where a run turns it needs a corner.
    if (/_Fence$/.test(n)) {
      const fx = [-1, 1].filter(d => /_Fence/.test(name(x + d, y, z) ?? '')).length, fz = [-1, 1].filter(d => /_Fence/.test(name(x, y, z + d) ?? '')).length;
      if (fx && fz) problems.push(`fence at ${x} ${y} ${z} joins runs both ways: use a Corner, T or Cross state`);
      else if (fz && yaw % 2 === 0) problems.push(`fence at ${x} ${y} ${z} runs north to south but is not turned (rotation 1)`);
      else if (fx && yaw % 2 === 1) problems.push(`fence at ${x} ${y} ${z} runs east to west but is turned`);
    }
    // Cobwebs sit in a corner: solid on at least two sides.
    if (/SpiderWeb|Cobweb/.test(n) && DIRS.filter(([dx, dy, dz]) => solid(x + dx, y + dy, z + dz)).length < 2) problems.push(`cobweb floating at ${x} ${y} ${z}`);
    // 4. Half slabs lying on a floor make a step in it.
    if (/^Cloth_Block_.*_Half$/.test(n) && solid(x, y - 1, z)) problems.push(`half slab on a floor at ${x} ${y} ${z}: set the floor block itself`);
    // 5. Doors: both sides clear for two cells up.
    if (/_Door$/.test(n)) {
      const [dx, dz] = YAW_STEP[yaw], [ax, az] = dx ? [1, 0] : [0, 1];
      for (const s of [1, -1]) for (const h of [0, 1]) {
        const c = [x + ax * s, y + h, z + az * s];
        if (name(...c) && !/_Door$/.test(name(...c))) problems.push(`door at ${x} ${y} ${z} blocked by ${name(...c)} at ${c.join(' ')}`);
        clear.push([`door at ${x} ${y} ${z}`, c, c, true]);
      }
    }
    // 6. Banners: a wall behind (the side their back faces) and the two cells below empty: a single banner hangs from the
    // top of its cell about 2.4 blocks down.
    if (/Banner/.test(n)) {
      const [dx, dz] = YAW_STEP[yaw];
      if (!solid(x - dx, y, z - dz)) problems.push(`banner at ${x} ${y} ${z} has no wall behind it`);
      for (const d of [1, 2]) if (name(x, y - d, z)) problems.push(`banner at ${x} ${y} ${z} hangs into ${name(x, y - d, z)} at ${x} ${y - d} ${z}`);
    }
  }

  // 7. Walkways (stair tops, landings, doorways, corridors) must hold no blocks and no props.
  const props = p.entities.filter(e => e.Components.Prop).map(e => { const t = e.Components.Transform.Position; return [Math.floor(t.X), Math.floor(t.Y), Math.floor(t.Z), e.Components.Item?.Item?.Id]; });
  for (const [label, a, b, propsOnly] of clear) {
    for (let x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++) for (let y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++) for (let z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++) {
      if (!propsOnly && name(x, y, z) && !/_Door$|Ladder/.test(name(x, y, z))) problems.push(`${label}: ${name(x, y, z)} blocks ${x} ${y} ${z}`);
      for (const q of props) if (q[0] === x && q[1] === y && q[2] === z) problems.push(`${label}: prop ${q[3]} blocks ${x} ${y} ${z}`);
    }
  }

  // 8. What a prop stands on: a full block, or a furniture top made to hold things (tables, counters, shelves). Never a
  // sack, barrel, stool, chair, bed or half slab, and never another prop unless it is a crate. Nothing floats, except
  // food hanging from a chain and banners on a wall.
  const SURFACE = /Table|Counter|Shelf|Desk/;
  // Tables cover 2 x 2 east and south of their cell, counters two cells: a surface within one cell west or north counts.
  const surfaceAt = (x, y, z) => [[0, 0], [-1, 0], [0, -1], [-1, -1], [1, 0]].some(([dx, dz]) => SURFACE.test(name(x + dx, y, z + dz) ?? ''));
  const placed = p.entities.filter(e => e.Components.Prop).map(e => {
    const c = e.Components, t = c.Transform.Position, s = c.EntityScale?.Scale ?? 1, id = c.Item?.Item?.Id ?? '';
    return { id, x: t.X, z: t.Z, bottom: c.BlockEntity ? t.Y - s / 2 : t.Y, top: c.BlockEntity ? t.Y + s / 2 : t.Y + 0.3 * s };
  });
  for (const q of placed) {
    if (/Banner/.test(q.id)) continue;
    const [cx, cz] = [Math.floor(q.x), Math.floor(q.z)];
    const onProp = placed.find(o => o !== q && Math.abs(o.x - q.x) < 0.45 && Math.abs(o.z - q.z) < 0.45 && Math.abs(o.top - q.bottom) < 0.15);
    if (onProp) { if (!/Crate/.test(onProp.id)) problems.push(`prop ${q.id} at ${q.x} ${q.bottom} ${q.z} stands on ${onProp.id}: only on full blocks, crates and tables`); continue; }
    const frac = q.bottom - Math.floor(q.bottom), cy = Math.floor(q.bottom) - (frac < 0.05 ? 1 : 0);
    const under = name(cx, cy, cz);
    if (frac >= 0.05 && frac < 0.9) {
      // Mid-cell: standing on a furniture top in its own cell (a table's top is about 0.9 to 1.0; shelves inside the cell).
      const own = name(cx, Math.floor(q.bottom), cz);
      if (surfaceAt(cx, Math.floor(q.bottom), cz)) continue;
      if (/Chain/.test(name(cx, Math.floor(q.bottom) + 1, cz) ?? '') || /Chain/.test(own ?? '')) continue;
      problems.push(`prop ${q.id} at ${q.x} ${q.bottom.toFixed(2)} ${q.z} floats (bottom inside cell ${cx} ${Math.floor(q.bottom)} ${cz}, ${own ?? 'air'})`);
      continue;
    }
    if (surfaceAt(cx, cy, cz)) continue;
    if (!under) { if (!/Chain/.test(name(cx, cy + 1, cz) ?? '')) problems.push(`prop ${q.id} at ${q.x} ${q.bottom.toFixed(2)} ${q.z} floats over air`); continue; }
    if (!OPEN.test(under) || CLOSED.test(under)) continue;
    problems.push(`prop ${q.id} at ${q.x} ${q.bottom.toFixed(2)} ${q.z} stands on ${under}, which is not a full block`);
  }

  const unique = [...new Set(problems)];
  if (!quiet) console.log(unique.length ? 'lint: ' + unique.length + ' problems\n  ' + unique.join('\n  ') : 'lint: clean');
  return unique;
}

function bounds(p) {
  const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
  for (const b of p.blocks.values()) [b.x, b.y, b.z].forEach((v, i) => { min[i] = Math.min(min[i], v - 1); max[i] = Math.max(max[i], v + 1); });
  return { min, max };
}
