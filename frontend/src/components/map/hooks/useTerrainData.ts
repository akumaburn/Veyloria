import { useMemo } from 'react';
import { useQuery } from '@apollo/client';
import {
  TERRAIN_DATA_QUERY,
  TERRAIN_BIOMES_QUERY,
  MAP_ROADS_QUERY,
} from '../graphql/map-operations';
import { decodeBase64Float32, decodeBase64Int32 } from '../utils';
import type { TerrainData, RoadSegment } from '@/graphql/types';

/**
 * Terrain data is written once, when the map is generated, and the generator
 * inserts a new row with a new id rather than rewriting an existing one. Every
 * query below is therefore immutable per mapId, which makes `cache-first` both
 * safe and much cheaper: `cache-and-network` re-sent the 1.4 MB base64 heightmap
 * every time the map view remounted, and re-decoded it.
 */
const IMMUTABLE = 'cache-first' as const;

/** Stable empty array, so callers can use it as a memo dependency. */
const NO_ROADS: RoadSegment[] = [];

/** Fetches and decodes the terrain heightmap for a generated map. */
export const useTerrainData = (mapId: string) => {
  const { data, loading, error } = useQuery<{ terrainData: TerrainData | null }>(TERRAIN_DATA_QUERY, {
    variables: { mapId },
    skip: !mapId,
    fetchPolicy: IMMUTABLE,
  });

  const decoded = useMemo(() => {
    const terrain = data?.terrainData;
    if (!terrain) return null;
    return {
      width: terrain.width,
      height: terrain.height,
      heightmap: decodeBase64Float32(terrain.heightmap),
      waterLevel: terrain.waterLevel,
      seed: terrain.seed,
      mapScale: terrain.mapScale,
      theme: terrain.theme || 'temperate',
    };
  }, [data?.terrainData]);

  return { terrain: decoded, loading, error };
};

/**
 * Fetches the biome grid, which only the 2D view paints with.
 *
 * It is a second 1.4 MB field, so it stays out of `useTerrainData` and out of
 * the 3D map's payload.
 */
export const useBiomeGrid = (mapId: string) => {
  const { data, loading, error } = useQuery<{ terrainData: { biomeGrid: string } | null }>(
    TERRAIN_BIOMES_QUERY,
    {
      variables: { mapId },
      skip: !mapId,
      fetchPolicy: IMMUTABLE,
    },
  );

  const biomeGrid = useMemo(() => {
    const encoded = data?.terrainData?.biomeGrid;
    return encoded ? decodeBase64Int32(encoded) : null;
  }, [data?.terrainData?.biomeGrid]);

  return { biomeGrid, loading, error };
};

/** Fetches road data for a generated map. */
export const useMapRoads = (mapId: string) => {
  const { data, loading, error } = useQuery<{ mapRoads: RoadSegment[] }>(MAP_ROADS_QUERY, {
    variables: { mapId },
    skip: !mapId,
    fetchPolicy: IMMUTABLE,
  });

  return {
    roads: data?.mapRoads ?? NO_ROADS,
    loading,
    error,
  };
};
