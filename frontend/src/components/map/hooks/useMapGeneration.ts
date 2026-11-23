import { useMutation } from '@apollo/client';
import { GENERATE_PROCEDURAL_MAP } from '../graphql/map-operations';
import type { GenerateMapInput } from '../graphql/map-types';

/** Fields returned by the generateProceduralMap mutation (subset of WorldMap). */
interface GeneratedMapResult {
  mapId: string;
  conversationId: string;
  name: string;
  mapType?: string;
  mapScale?: string;
  gridWidth?: number;
  gridHeight?: number;
}

interface GenerateProceduralMapResult {
  generateProceduralMap: GeneratedMapResult;
}

interface GenerateProceduralMapVariables {
  input: GenerateMapInput;
}

/** Mutation hook for procedural map generation. */
export const useGenerateMap = () => {
  const [generateMap, { loading, error }] = useMutation<
    GenerateProceduralMapResult,
    GenerateProceduralMapVariables
  >(GENERATE_PROCEDURAL_MAP, {
    refetchQueries: ['WorldMaps', 'LocalMap', 'EntityPositions'],
    awaitRefetchQueries: true,
  });

  return {
    generateMap: (input: GenerateMapInput) =>
      generateMap({ variables: { input } }),
    loading,
    error,
  };
};
