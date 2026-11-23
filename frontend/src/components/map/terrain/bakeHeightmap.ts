import { MAX_ELEVATION_FACTOR } from '../utils';

export interface BakeHeightmapInput {
  /** Normalised heights, row-major, `width * height` long. */
  heights: Float32Array;
  width: number;
  height: number;
  /** World extent of the map, e.g. 200 for [-100, 100]. */
  worldSize: number;
}

export interface BakedHeightmap {
  /** Heights in world units with the border fade applied. */
  data: Float32Array;
  /** Highest baked height. */
  peak: number;
}

/** Fraction of the half extent over which the surface fades into the map edge. */
const FADE_FRACTION = 0.05;

/**
 * Fades the map border to zero and scales the heights to world units.
 *
 * The ray-march shader samples the heightmap dozens of times per pixel, so it
 * reads this texture directly instead of repeating the fade and the scale on
 * every sample. Fading here also removes the cliff the terrain would otherwise
 * show where the heightmap ends.
 */
export function bakeHeightmap({
  heights,
  width,
  height,
  worldSize,
}: BakeHeightmapInput): BakedHeightmap {
  const maxElevation = worldSize * MAX_ELEVATION_FACTOR;
  const halfWorld = worldSize * 0.5;
  const fadeWidth = halfWorld * FADE_FRACTION;

  // Matches the shader's smoothstep(halfWorld, halfWorld - fadeWidth, |coord|).
  const edgeFade = (coord: number) => {
    const t = Math.min(1, Math.max(0, (halfWorld - Math.abs(coord)) / fadeWidth));
    return t * t * (3 - 2 * t);
  };

  const data = new Float32Array(width * height);
  let peak = 0;
  for (let y = 0; y < height; y++) {
    const worldY = ((y + 0.5) / height) * worldSize - halfWorld;
    const fadeY = edgeFade(worldY);
    for (let x = 0; x < width; x++) {
      const raw = heights[y * width + x];
      const worldX = ((x + 0.5) / width) * worldSize - halfWorld;
      // A non-finite sample would poison every interpolation that touches it.
      const value = (Number.isFinite(raw) ? raw : 0) * edgeFade(worldX) * fadeY * maxElevation;
      data[y * width + x] = value;
      if (value > peak) peak = value;
    }
  }

  return { data, peak };
}
