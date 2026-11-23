import { useQuery } from '@apollo/client';
import { CHARACTER_GROUPS_QUERY } from '@/graphql/operations';
import type { CharacterGroup } from '@/graphql/types';

/**
 * Saved rosters (generated casts) owned by the current user.
 * Roster membership is returned in saved order, so selecting a roster
 * preserves the order the cast was generated in.
 */
export const useCharacterGroups = () => {
  const { data, loading, error, refetch } = useQuery<{ characterGroups: CharacterGroup[] }>(
    CHARACTER_GROUPS_QUERY,
    { fetchPolicy: 'cache-and-network' }
  );

  return {
    rosters: data?.characterGroups ?? [],
    loading,
    error,
    refetch,
  };
};
