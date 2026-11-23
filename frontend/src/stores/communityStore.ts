/**
 * Community Store
 *
 * Manages published stories, featured stories, and community templates.
 * Uses centralized GraphQL operations from @graphql/roleplay-operations module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
import {
  PUBLISHED_STORIES_QUERY,
  FEATURED_STORIES_QUERY,
  COMMUNITY_TEMPLATES_QUERY,
  PUBLISH_STORY,
  UNPUBLISH_STORY,
  RATE_STORY,
  SUBMIT_TEMPLATE,
} from '@/graphql/roleplay-operations';
import type {
  PublishedStory,
  CommunityTemplate,
  PublishStoryInput,
  RateStoryInput,
  SubmitTemplateInput,
} from '@/graphql/types';

interface CommunityState {
  publishedStories: PublishedStory[];
  featuredStories: PublishedStory[];
  communityTemplates: CommunityTemplate[];
  storiesTotalCount: number;
  storiesHasMore: boolean;
  templatesTotalCount: number;
  templatesHasMore: boolean;
  isLoading: boolean;
  error: string | null;

  // Actions
  fetchPublishedStories: (genre?: string, limit?: number, offset?: number) => Promise<void>;
  fetchFeaturedStories: (limit?: number) => Promise<void>;
  fetchCommunityTemplates: (limit?: number, offset?: number) => Promise<void>;
  publishStory: (input: PublishStoryInput) => Promise<void>;
  unpublishStory: (storyId: string) => Promise<void>;
  rateStory: (input: RateStoryInput) => Promise<void>;
  submitTemplate: (input: SubmitTemplateInput) => Promise<void>;
  clearError: () => void;
  reset: () => void;
}

export const useCommunityStore = create<CommunityState>()(
  devtoolsInDev(
    immer((set) => ({
      publishedStories: [],
      featuredStories: [],
      communityTemplates: [],
      storiesTotalCount: 0,
      storiesHasMore: true,
      templatesTotalCount: 0,
      templatesHasMore: true,
      isLoading: false,
      error: null,

      fetchPublishedStories: async (genre?: string, limit: number = 20, offset: number = 0) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: PUBLISHED_STORIES_QUERY,
            variables: { genre, limit, offset },
            fetchPolicy: 'network-only',
          });

          const pagination = data.publishedStories;
          set((state) => {
            if (offset === 0) {
              state.publishedStories = pagination.items;
            } else {
              state.publishedStories.push(...pagination.items);
            }
            state.storiesHasMore = pagination.hasMore;
            state.storiesTotalCount = pagination.totalCount;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch published stories'));
          });
        }
      },

      fetchFeaturedStories: async (limit: number = 10) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: FEATURED_STORIES_QUERY,
            variables: { limit },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.featuredStories = data.featuredStories;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch featured stories'));
          });
        }
      },

      fetchCommunityTemplates: async (limit: number = 20, offset: number = 0) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: COMMUNITY_TEMPLATES_QUERY,
            variables: { limit, offset },
            fetchPolicy: 'network-only',
          });

          const pagination = data.communityTemplates;
          set((state) => {
            if (offset === 0) {
              state.communityTemplates = pagination.items;
            } else {
              state.communityTemplates.push(...pagination.items);
            }
            state.templatesHasMore = pagination.hasMore;
            state.templatesTotalCount = pagination.totalCount;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch community templates'));
          });
        }
      },

      publishStory: async (input: PublishStoryInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: PUBLISH_STORY,
            variables: { input },
          });

          set((state) => {
            state.publishedStories.unshift(data.publishStory);
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to publish story'));
          });
        }
      },

      unpublishStory: async (storyId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          await apolloClient.mutate({
            mutation: UNPUBLISH_STORY,
            variables: { storyId },
          });

          set((state) => {
            state.publishedStories = state.publishedStories.filter(
              (s) => s.storyId !== storyId
            );
            state.featuredStories = state.featuredStories.filter(
              (s) => s.storyId !== storyId
            );
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to unpublish story'));
          });
        }
      },

      rateStory: async (input: RateStoryInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          await apolloClient.mutate({
            mutation: RATE_STORY,
            variables: { input },
          });

          set((state) => {
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to rate story'));
          });
        }
      },

      submitTemplate: async (input: SubmitTemplateInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: SUBMIT_TEMPLATE,
            variables: { input },
          });

          set((state) => {
            state.communityTemplates.unshift(data.submitTemplate);
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to submit template'));
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
          state.publishedStories = [];
          state.featuredStories = [];
          state.communityTemplates = [];
          state.storiesTotalCount = 0;
          state.storiesHasMore = true;
          state.templatesTotalCount = 0;
          state.templatesHasMore = true;
          state.isLoading = false;
          state.error = null;
        });
      },
    })),
  ),
);
