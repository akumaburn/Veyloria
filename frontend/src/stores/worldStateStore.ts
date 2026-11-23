/**
 * World State Store
 *
 * Manages roleplay world state including templates, scenes, knowledge entries, and agent model configurations.
 * Uses centralized GraphQL operations from @graphql/roleplay-operations module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
import {
  WORLD_STATE_QUERY,
  STORY_TEMPLATES_QUERY,
  ACTIVE_SCENE_QUERY,
  KNOWLEDGE_ENTRIES_QUERY,
  AGENT_MODEL_CONFIG_QUERY,
  UPDATE_WORLD_STATE,
  INITIALIZE_FROM_TEMPLATE,
  CHANGE_SCENE,
  UPDATE_AGENT_MODEL_CONFIG,
} from '@/graphql/roleplay-operations';
import type {
  WorldState,
  StoryTemplate,
  Scene,
  KnowledgeEntry,
  AgentModelConfig,
  UpdateWorldStateInput,
  ChangeSceneInput,
  AgentModelConfigInput,
} from '@/graphql/types';

interface WorldStateState {
  worldState: WorldState | null;
  templates: StoryTemplate[];
  activeScene: Scene | null;
  knowledgeEntries: KnowledgeEntry[];
  agentModelConfig: AgentModelConfig | null;
  isLoading: boolean;
  error: string | null;

  // Actions
  fetchWorldState: (conversationId: string) => Promise<void>;
  updateWorldState: (conversationId: string, input: UpdateWorldStateInput) => Promise<void>;
  initializeFromTemplate: (conversationId: string, templateId: string) => Promise<void>;
  fetchTemplates: (genre?: string) => Promise<void>;
  fetchActiveScene: (conversationId: string) => Promise<void>;
  changeScene: (input: ChangeSceneInput) => Promise<void>;
  fetchKnowledgeEntries: (conversationId: string, characterId?: string) => Promise<void>;
  fetchAgentModelConfig: (conversationId: string) => Promise<void>;
  updateAgentModelConfig: (conversationId: string, input: AgentModelConfigInput) => Promise<void>;
  clearError: () => void;
  reset: () => void;
}

export const useWorldStateStore = create<WorldStateState>()(
  devtoolsInDev(
    immer((set) => ({
      worldState: null,
      templates: [],
      activeScene: null,
      knowledgeEntries: [],
      agentModelConfig: null,
      isLoading: false,
      error: null,

      fetchWorldState: async (conversationId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: WORLD_STATE_QUERY,
            variables: { conversationId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.worldState = data.worldState;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch world state'));
          });
        }
      },

      updateWorldState: async (conversationId: string, input: UpdateWorldStateInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: UPDATE_WORLD_STATE,
            variables: { conversationId, input },
          });

          set((state) => {
            state.worldState = data.updateWorldState;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to update world state'));
          });
        }
      },

      initializeFromTemplate: async (conversationId: string, templateId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: INITIALIZE_FROM_TEMPLATE,
            variables: { conversationId, templateId },
          });

          set((state) => {
            state.worldState = data.initializeFromTemplate;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to initialize from template'));
          });
        }
      },

      fetchTemplates: async (genre?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: STORY_TEMPLATES_QUERY,
            variables: { genre },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.templates = data.storyTemplates;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch templates'));
          });
        }
      },

      fetchActiveScene: async (conversationId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: ACTIVE_SCENE_QUERY,
            variables: { conversationId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.activeScene = data.activeScene;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch active scene'));
          });
        }
      },

      changeScene: async (input: ChangeSceneInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: CHANGE_SCENE,
            variables: { input },
          });

          set((state) => {
            state.activeScene = data.changeScene;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to change scene'));
          });
        }
      },

      fetchKnowledgeEntries: async (conversationId: string, characterId?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: KNOWLEDGE_ENTRIES_QUERY,
            variables: { conversationId, characterId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.knowledgeEntries = data.knowledgeEntries;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch knowledge entries'));
          });
        }
      },

      fetchAgentModelConfig: async (conversationId: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: AGENT_MODEL_CONFIG_QUERY,
            variables: { conversationId },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            state.agentModelConfig = data.agentModelConfig;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch agent model config'));
          });
        }
      },

      updateAgentModelConfig: async (conversationId: string, input: AgentModelConfigInput) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: UPDATE_AGENT_MODEL_CONFIG,
            variables: { conversationId, input },
          });

          set((state) => {
            state.agentModelConfig = data.updateAgentModelConfig;
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to update agent model config'));
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
          state.worldState = null;
          state.templates = [];
          state.activeScene = null;
          state.knowledgeEntries = [];
          state.agentModelConfig = null;
          state.isLoading = false;
          state.error = null;
        });
      },
    })),
  ),
);
