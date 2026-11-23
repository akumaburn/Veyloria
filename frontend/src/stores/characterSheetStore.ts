/**
 * Character Sheet Store
 *
 * Manages character stats, relationships, and inventory for roleplay mode.
 * Uses centralized GraphQL operations from @graphql/roleplay-operations module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
import {
  CHARACTER_STATS_QUERY,
  CHARACTER_RELATIONSHIPS_QUERY,
  CHARACTER_INVENTORY_QUERY,
  SET_CHARACTER_STAT,
  REMOVE_CHARACTER_STAT,
  SET_RELATIONSHIP,
  ADD_INVENTORY_ITEM,
  REMOVE_INVENTORY_ITEM,
  EQUIP_ITEM,
} from '@/graphql/roleplay-operations';
import type {
  CharacterStat,
  CharacterRelationship,
  CharacterInventoryItem,
  SetCharacterStatInput,
  SetRelationshipInput,
  AddInventoryItemInput,
} from '@/graphql/types';

interface CharacterSheetState {
  stats: CharacterStat[];
  relationships: CharacterRelationship[];
  inventory: CharacterInventoryItem[];
  isLoading: boolean;
  error: string | null;

  // Actions
  fetchStats: (conversationId: string, characterId: string) => Promise<void>;
  setStat: (input: SetCharacterStatInput) => Promise<void>;
  removeStat: (conversationId: string, characterId: string, statName: string) => Promise<void>;
  fetchRelationships: (conversationId: string, characterId?: string) => Promise<void>;
  setRelationship: (input: SetRelationshipInput) => Promise<void>;
  fetchInventory: (conversationId: string, characterId: string) => Promise<void>;
  addInventoryItem: (input: AddInventoryItemInput) => Promise<void>;
  removeInventoryItem: (inventoryId: string) => Promise<void>;
  equipItem: (inventoryId: string, equipped: boolean) => Promise<void>;
  clearError: () => void;
  reset: () => void;
}

export const useCharacterSheetStore = create<CharacterSheetState>()(
  devtoolsInDev(
    immer((set, get) => ({
      stats: [],
      relationships: [],
      inventory: [],
      isLoading: false,
      error: null,

      fetchStats: async (conversationId: string, characterId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: CHARACTER_STATS_QUERY,
            variables: { conversationId, characterId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.stats = data.characterStats;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch character stats'));
          });
        }
      },

      setStat: async (input: SetCharacterStatInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: SET_CHARACTER_STAT,
            variables: { input },
          });

          set((state) => {
            const returnedStat = data?.setCharacterStat;
            if (returnedStat) {
              const idx = state.stats.findIndex(
                (s) => s.statId === returnedStat.statId
              );

              if (idx >= 0) {
                state.stats[idx] = { ...state.stats[idx], ...returnedStat };
              } else {
                state.stats.push(returnedStat);
              }
            }

            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to set character stat'));
          });
        }
      },

      removeStat: async (conversationId: string, characterId: string, statName: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          await apolloClient.mutate({
            mutation: REMOVE_CHARACTER_STAT,
            variables: { conversationId, characterId, statName },
          });

          set((state) => {
            state.stats = state.stats.filter(
              (s) =>
                !(
                  s.conversationId === conversationId &&
                  s.characterId === characterId &&
                  s.statName === statName
                )
            );
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to remove character stat'));
          });
        }
      },

      fetchRelationships: async (conversationId: string, characterId?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: CHARACTER_RELATIONSHIPS_QUERY,
            variables: { conversationId, characterId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.relationships = data.characterRelationships;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch character relationships'));
          });
        }
      },

      setRelationship: async (input: SetRelationshipInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: SET_RELATIONSHIP,
            variables: { input },
          });

          set((state) => {
            const returnedRelationship = data?.setRelationship;
            if (returnedRelationship) {
              const idx = state.relationships.findIndex(
                (r) => r.relationshipId === returnedRelationship.relationshipId
              );

              if (idx >= 0) {
                state.relationships[idx] = { ...state.relationships[idx], ...returnedRelationship };
              } else {
                state.relationships.push(returnedRelationship);
              }
            }

            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to set relationship'));
          });
        }
      },

      fetchInventory: async (conversationId: string, characterId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: CHARACTER_INVENTORY_QUERY,
            variables: { conversationId, characterId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.inventory = data.characterInventory;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch character inventory'));
          });
        }
      },

      addInventoryItem: async (input: AddInventoryItemInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: ADD_INVENTORY_ITEM,
            variables: { input },
          });

          set((state) => {
            const returnedItem = data?.addInventoryItem;
            if (returnedItem) {
              // Backend may stack onto an existing item — update instead of duplicating
              const idx = state.inventory.findIndex(
                (i) => i.inventoryId === returnedItem.inventoryId
              );
              if (idx >= 0) {
                state.inventory[idx] = { ...state.inventory[idx], ...returnedItem };
              } else {
                state.inventory.push(returnedItem);
              }
            }
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to add inventory item'));
          });
        }
      },

      removeInventoryItem: async (inventoryId: string) => {
        const item = get().inventory.find((i) => i.inventoryId === inventoryId);
        const originalIndex = get().inventory.findIndex((i) => i.inventoryId === inventoryId);

        set((state) => {
          const idx = state.inventory.findIndex((i) => i.inventoryId === inventoryId);
          if (idx >= 0) state.inventory.splice(idx, 1);
        });

        try {
          await apolloClient.mutate({
            mutation: REMOVE_INVENTORY_ITEM,
            variables: { inventoryId },
          });
        } catch (error) {
          if (item) {
            set((state) => {
              // Restore at the original position — push() would move the item
              // to the bottom, a phantom reorder users perceive as a bug.
              if (originalIndex >= 0 && originalIndex <= state.inventory.length) {
                state.inventory.splice(originalIndex, 0, item);
              } else {
                state.inventory.unshift(item);
              }
            });
          }
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to remove inventory item'));
          });
        }
      },

      equipItem: async (inventoryId: string, equipped: boolean) => {
        const item = get().inventory.find((i) => i.inventoryId === inventoryId);
        const wasEquipped = item?.isEquipped;

        set((state) => {
          const itemToUpdate = state.inventory.find((i) => i.inventoryId === inventoryId);
          if (itemToUpdate) {
            itemToUpdate.isEquipped = equipped;
          }
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: EQUIP_ITEM,
            variables: { inventoryId, equipped },
          });

          set((state) => {
            const idx = state.inventory.findIndex((i) => i.inventoryId === inventoryId);
            if (idx >= 0) state.inventory[idx] = { ...state.inventory[idx], ...data.equipItem };
          });
        } catch (error) {
          set((state) => {
            const itemToRevert = state.inventory.find((i) => i.inventoryId === inventoryId);
            if (itemToRevert) {
              itemToRevert.isEquipped = wasEquipped;
            }
            setError(state, handleStoreError(error, 'Failed to equip/unequip item'));
          });
        }
      },

      clearError: () => {
        set((state) => {
          state.error = null;
        });
      },

      reset: () => {
        set((state) => {
          state.stats = [];
          state.relationships = [];
          state.inventory = [];
          state.isLoading = false;
          state.error = null;
        });
      },
    })),
  ),
);
