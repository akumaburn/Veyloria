/**
 * Convert DB coordinates (x=east/west, y=north/south, z=elevation)
 * to Three.js position [x=east/west, y=up, z=south/north]
 */
export const toThreePos = (x: number, y: number, z: number): [number, number, number] => [x, z, y];

/** Canvas coordinate system helpers for 2D map */
export const coordToCanvas = (coord: number, canvasSize: number, coordRange: number): number =>
  ((coord + coordRange / 2) / coordRange) * canvasSize;

export const canvasToCoord = (px: number, canvasSize: number, coordRange: number): number =>
  (px / canvasSize) * coordRange - coordRange / 2;

/** Decode a Base64-encoded Float32 array (little-endian) */
export function decodeBase64Float32(base64: string): Float32Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new Float32Array(bytes.buffer);
}

/** Decode a Base64-encoded Int32 array (little-endian) */
export function decodeBase64Int32(base64: string): Int32Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new Int32Array(bytes.buffer);
}

/** Get world size based on map scale */
export function scaleToWorldSize(mapScale: string): number {
  switch (mapScale) {
    case 'room': return 30;
    case 'building': return 60;
    case 'city': return 200;
    case 'continent': return 1000;
    case 'planet': return 4000;
    default: return 200; // region — matches backend WORLD_SCALE=200
  }
}

/** Elevation multiplier: terrain Y = heightmap * maxElevation */
export const MAX_ELEVATION_FACTOR = 0.30;
