/**
 * Procedural 3D geometry factory for each location type.
 * Each model fits within a ~1x1x1 unit bounding box with base at Y=0.
 * Geometries are cached after first creation for reuse across InstancedMesh instances.
 */
import * as THREE from 'three';

const geometryCache = new Map<string, THREE.BufferGeometry>();

/* ─────────────────────── merge helper ─────────────────────── */

/** Merge multiple BufferGeometries into one, concatenating position/normal/index. */
function merge(geometries: THREE.BufferGeometry[]): THREE.BufferGeometry {
  const positions: number[] = [];
  const normals: number[] = [];
  const indices: number[] = [];
  let vertexOffset = 0;
  let allHaveNormals = true;

  for (const g of geometries) {
    const pos = g.getAttribute('position') as THREE.BufferAttribute;
    const norm = g.getAttribute('normal') as THREE.BufferAttribute | undefined;

    if (!norm) allHaveNormals = false;

    for (let i = 0; i < pos.count; i++) {
      positions.push(pos.getX(i), pos.getY(i), pos.getZ(i));
      if (norm) normals.push(norm.getX(i), norm.getY(i), norm.getZ(i));
    }

    if (g.index) {
      for (let i = 0; i < g.index.count; i++) {
        indices.push(g.index.getX(i) + vertexOffset);
      }
    } else {
      for (let i = 0; i < pos.count; i++) {
        indices.push(vertexOffset + i);
      }
    }

    vertexOffset += pos.count;
    g.dispose();
  }

  const merged = new THREE.BufferGeometry();
  merged.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  if (allHaveNormals && normals.length > 0) {
    merged.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
  } else {
    merged.computeVertexNormals();
  }
  merged.setIndex(indices);
  merged.computeBoundingSphere();
  return merged;
}

/* ─────────────── settlement types ─────────────── */

function createVillage(): THREE.BufferGeometry {
  const body = new THREE.BoxGeometry(0.5, 0.4, 0.5);
  body.translate(0, 0.2, 0);
  const roof = new THREE.ConeGeometry(0.45, 0.35, 4);
  roof.rotateY(Math.PI / 4);
  roof.translate(0, 0.575, 0);
  return merge([body, roof]);
}

function createTown(): THREE.BufferGeometry {
  const b1 = new THREE.BoxGeometry(0.38, 0.4, 0.38);
  b1.translate(-0.2, 0.2, 0);
  const r1 = new THREE.ConeGeometry(0.32, 0.28, 4);
  r1.rotateY(Math.PI / 4);
  r1.translate(-0.2, 0.54, 0);
  const b2 = new THREE.BoxGeometry(0.32, 0.5, 0.32);
  b2.translate(0.2, 0.25, 0.05);
  const r2 = new THREE.ConeGeometry(0.28, 0.24, 4);
  r2.rotateY(Math.PI / 4);
  r2.translate(0.2, 0.62, 0.05);
  return merge([b1, r1, b2, r2]);
}

function createCity(): THREE.BufferGeometry {
  const b1 = new THREE.BoxGeometry(0.22, 0.8, 0.22);
  b1.translate(-0.2, 0.4, 0);
  const b2 = new THREE.BoxGeometry(0.2, 0.55, 0.2);
  b2.translate(0.08, 0.275, 0.1);
  const b3 = new THREE.BoxGeometry(0.18, 0.65, 0.18);
  b3.translate(0.15, 0.325, -0.12);
  const spire = new THREE.ConeGeometry(0.07, 0.22, 6);
  spire.translate(-0.2, 0.91, 0);
  return merge([b1, b2, b3, spire]);
}

/* ─────────────── fortification types ─────────────── */

function createFortress(): THREE.BufferGeometry {
  const base = new THREE.BoxGeometry(0.75, 0.22, 0.75);
  base.translate(0, 0.11, 0);
  const parts: THREE.BufferGeometry[] = [base];
  for (const [dx, dz] of [[-0.32, -0.32], [0.32, -0.32], [-0.32, 0.32], [0.32, 0.32]]) {
    const t = new THREE.CylinderGeometry(0.09, 0.09, 0.45, 8);
    t.translate(dx, 0.225, dz);
    parts.push(t);
  }
  return merge(parts);
}

function createCastle(): THREE.BufferGeometry {
  const tower = new THREE.CylinderGeometry(0.22, 0.28, 0.55, 10);
  tower.translate(0, 0.275, 0);
  const roof = new THREE.ConeGeometry(0.28, 0.32, 10);
  roof.translate(0, 0.71, 0);
  const pole = new THREE.CylinderGeometry(0.015, 0.015, 0.28, 4);
  pole.translate(0, 1.01, 0);
  const flag = new THREE.BoxGeometry(0.14, 0.09, 0.02);
  flag.translate(0.07, 1.1, 0);
  return merge([tower, roof, pole, flag]);
}

function createKeep(): THREE.BufferGeometry {
  const body = new THREE.BoxGeometry(0.48, 0.65, 0.48);
  body.translate(0, 0.325, 0);
  const parts: THREE.BufferGeometry[] = [body];
  for (const [dx, dz] of [[-0.18, -0.18], [0.18, -0.18], [-0.18, 0.18], [0.18, 0.18]]) {
    const c = new THREE.BoxGeometry(0.1, 0.14, 0.1);
    c.translate(dx, 0.72, dz);
    parts.push(c);
  }
  return merge(parts);
}

/* ─────────────── underground types ─────────────── */

function createDungeon(): THREE.BufferGeometry {
  const left = new THREE.BoxGeometry(0.1, 0.55, 0.1);
  left.translate(-0.18, 0.275, 0);
  const right = new THREE.BoxGeometry(0.1, 0.55, 0.1);
  right.translate(0.18, 0.275, 0);
  const lintel = new THREE.BoxGeometry(0.48, 0.08, 0.1);
  lintel.translate(0, 0.59, 0);
  const step = new THREE.BoxGeometry(0.26, 0.06, 0.18);
  step.translate(0, 0.03, 0.1);
  return merge([left, right, lintel, step]);
}

function createCave(): THREE.BufferGeometry {
  const dome = new THREE.SphereGeometry(0.38, 12, 8, 0, Math.PI * 2, 0, Math.PI / 2);
  return dome;
}

function createMine(): THREE.BufferGeometry {
  const lBeam = new THREE.BoxGeometry(0.05, 0.55, 0.05);
  lBeam.rotateZ(0.15);
  lBeam.translate(-0.18, 0.275, 0);
  const rBeam = new THREE.BoxGeometry(0.05, 0.55, 0.05);
  rBeam.rotateZ(-0.15);
  rBeam.translate(0.18, 0.275, 0);
  const cross = new THREE.BoxGeometry(0.32, 0.05, 0.05);
  cross.translate(0, 0.46, 0);
  const pick = new THREE.ConeGeometry(0.08, 0.18, 3);
  pick.rotateX(-0.3);
  pick.translate(0, 0.14, 0.14);
  return merge([lBeam, rBeam, cross, pick]);
}

/* ─────────────── nature types ─────────────── */

function createForest(): THREE.BufferGeometry {
  const trunk = new THREE.CylinderGeometry(0.05, 0.07, 0.28, 6);
  trunk.translate(0, 0.14, 0);
  const canopy = new THREE.ConeGeometry(0.34, 0.62, 8);
  canopy.translate(0, 0.59, 0);
  return merge([trunk, canopy]);
}

function createGrove(): THREE.BufferGeometry {
  const trunk = new THREE.CylinderGeometry(0.04, 0.055, 0.22, 6);
  trunk.translate(0, 0.11, 0);
  const canopy = new THREE.SphereGeometry(0.28, 10, 8);
  canopy.translate(0, 0.48, 0);
  return merge([trunk, canopy]);
}

function createMountain(): THREE.BufferGeometry {
  const peak = new THREE.ConeGeometry(0.38, 0.82, 6);
  peak.translate(0, 0.41, 0);
  const peak2 = new THREE.ConeGeometry(0.18, 0.45, 5);
  peak2.translate(0.22, 0.225, 0.1);
  return merge([peak, peak2]);
}

function createLake(): THREE.BufferGeometry {
  const disk = new THREE.CylinderGeometry(0.38, 0.38, 0.04, 16);
  disk.translate(0, 0.02, 0);
  return disk;
}

function createRiverCrossing(): THREE.BufferGeometry {
  const deck = new THREE.BoxGeometry(0.65, 0.05, 0.22);
  deck.translate(0, 0.28, 0);
  const p1 = new THREE.CylinderGeometry(0.035, 0.035, 0.28, 6);
  p1.translate(-0.22, 0.14, 0);
  const p2 = new THREE.CylinderGeometry(0.035, 0.035, 0.28, 6);
  p2.translate(0.22, 0.14, 0);
  const rail = new THREE.BoxGeometry(0.65, 0.03, 0.025);
  rail.translate(0, 0.33, 0.1);
  return merge([deck, p1, p2, rail]);
}

function createWater(): THREE.BufferGeometry {
  const ring = new THREE.RingGeometry(0.18, 0.36, 16);
  ring.rotateX(-Math.PI / 2);
  ring.translate(0, 0.02, 0);
  return ring;
}

function createSwamp(): THREE.BufferGeometry {
  const trunk = new THREE.CylinderGeometry(0.035, 0.055, 0.45, 5);
  trunk.translate(0, 0.225, 0);
  const br1 = new THREE.CylinderGeometry(0.018, 0.025, 0.22, 4);
  br1.rotateZ(0.6);
  br1.translate(0.09, 0.42, 0);
  const br2 = new THREE.CylinderGeometry(0.018, 0.025, 0.18, 4);
  br2.rotateZ(-0.8);
  br2.translate(-0.07, 0.36, 0.04);
  return merge([trunk, br1, br2]);
}

function createOasis(): THREE.BufferGeometry {
  const trunk = new THREE.CylinderGeometry(0.035, 0.055, 0.55, 6);
  trunk.translate(0.04, 0.275, 0);
  const fronds = new THREE.DodecahedronGeometry(0.2, 0);
  fronds.scale(1.2, 0.6, 1.2);
  fronds.translate(0.04, 0.65, 0);
  const pool = new THREE.CylinderGeometry(0.18, 0.18, 0.025, 12);
  pool.translate(0, 0.012, 0);
  return merge([trunk, fronds, pool]);
}

/* ─────────────── religious / magical types ─────────────── */

function createShrine(): THREE.BufferGeometry {
  const stone = new THREE.BoxGeometry(0.18, 0.65, 0.14);
  stone.translate(0, 0.325, 0);
  const top = new THREE.ConeGeometry(0.14, 0.18, 4);
  top.rotateY(Math.PI / 4);
  top.translate(0, 0.74, 0);
  return merge([stone, top]);
}

function createTemple(): THREE.BufferGeometry {
  const base = new THREE.BoxGeometry(0.68, 0.05, 0.48);
  base.translate(0, 0.025, 0);
  const parts: THREE.BufferGeometry[] = [base];
  for (const [cx, cz] of [[-0.22, -0.16], [0.22, -0.16], [-0.22, 0.16], [0.22, 0.16]]) {
    const col = new THREE.CylinderGeometry(0.035, 0.045, 0.45, 6);
    col.translate(cx, 0.275, cz);
    parts.push(col);
  }
  const roof = new THREE.BoxGeometry(0.72, 0.05, 0.52);
  roof.translate(0, 0.525, 0);
  parts.push(roof);
  return merge(parts);
}

/* ─────────────── ancient types ─────────────── */

function createRuins(): THREE.BufferGeometry {
  const col1 = new THREE.CylinderGeometry(0.07, 0.09, 0.38, 8);
  col1.translate(-0.14, 0.19, 0);
  const col2 = new THREE.CylinderGeometry(0.07, 0.09, 0.22, 8);
  col2.translate(0.14, 0.11, 0.04);
  const block = new THREE.BoxGeometry(0.18, 0.08, 0.09);
  block.rotateY(0.3);
  block.translate(0, 0.04, 0.18);
  return merge([col1, col2, block]);
}

/* ─────────────── structure types ─────────────── */

function createTower(): THREE.BufferGeometry {
  const body = new THREE.CylinderGeometry(0.14, 0.16, 0.75, 8);
  body.translate(0, 0.375, 0);
  const roof = new THREE.ConeGeometry(0.18, 0.28, 8);
  roof.translate(0, 0.89, 0);
  return merge([body, roof]);
}

function createPort(): THREE.BufferGeometry {
  const main = new THREE.BoxGeometry(0.55, 0.06, 0.18);
  main.translate(0, 0.03, 0);
  const arm = new THREE.BoxGeometry(0.18, 0.06, 0.35);
  arm.translate(0.18, 0.03, 0.26);
  const post = new THREE.CylinderGeometry(0.025, 0.025, 0.22, 6);
  post.translate(-0.22, 0.11, 0);
  return merge([main, arm, post]);
}

function createCamp(): THREE.BufferGeometry {
  const tent = new THREE.ConeGeometry(0.32, 0.38, 4);
  tent.rotateY(Math.PI / 4);
  tent.translate(0, 0.19, 0);
  const fire = new THREE.CylinderGeometry(0.05, 0.07, 0.06, 8);
  fire.translate(0.28, 0.03, 0);
  return merge([tent, fire]);
}

/* ─────────────── infrastructure types ─────────────── */

function createRoad(): THREE.BufferGeometry {
  const pole = new THREE.CylinderGeometry(0.025, 0.025, 0.55, 6);
  pole.translate(0, 0.275, 0);
  const sign = new THREE.BoxGeometry(0.28, 0.1, 0.025);
  sign.translate(0.1, 0.5, 0);
  return merge([pole, sign]);
}

/* ─────────────── factory ─────────────── */

const CREATORS: Record<string, () => THREE.BufferGeometry> = {
  village: createVillage,
  town: createTown,
  city: createCity,
  fortress: createFortress,
  castle: createCastle,
  keep: createKeep,
  dungeon: createDungeon,
  cave: createCave,
  mine: createMine,
  forest: createForest,
  grove: createGrove,
  mountain: createMountain,
  lake: createLake,
  river_crossing: createRiverCrossing,
  water: createWater,
  swamp: createSwamp,
  oasis: createOasis,
  shrine: createShrine,
  temple: createTemple,
  ruins: createRuins,
  tower: createTower,
  port: createPort,
  camp: createCamp,
  road: createRoad,
};

function createDefault(): THREE.BufferGeometry {
  return new THREE.OctahedronGeometry(0.28, 0);
}

/**
 * Return a cached merged BufferGeometry for the given location type.
 * All models fit within a ~1x1x1 bounding box, base at Y=0.
 */
export function getLocationGeometry(type: string): THREE.BufferGeometry {
  const cached = geometryCache.get(type);
  if (cached) return cached;
  const creator = CREATORS[type];
  const geom = creator ? creator() : createDefault();
  if (!geom.boundingSphere) geom.computeBoundingSphere();
  geometryCache.set(type, geom);
  return geom;
}

/** Dispose all cached geometries (call on unmount). */
export function disposeGeometryCache(): void {
  for (const geom of geometryCache.values()) geom.dispose();
  geometryCache.clear();
}
