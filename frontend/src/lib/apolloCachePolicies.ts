/**
 * Cache policies that need to live apart from the client so they can be tested
 * without constructing it (the client builds HTTP and WebSocket links).
 */

/**
 * Merges successive `terrainData(mapId)` responses instead of replacing them.
 *
 * The 3D map asks for the heightmap; the 2D map asks for the same object plus
 * the biome grid. Both fields are about a megabyte of base64. Without a merge
 * the second response replaces the cached object and evicts the half the other
 * view needs, so every tab switch re-downloads it.
 */
export const mergeTerrainData = (
  existing: Record<string, unknown> | undefined,
  incoming: Record<string, unknown>,
) => (existing ? { ...existing, ...incoming } : incoming);
