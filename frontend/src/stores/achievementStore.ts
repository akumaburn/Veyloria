/**
 * Achievement Store
 *
 * Manages achievements, user achievements, and user progression for roleplay mode.
 * Uses centralized GraphQL operations from @graphql/roleplay-operations module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
import {
  ACHIEVEMENTS_QUERY,
  USER_ACHIEVEMENTS_QUERY,
  USER_PROGRESSION_QUERY,
} from '@/graphql/roleplay-operations';
import type {
  Achievement,
  UserAchievement,
  UserProgression,
} from '@/graphql/types';

interface AchievementState {
  achievements: Achievement[];
  userAchievements: UserAchievement[];
  progression: UserProgression | null;
  isLoading: boolean;
  error: string | null;

  // Actions
  fetchAchievements: (category?: string) => Promise<void>;
  fetchUserAchievements: (userId?: string) => Promise<void>;
  fetchProgression: (userId?: string) => Promise<void>;
  addUserAchievement: (achievement: UserAchievement) => void;
  clearError: () => void;
  reset: () => void;
}

export const useAchievementStore = create<AchievementState>()(
  devtoolsInDev(
    immer((set) => ({
      achievements: [],
      userAchievements: [],
      progression: null,
      isLoading: false,
      error: null,

      fetchAchievements: async (category?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: ACHIEVEMENTS_QUERY,
            variables: { category },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.achievements = data.achievements ?? [];
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch achievements'));
          });
        }
      },

      fetchUserAchievements: async (userId?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: USER_ACHIEVEMENTS_QUERY,
            variables: { userId: userId || undefined },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.userAchievements = data.userAchievements ?? [];
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch user achievements'));
          });
        }
      },

      fetchProgression: async (userId?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: USER_PROGRESSION_QUERY,
            variables: { userId: userId || undefined },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.progression = data.userProgression;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch user progression'));
          });
        }
      },

      addUserAchievement: (achievement: UserAchievement) => {
        set((state) => {
          const exists = state.userAchievements.some(
            (ua) => ua.achievementId === achievement.achievementId
          );
          if (!exists) {
            state.userAchievements.push(achievement);
          }
        });
      },

      clearError: () => {
        set((state) => {
          state.error = null;
        });
      },

      reset: () => {
        set((state) => {
          state.achievements = [];
          state.userAchievements = [];
          state.progression = null;
          state.isLoading = false;
          state.error = null;
        });
      },
    })),
  ),
);
