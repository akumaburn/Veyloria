import { describe, expect, it } from 'vitest';
import { bakeHeightmap } from './bakeHeightmap';
import { MAX_ELEVATION_FACTOR } from '../utils';

const worldSize = 200;
const maxElevation = worldSize * MAX_ELEVATION_FACTOR;

/** A flat plateau: every sample at the same normalised height. */
function flat(width: number, height: number, value: number) {
  return new Float32Array(width * height).fill(value);
}

describe('bakeHeightmap', () => {
  it('scales heights to world units', () => {
    const { data, peak } = bakeHeightmap({
      heights: flat(64, 64, 0.5),
      width: 64,
      height: 64,
      worldSize,
    });

    // The sample furthest from the border keeps the full height.
    expect(data[32 * 64 + 32]).toBeCloseTo(0.5 * maxElevation, 4);
    expect(peak).toBeCloseTo(0.5 * maxElevation, 4);
  });

  it('fades the terrain to zero at the map border', () => {
    const size = 128;
    const { data } = bakeHeightmap({
      heights: flat(size, size, 1),
      width: size,
      height: size,
      worldSize,
    });

    const unfaded = maxElevation;
    // The outermost texel sits inside the 5% fade band, so almost nothing is
    // left of it; the shader used to reach exactly zero only at the boundary.
    expect(data[0]).toBeLessThan(unfaded * 0.02);
    expect(data[0]).toBeGreaterThanOrEqual(0);

    // The fade only affects the band, so anything past it keeps full height.
    const inset = Math.ceil(size * 0.05) + 1;
    expect(data[Math.floor(size / 2) * size + inset]).toBeCloseTo(unfaded, 4);
    expect(data[Math.floor(size / 2) * size + Math.floor(size / 2)]).toBeCloseTo(unfaded, 4);

    // Coverage grows strictly until the fade reaches full height.
    const row = Math.floor(size / 2);
    let x = 1;
    while (x < inset && data[row * size + x] < unfaded) {
      expect(data[row * size + x]).toBeGreaterThan(data[row * size + x - 1]);
      x++;
    }
    // The band spans several texels, so the loop above must have run.
    expect(x).toBeGreaterThan(1);
  });

  it('replaces non-finite samples instead of poisoning the texture', () => {
    const heights = flat(8, 8, 1);
    heights[27] = Number.NaN;
    heights[28] = Number.POSITIVE_INFINITY;

    const { data } = bakeHeightmap({ heights, width: 8, height: 8, worldSize });

    expect(data.every((value) => Number.isFinite(value))).toBe(true);
    expect(data[27]).toBe(0);
    expect(data[28]).toBe(0);
  });

  it('reports the peak of the baked data, not the input', () => {
    const heights = flat(32, 32, 1);
    // A spike in the fade band is reduced by the fade.
    heights[0] = 1;
    const { data, peak } = bakeHeightmap({ heights, width: 32, height: 32, worldSize });

    expect(peak).toBeCloseTo(Math.max(...data), 5);
    expect(peak).toBeCloseTo(maxElevation, 4);
  });
});
