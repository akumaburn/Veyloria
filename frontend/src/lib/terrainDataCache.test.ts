import { describe, expect, it } from 'vitest';
import { InMemoryCache } from '@apollo/client';
import { mergeTerrainData } from './apolloCachePolicies';
import { TERRAIN_DATA_QUERY, TERRAIN_BIOMES_QUERY } from '@/components/map/graphql/map-operations';

/**
 * The 3D map and the 2D map read the same `terrainData(mapId)` object through
 * different query documents. Both halves are about a megabyte, so a regression
 * here is expensive: the losing view re-downloads its half on every mount.
 */
const makeCache = () =>
  new InMemoryCache({
    typePolicies: {
      Query: { fields: { terrainData: { merge: mergeTerrainData } } },
    },
  });

const mapId = 'map-1';

/** Shape of the cached slice, so reads are typed without Apollo's generics. */
interface TerrainSlice {
  terrainData?: {
    heightmap?: string;
    width?: number;
    biomeGrid?: string;
  } | null;
}

const readTerrain = (cache: InMemoryCache, id: string) =>
  cache.readQuery<TerrainSlice>({ query: TERRAIN_DATA_QUERY, variables: { mapId: id } });

const readBiomes = (cache: InMemoryCache, id: string) =>
  cache.readQuery<TerrainSlice>({ query: TERRAIN_BIOMES_QUERY, variables: { mapId: id } });

describe('terrainData cache policy', () => {
  it('keeps both halves when two query documents write to the same object', () => {
    const cache = makeCache();

    cache.writeQuery({
      query: TERRAIN_DATA_QUERY,
      variables: { mapId },
      data: {
        terrainData: {
          __typename: 'TerrainData',
          width: 512,
          height: 512,
          heightmap: 'AAAA',
          waterLevel: 0.01,
          seed: 42,
          mapScale: 'region',
          theme: 'temperate',
        },
      },
    });

    cache.writeQuery({
      query: TERRAIN_BIOMES_QUERY,
      variables: { mapId },
      data: { terrainData: { __typename: 'TerrainData', biomeGrid: 'BBBB' } },
    });

    // Each document reads back its own half: neither write evicted the other.
    expect(readTerrain(cache, mapId)?.terrainData).toMatchObject({
      heightmap: 'AAAA',
      width: 512,
    });
    expect(readBiomes(cache, mapId)?.terrainData).toMatchObject({ biomeGrid: 'BBBB' });
  });

  it('loses the first half when the merge policy is missing', () => {
    // The reason the policy exists: a plain cache replaces the object, and the
    // next read of the other document finds it incomplete.
    const cache = new InMemoryCache();
    const terrainWrite = {
      query: TERRAIN_DATA_QUERY,
      variables: { mapId },
      data: {
        terrainData: {
          __typename: 'TerrainData',
          width: 512,
          height: 512,
          heightmap: 'AAAA',
          waterLevel: 0.01,
          seed: 42,
          mapScale: 'region',
          theme: 'temperate',
        },
      },
    } as const;

    cache.writeQuery(terrainWrite);
    // Apollo reports the partial object rather than merging it.
    cache.writeQuery({
      query: TERRAIN_BIOMES_QUERY,
      variables: { mapId },
      data: { terrainData: { __typename: 'TerrainData', biomeGrid: 'BBBB' } },
    });

    // The heightmap half is gone, which is what sent the 3D view back to the
    // network on every tab switch.
    expect(readTerrain(cache, mapId)?.terrainData ?? null).toBeNull();
  });

  it('does not leak one map into another', () => {
    const cache = makeCache();

    cache.writeQuery({
      query: TERRAIN_DATA_QUERY,
      variables: { mapId: 'a' },
      data: {
        terrainData: {
          __typename: 'TerrainData',
          width: 512,
          height: 512,
          heightmap: 'AAA',
          waterLevel: 0.01,
          seed: 1,
          mapScale: 'region',
          theme: 'temperate',
        },
      },
    });
    cache.writeQuery({
      query: TERRAIN_DATA_QUERY,
      variables: { mapId: 'b' },
      data: {
        terrainData: {
          __typename: 'TerrainData',
          width: 256,
          height: 256,
          heightmap: 'BBB',
          waterLevel: 0.02,
          seed: 2,
          mapScale: 'city',
          theme: 'desert',
        },
      },
    });

    expect(readTerrain(cache, 'a')?.terrainData?.heightmap).toBe('AAA');
    expect(readTerrain(cache, 'b')?.terrainData?.heightmap).toBe('BBB');
  });

  it('replaces rather than merges when a second map reports the same field', () => {
    // A fresh generation inserts a new mapId, so the merge only ever sees
    // partial selections of the same map. This guards the assumption.
    const merged = mergeTerrainData({ heightmap: 'old', biomeGrid: 'old' }, { heightmap: 'new' });
    expect(merged).toEqual({ heightmap: 'new', biomeGrid: 'old' });
  });
});
