/**
 * Bookmark Store
 *
 * Manages story bookmarks and dice roll history for roleplay mode.
 * Uses centralized GraphQL operations from @graphql/roleplay-operations module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
import {
  BOOKMARKS_QUERY,
  DICE_HISTORY_QUERY,
  CREATE_BOOKMARK,
  DELETE_BOOKMARK,
  ROLL_DICE,
} from '@/graphql/roleplay-operations';
import type {
  StoryBookmark,
  DiceRollResult,
  CreateBookmarkInput,
  RollDiceInput,
} from '@/graphql/types';

interface BookmarkState {
  bookmarks: StoryBookmark[];
  diceHistory: DiceRollResult[];
  lastRoll: DiceRollResult | null;
  isLoading: boolean;
  error: string | null;

  // Actions
  fetchBookmarks: (conversationId: string) => Promise<void>;
  createBookmark: (input: CreateBookmarkInput) => Promise<void>;
  deleteBookmark: (bookmarkId: string) => Promise<void>;
  fetchDiceHistory: (conversationId: string, limit?: number) => Promise<void>;
  rollDice: (input: RollDiceInput) => Promise<void>;
  clearError: () => void;
  reset: () => void;
}

export const useBookmarkStore = create<BookmarkState>()(
  devtoolsInDev(
    immer((set, get) => ({
      bookmarks: [],
      diceHistory: [],
      lastRoll: null,
      isLoading: false,
      error: null,

      fetchBookmarks: async (conversationId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: BOOKMARKS_QUERY,
            variables: { conversationId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.bookmarks = data.bookmarks;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch bookmarks'));
          });
        }
      },

      createBookmark: async (input: CreateBookmarkInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: CREATE_BOOKMARK,
            variables: { input },
          });

          set((state) => {
            state.bookmarks.unshift(data.createBookmark);
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to create bookmark'));
          });
        }
      },

      deleteBookmark: async (bookmarkId: string) => {
        const bookmark = get().bookmarks.find((b) => b.bookmarkId === bookmarkId);
        const originalIndex = get().bookmarks.findIndex((b) => b.bookmarkId === bookmarkId);

        set((state) => {
          const idx = state.bookmarks.findIndex((b) => b.bookmarkId === bookmarkId);
          if (idx >= 0) state.bookmarks.splice(idx, 1);
        });

        try {
          await apolloClient.mutate({
            mutation: DELETE_BOOKMARK,
            variables: { bookmarkId },
          });
        } catch (error) {
          if (bookmark) {
            set((state) => {
              // Restore at the original position — push() would move the item
              // to the bottom, a phantom reorder users perceive as a bug.
              if (originalIndex >= 0 && originalIndex <= state.bookmarks.length) {
                state.bookmarks.splice(originalIndex, 0, bookmark);
              } else {
                state.bookmarks.unshift(bookmark);
              }
            });
          }
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to delete bookmark'));
          });
        }
      },

      fetchDiceHistory: async (conversationId: string, limit?: number) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: DICE_HISTORY_QUERY,
            variables: { conversationId, limit },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.diceHistory = data.diceHistory;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch dice history'));
          });
        }
      },

      rollDice: async (input: RollDiceInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: ROLL_DICE,
            variables: { input },
          });

          set((state) => {
            state.lastRoll = data.rollDice;
            state.diceHistory.unshift(data.rollDice);
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to roll dice'));
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
          state.bookmarks = [];
          state.diceHistory = [];
          state.lastRoll = null;
          state.isLoading = false;
          state.error = null;
        });
      },
    })),
  ),
);
