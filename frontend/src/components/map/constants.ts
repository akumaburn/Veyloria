/** Location marker colors — shared by 2D/3D map views. Preserves existing legend. */
export const LOCATION_COLORS: Record<string, string> = {
  town: '#4ade80',
  city: '#818cf8',
  village: '#86efac',
  fortress: '#f87171',
  castle: '#eab308',
  keep: '#fb7185',
  dungeon: '#ef4444',
  cave: '#a78bfa',
  mine: '#a8a29e',
  forest: '#22c55e',
  grove: '#34d399',
  mountain: '#94a3b8',
  lake: '#60a5fa',
  river_crossing: '#38bdf8',
  water: '#3b82f6',
  shrine: '#c084fc',
  temple: '#a78bfa',
  ruins: '#f59e0b',
  tower: '#fbbf24',
  port: '#2dd4bf',
  camp: '#fb923c',
  oasis: '#fcd34d',
  swamp: '#4a6741',
  road: '#d4d4d8',
};

/** Biome colors for terrain mesh faces. */
export const BIOME_COLORS: Record<number, string> = {
  0: '#1e3a5f',  // DEEP_WATER
  1: '#3b82f6',  // SHALLOW_WATER
  2: '#d4a574',  // BEACH
  3: '#4ade80',  // GRASSLAND
  4: '#22c55e',  // FOREST
  5: '#166534',  // DENSE_FOREST
  6: '#a3a38a',  // HILLS
  7: '#94a3b8',  // MOUNTAIN
  8: '#e2e8f0',  // SNOW_PEAK
  9: '#d4a017',  // DESERT
  10: '#4a6741', // SWAMP
  11: '#2563eb', // RIVER
  12: '#60a5fa', // LAKE
  13: '#a8b4a0', // TUNDRA
};

/**
 * Per-biome color gradient ranges (RGB 0-255).
 * Terrain rendering interpolates between dark and light based on elevation
 * within each biome's height range, creating natural-looking color variation.
 * Palette uses muted, earthy tones inspired by realistic terrain renderers.
 */
export const BIOME_GRADIENT: Record<number, { dark: [number, number, number]; light: [number, number, number] }> = {
  0:  { dark: [10, 18, 40],     light: [25, 45, 70] },     // DEEP_WATER
  1:  { dark: [28, 52, 82],     light: [55, 90, 130] },    // SHALLOW_WATER
  2:  { dark: [150, 128, 88],   light: [210, 182, 138] },  // BEACH
  3:  { dark: [28, 52, 8],      light: [72, 115, 22] },    // GRASSLAND
  4:  { dark: [16, 52, 16],     light: [38, 95, 32] },     // FOREST
  5:  { dark: [10, 36, 12],     light: [24, 72, 24] },     // DENSE_FOREST
  6:  { dark: [100, 95, 72],    light: [155, 148, 118] },  // HILLS
  7:  { dark: [82, 92, 108],    light: [148, 162, 182] },  // MOUNTAIN
  8:  { dark: [192, 202, 218],  light: [244, 248, 254] },  // SNOW_PEAK
  9:  { dark: [148, 118, 38],   light: [218, 182, 62] },   // DESERT
  10: { dark: [40, 58, 34],     light: [78, 105, 64] },    // SWAMP
  11: { dark: [30, 60, 128],    light: [52, 92, 178] },    // RIVER
  12: { dark: [48, 88, 148],    light: [82, 138, 198] },   // LAKE
  13: { dark: [88, 104, 88],    light: [158, 178, 152] },  // TUNDRA
};

/** Fallback biome gradient (GRASSLAND) for unknown biome IDs. */
export const BIOME_GRADIENT_FALLBACK = BIOME_GRADIENT[3];

/** Water biome IDs (used for elevation normalization in gradient rendering). */
export const WATER_BIOME_IDS = new Set([0, 1, 11, 12]);

/** Human-readable biome names keyed by biome ID. */
export const BIOME_NAMES: Record<number, string> = {
  0: 'Deep Water',
  1: 'Shallow Water',
  2: 'Beach',
  3: 'Grassland',
  4: 'Forest',
  5: 'Dense Forest',
  6: 'Hills',
  7: 'Mountain',
  8: 'Snow Peak',
  9: 'Desert',
  10: 'Swamp',
  11: 'River',
  12: 'Lake',
  13: 'Tundra',
};

/** Road type colors */
export const ROAD_COLORS: Record<string, string> = {
  main: '#d4d4d8',
  trail: '#a3a38a',
  path: '#78716c',
};

/** Culture/empire colors for Voronoi overlay */
export const CULTURE_COLORS: Record<string, string> = {
  NORTHERN: '#6366f1',
  SYLVAN: '#22c55e',
  ARIDIAN: '#f59e0b',
  MIREBORN: '#84cc16',
  THALASSIAN: '#06b6d4',
  HEARTHFOLK: '#f97316',
  FROSTBOUND: '#a78bfa',
  DEEPSTONE: '#78716c',
};

/** Camera defaults for different map scales */
export const CAMERA_DEFAULTS: Record<string, { position: [number, number, number]; far: number }> = {
  room: { position: [0, 15, 15], far: 500 },
  building: { position: [0, 30, 30], far: 1000 },
  city: { position: [0, 80, 80], far: 5000 },
  region: { position: [0, 200, 200], far: 10000 },
  continent: { position: [0, 500, 500], far: 50000 },
  planet: { position: [0, 2000, 2000], far: 200000 },
  solar_system: { position: [0, 10000, 10000], far: 1000000 },
  galaxy: { position: [0, 20000, 20000], far: 5000000 },
};

/** Fallback camera defaults for unknown map scales */
export const CAMERA_FALLBACK = { position: [0, 200, 200] as [number, number, number], far: 10000 };

/** Location types for the editor dropdown */
export const LOCATION_TYPES = [
  'town', 'city', 'village', 'fortress', 'castle', 'keep',
  'dungeon', 'cave', 'mine', 'forest', 'grove', 'mountain',
  'lake', 'river_crossing', 'shrine', 'temple', 'ruins',
  'tower', 'port', 'camp', 'oasis', 'swamp', 'water', 'road',
] as const;

export type LocationType = typeof LOCATION_TYPES[number];

/**
 * Compute a scale factor for 3D markers based on average location spacing.
 * Ensures markers look proportional regardless of map density.
 * @param worldSize - world coordinate range (e.g. 200 for generated maps)
 * @param locationCount - number of locations on the map
 * @returns scale factor clamped between 0.3 and 2.0
 */
export function getMarkerScale(worldSize: number, locationCount: number): number {
  const count = Math.max(locationCount, 1);
  const avgSpacing = worldSize / Math.sqrt(count);
  const raw = avgSpacing * 0.25;
  return Math.max(0.3, Math.min(2.0, raw));
}

/** Shared navigation constants for 2D/3D map controls. */
export const MAP_CONTROLS = {
  MIN_ZOOM_2D: 0.25,
  MAX_ZOOM_2D: 6.0,
  ZOOM_FACTOR: 1.12,
  ZOOM_FACTOR_FAST: 1.35,
  PAN_STEP_2D: 20,
  PAN_MOMENTUM_FRICTION: 0.90,
  MIN_MOMENTUM_SPEED: 0.5,
  PAN_SPEED_3D: 0.5,
  ORBIT_SPEED_3D: 0.025,
  ZOOM_SPEED_3D: 0.10,
  ZOOM_SPEED_3D_FAST: 0.28,
  MIN_DISTANCE_3D: 3,
  FOCUS_LERP_BASE: 0.02,
  FOCUS_LERP_MAX: 0.15,
  FOCUS_COMPLETE_DIST: 0.5,
  DRAG_PIXEL_THRESHOLD: 4,
} as const;
