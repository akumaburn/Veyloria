/**
 * Character Store
 *
 * Manages character state including CRUD operations, search, and filtering.
 * Uses centralized GraphQL operations from @graphql module.
 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev } from '@/lib/storeHelpers';
import {
  CHARACTERS_QUERY,
  CHARACTER_QUERY,
  CREATE_CHARACTER_MUTATION,
  UPDATE_CHARACTER_MUTATION,
  DELETE_CHARACTER_MUTATION,
  CLONE_CHARACTER_MUTATION,
  GENERATE_CHARACTER_MUTATION,
  GENERATE_CHARACTER_ROSTER_MUTATION,
  CREATE_CHARACTER_ROSTER_MUTATION,
  RATE_CHARACTER_MUTATION,
  FAVORITE_CHARACTER_MUTATION,
} from '@/graphql/operations';
import type { Character, CharacterDraft } from '@/graphql/types';
import {
  setLoading,
  setError,
  clearLoadingAndError,
  findAndUpdate,
  findAndRemove,
  handleStoreError,
} from '@/lib/storeHelpers';

interface CharacterState {
  characters: Character[];
  userCharacters: Character[];
  publicCharacters: Character[];
  selectedCharacterId: string | null;
  isLoading: boolean;
  error: string | null;
  hasMore: boolean;
  page: number;
  searchQuery: string;
  filter: 'all' | 'mine' | 'public' | 'favorites';

  // Actions
  fetchCharacters: (filter?: string, page?: number, limit?: number) => Promise<void>;
  fetchCharacter: (id: string) => Promise<Character | null>;
  createCharacter: (
    character: Partial<Character> & { lawfulness?: number; goodness?: number },
  ) => Promise<Character | null>;
  updateCharacter: (id: string, updates: Partial<Character>) => Promise<void>;
  deleteCharacter: (id: string) => Promise<void>;
  cloneCharacter: (id: string, newName: string) => Promise<Character | null>;
  generateCharacter: (prompt: string, signal?: AbortSignal) => Promise<CharacterDraft | null>;
  generateCharacterRoster: (
    prompt: string,
    count: number,
    signal: AbortSignal,
  ) => Promise<CharacterDraft[]>;
  createCharacterRoster: (
    drafts: CharacterDraft[],
    groupName?: string,
    setting?: string,
  ) => Promise<Character[]>;
  rateCharacter: (id: string, rating: number) => Promise<void>;
  favoriteCharacter: (id: string, isFavorite: boolean) => Promise<void>;
  searchCharacters: (query: string) => Promise<void>;
  setSelectedCharacter: (id: string | null) => void;
  setFilter: (filter: 'all' | 'mine' | 'public' | 'favorites') => void;
  clearError: () => void;
  reset: () => void;
}

// Using characters query with filter instead for search functionality

export const useCharacterStore = create<CharacterState>()(
  devtoolsInDev(
    persist(
      immer((set, get) => ({
        characters: [],
        userCharacters: [],
        publicCharacters: [],
        selectedCharacterId: null,
        isLoading: false,
        error: null,
        hasMore: true,
        page: 1,
        searchQuery: '',
        filter: 'all',

        fetchCharacters: async (filter = 'all', page = 1, limit = 20) => {
          set((state) => {
            setLoading(state);
            state.filter = filter as any;
          });

          const offset = (page - 1) * limit;

          try {
            const { data } = await apolloClient.query({
              query: CHARACTERS_QUERY,
              variables: {
                filter: filter !== 'all' ? { isPublic: filter === 'public' } : undefined,
                limit,
                offset,
              },
              fetchPolicy: 'network-only',
            });

            set((state) => {
              if (page === 1) {
                state.characters = data.characters.items;
              } else {
                state.characters.push(...data.characters.items);
              }

              // Update categorized lists based on isPublic field
              state.userCharacters = state.characters.filter((c: Character) => !c.isPublic);
              state.publicCharacters = state.characters.filter((c: Character) => c.isPublic);

              state.hasMore = data.characters.hasMore;
              state.page = page;
              clearLoadingAndError(state);
            });
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Failed to fetch characters'));
            });
          }
        },

        fetchCharacter: async (id: string) => {
          try {
            const { data } = await apolloClient.query({
              query: CHARACTER_QUERY,
              variables: { id },
              fetchPolicy: 'network-only',
            });

            if (data.character) {
              set((state) => {
                const index = state.characters.findIndex((c: Character) => c.id === id);
                if (index >= 0) {
                  state.characters[index] = data.character;
                } else {
                  state.characters.push(data.character);
                }
              });
            }

            return data.character;
          } catch (error) {
            console.error('Failed to fetch character:', error);
            return null;
          }
        },

        createCharacter: async (character: Partial<Character>) => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: CREATE_CHARACTER_MUTATION,
              variables: { input: character },
            });

            const newCharacter = data.createCharacter;
            void apolloClient.refetchQueries({ include: ['CharacterAlignmentSummary'] });

            set((state) => {
              state.characters.unshift(newCharacter);
              if (!newCharacter.isPublic) {
                state.userCharacters.unshift(newCharacter);
              } else {
                state.publicCharacters.unshift(newCharacter);
              }
              state.selectedCharacterId = newCharacter.id;
              clearLoadingAndError(state);
            });

            return newCharacter;
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Failed to create character'));
            });
            return null;
          }
        },

        updateCharacter: async (id: string, updates: Partial<Character>) => {
          // Optimistic update
          set((state) => {
            findAndUpdate(state.characters, id, updates);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: UPDATE_CHARACTER_MUTATION,
              variables: { id, input: updates },
            });

            set((state) => {
              findAndUpdate(state.characters, id, data.updateCharacter);
            });
          } catch (error) {
            // Revert on error
            await get().fetchCharacter(id);
            throw error;
          }
        },

        deleteCharacter: async (id: string) => {
          // Optimistic delete - save reference for potential rollback
          const character = get().characters.find((c: Character) => c.id === id);

          set((state) => {
            findAndRemove(state.characters, id);
            findAndRemove(state.userCharacters, id);
            findAndRemove(state.publicCharacters, id);
            if (state.selectedCharacterId === id) {
              state.selectedCharacterId = null;
            }
          });

          try {
            await apolloClient.mutate({
              mutation: DELETE_CHARACTER_MUTATION,
              variables: { id },
            });
          } catch (error) {
            // Revert on error
            if (character) {
              set((state) => {
                state.characters.push(character);
                if (!character.isPublic) {
                  state.userCharacters.push(character);
                } else {
                  state.publicCharacters.push(character);
                }
              });
            }
            throw error;
          }
        },

        cloneCharacter: async (id: string, newName: string) => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: CLONE_CHARACTER_MUTATION,
              variables: { id, name: newName },
            });

            const clonedCharacter = data.cloneCharacter;

            set((state) => {
              state.characters.unshift(clonedCharacter);
              if (!clonedCharacter.isPublic) {
                state.userCharacters.unshift(clonedCharacter);
              }
              state.selectedCharacterId = clonedCharacter.id;
              clearLoadingAndError(state);
            });

            return clonedCharacter;
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Failed to clone character'));
            });
            return null;
          }
        },

        generateCharacterRoster: async (prompt, count, signal) => {
          const { data } = await apolloClient.mutate({
            mutation: GENERATE_CHARACTER_ROSTER_MUTATION,
            variables: { prompt, count },
            fetchPolicy: 'no-cache',
            errorPolicy: 'none',
            context: { fetchOptions: { signal } },
          });
          if (signal.aborted) return [];
          if (
            !Array.isArray(data?.generateCharacterRoster) ||
            data.generateCharacterRoster.length !== count
          ) {
            throw new Error('The model returned the wrong number of characters. Please try again.');
          }
          return data.generateCharacterRoster;
        },

        createCharacterRoster: async (drafts, groupName, setting) => {
          const inputs = drafts.map((draft) => ({
            character: {
              name: draft.character.name,
              description: draft.character.description,
              systemPrompt: draft.character.systemPrompt,
              personality: draft.character.personality,
              appearance: draft.character.appearance,
              gender: draft.character.gender,
              pronouns: draft.character.pronouns,
              ageYears: draft.character.ageYears,
              height: draft.character.height,
              build: draft.character.build,
              hair: draft.character.hair,
              eyes: draft.character.eyes,
              strength: draft.character.strength,
              dexterity: draft.character.dexterity,
              constitution: draft.character.constitution,
              intelligence: draft.character.intelligence,
              wisdom: draft.character.wisdom,
              charisma: draft.character.charisma,
              backstory: draft.character.backstory,
              goals: draft.character.goals,
              likes: draft.character.likes,
              dislikes: draft.character.dislikes,
              greeting: draft.character.greeting,
              lawfulness: draft.lawfulness,
              goodness: draft.goodness,
            },
            relationships: (draft.relationships ?? []).map((relationship) => ({
              targetName: relationship.targetName,
              tier: relationship.tier,
              reason: relationship.reason,
            })),
          }));
          const { data } = await apolloClient.mutate({
            mutation: CREATE_CHARACTER_ROSTER_MUTATION,
            variables: { inputs, groupName, setting },
            errorPolicy: 'none',
          });
          const saved: Character[] = data?.createCharacterRoster;
          if (!Array.isArray(saved) || saved.length !== drafts.length)
            throw new Error('The roster could not be saved.');
          void apolloClient.refetchQueries({
            include: ['CharacterAlignmentSummary', 'Relationships', 'CharacterGroups'],
          });
          set((state) => {
            const ids = new Set(saved.map((character) => character.id));
            state.characters = [
              ...saved,
              ...state.characters.filter((character) => !ids.has(character.id)),
            ];
            state.userCharacters = [
              ...saved,
              ...state.userCharacters.filter((character) => !ids.has(character.id)),
            ];
          });
          return saved;
        },

        generateCharacter: async (prompt: string, signal?: AbortSignal) => {
          try {
            const { data } = await apolloClient.mutate({
              mutation: GENERATE_CHARACTER_MUTATION,
              variables: { prompt },
              fetchPolicy: 'no-cache',
              context: { fetchOptions: { signal } },
            });
            // Generated characters are unsaved editor drafts, not list/cache entries.
            return signal?.aborted ? null : data.generateCharacter;
          } catch (error) {
            if (!signal?.aborted) {
              set((state) => {
                state.error = handleStoreError(error, 'Failed to generate character');
              });
            }
            return null;
          }
        },

        rateCharacter: async (id: string, rating: number) => {
          // Optimistic update
          set((state) => {
            findAndUpdate(state.characters, id, { rating } as Partial<Character>);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: RATE_CHARACTER_MUTATION,
              variables: { id, rating },
            });

            set((state) => {
              findAndUpdate(state.characters, id, {
                rating: data.rateCharacter.rating,
                usageCount: data.rateCharacter.usageCount,
              } as Partial<Character>);
            });
          } catch (error) {
            // Revert on error
            await get().fetchCharacter(id);
            throw error;
          }
        },

        favoriteCharacter: async (id: string, isFavorite: boolean) => {
          // Optimistic update
          set((state) => {
            findAndUpdate(state.characters, id, { isFavorite } as Partial<Character>);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: FAVORITE_CHARACTER_MUTATION,
              variables: { id, isFavorite },
            });

            // Update with server response
            set((state) => {
              if (data.favoriteCharacter) {
                findAndUpdate(state.characters, id, {
                  isFavorite: data.favoriteCharacter.isFavorite,
                } as Partial<Character>);
              }
            });
          } catch (error) {
            // Revert on error
            set((state) => {
              findAndUpdate(state.characters, id, {
                isFavorite: !isFavorite,
              } as Partial<Character>);
            });
            throw error;
          }
        },

        // Use characters query with search filter instead of dedicated search query
        searchCharacters: async (query: string) => {
          if (!query.trim()) {
            await get().fetchCharacters(get().filter);
            return;
          }

          set((state) => {
            setLoading(state);
            state.searchQuery = query;
          });

          try {
            const { data } = await apolloClient.query({
              query: CHARACTERS_QUERY,
              variables: {
                filter: { search: query },
                limit: 20,
                offset: 0,
              },
              fetchPolicy: 'network-only',
            });

            set((state) => {
              state.characters = data.characters.items;
              state.hasMore = data.characters.hasMore;
              clearLoadingAndError(state);
            });
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Search failed'));
            });
          }
        },

        setSelectedCharacter: (id: string | null) => {
          set((state) => {
            state.selectedCharacterId = id;
          });
        },

        setFilter: (filter: 'all' | 'mine' | 'public' | 'favorites') => {
          set((state) => {
            state.filter = filter;
          });
          void get().fetchCharacters(filter);
        },

        clearError: () => {
          set((state) => {
            state.error = null;
          });
        },

        reset: () => {
          set((state) => {
            state.characters = [];
            state.userCharacters = [];
            state.publicCharacters = [];
            state.selectedCharacterId = null;
            state.isLoading = false;
            state.error = null;
            state.hasMore = true;
            state.page = 1;
            state.searchQuery = '';
            state.filter = 'all';
          });
        },
      })),
      {
        name: 'character-storage',
        partialize: (state) => ({
          selectedCharacterId: state.selectedCharacterId,
          filter: state.filter,
        }),
      },
    ),
  ),
);
