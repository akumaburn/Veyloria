/**
 * Conversation Store
 *
 * Manages conversation state including CRUD operations, pagination, and selection.
 * Uses centralized GraphQL operations from @graphql module.
 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, findAndUpdate, findAndRemove, handleStoreError } from '@/lib/storeHelpers';
import {
  CONVERSATIONS_QUERY,
  CONVERSATION_QUERY,
  CREATE_CONVERSATION_MUTATION,
  UPDATE_CONVERSATION_MUTATION,
  DELETE_CONVERSATION_MUTATION,
  PIN_CONVERSATION_MUTATION,
  ARCHIVE_CONVERSATION_MUTATION,
} from '@/graphql/operations';
import type { Conversation } from '@/graphql/types';

const MAX_CONVERSATIONS = 1000;
function trimConversations(conversations: Conversation[]): Conversation[] {
  return conversations.length > MAX_CONVERSATIONS
    ? conversations.slice(0, MAX_CONVERSATIONS)
    : conversations;
}

/**
 * Orders list fetches against delete settlement. A plain timestamp is too
 * coarse: in a test, and on a fast local network, both events can land in the
 * same millisecond.
 */
let deletionClock = 0;

type Deletion = {
  /** The clock value at settlement, or null while the delete is in flight. */
  settledSeq: number | null;
};

/**
 * True when a list result may still contain a conversation that is being
 * deleted.
 *
 * A list fetch that overlaps a delete can reach the database before the delete
 * commits and return the row. Applying that result puts the conversation back
 * on screen, where it stays until a full reload. A fetch that began before the
 * delete settled is therefore suspect, whatever the delete later answers. A
 * fetch that begins after settlement reports server truth and is accepted.
 */
function overlapsDeletion(
  deletion: Deletion | undefined,
  fetchStartedSeq: number,
): boolean {
  if (!deletion) return false;
  return deletion.settledSeq === null || fetchStartedSeq < deletion.settledSeq;
}

interface ConversationState {
  conversations: Conversation[];
  selectedConversationId: string | null;
  isLoading: boolean;
  error: string | null;
  hasMore: boolean;
  offset: number;
  hasLoaded: boolean;
  /** Deletes issued this session, kept to reject list results that race them. */
  deletions: Record<string, Deletion>;

  // Actions
  fetchConversations: (offset?: number) => Promise<void>;
  fetchConversation: (id: string) => Promise<Conversation | null>;
  createConversation: (title: string, characterId?: string) => Promise<Conversation | null>;
  updateConversation: (id: string, updates: Partial<Conversation>) => Promise<void>;
  pinConversation: (id: string) => Promise<void>;
  archiveConversation: (id: string) => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  setSelectedConversation: (id: string | null) => void;
  clearError: () => void;
  reset: () => void;
}

export const useConversationStore = create<ConversationState>()(
  devtoolsInDev(
    persist(
      immer((set, get) => ({
        conversations: [],
        selectedConversationId: null,
        isLoading: false,
        error: null,
        hasMore: true,
        offset: 0,
        hasLoaded: false,
        deletions: {},

        fetchConversations: async (offset = 0) => {
          const startedSeq = deletionClock;
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.query({
              query: CONVERSATIONS_QUERY,
              variables: { offset, limit: 20 },
              fetchPolicy: 'network-only',
            });

            set((state) => {
              const page = data.conversations.items as Conversation[];
              const items = page.filter(
                (conversation) => !overlapsDeletion(state.deletions[conversation.id], startedSeq),
              );
              if (offset === 0) {
                state.conversations = items;
              } else {
                state.conversations.push(...items);
                state.conversations = trimConversations(state.conversations);
              }
              state.hasMore = data.conversations.hasMore;
              state.offset = offset + page.length;
              state.hasLoaded = true;
              clearLoadingAndError(state);
            });
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Failed to fetch conversations'));
            });
          }
        },

        fetchConversation: async (id: string) => {
          const startedSeq = deletionClock;
          try {
            const { data } = await apolloClient.query({
              query: CONVERSATION_QUERY,
              variables: { id },
              fetchPolicy: 'network-only',
            });

            if (data.conversation && !overlapsDeletion(get().deletions[id], startedSeq)) {
              set((state) => {
                const updated = findAndUpdate(state.conversations, id, data.conversation);
                if (!updated) {
                  state.conversations.unshift(data.conversation);
                }
              });
            }

            return data.conversation;
          } catch (error) {
            console.error('Failed to fetch conversation:', error);
            return null;
          }
        },

        createConversation: async (title: string, characterId?: string) => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: CREATE_CONVERSATION_MUTATION,
              variables: {
                input: {
                  title,
                  characterId,
                },
              },
            });

            if (!data?.createConversation) {
              throw new Error('Failed to create conversation: empty response from server');
            }

            const newConversation = data.createConversation;

            set((state) => {
              state.conversations.unshift(newConversation);
              state.selectedConversationId = newConversation.id;
              clearLoadingAndError(state);
            });

            return newConversation;
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Failed to create conversation'));
            });
            return null;
          }
        },

        updateConversation: async (id: string, updates: Partial<Conversation>) => {
          // Handle pin/archive through dedicated toggle functions only when state change is needed
          if ('isPinned' in updates) {
            const conversation = get().conversations.find((c) => c.id === id);
            if (conversation && conversation.isPinned !== updates.isPinned) {
              await get().pinConversation(id);
            }
            return;
          }
          if ('isArchived' in updates) {
            const conversation = get().conversations.find((c) => c.id === id);
            if (conversation && conversation.isArchived !== updates.isArchived) {
              await get().archiveConversation(id);
            }
            return;
          }

          // Optimistic update
          set((state) => {
            findAndUpdate(state.conversations, id, updates);
          });

          try {
            await apolloClient.mutate({
              mutation: UPDATE_CONVERSATION_MUTATION,
              variables: {
                id,
                input: {
                  title: updates.title,
                  description: updates.description,
                  category: updates.category,
                  tags: updates.tags,
                },
              },
            });
          } catch (error) {
            // Revert on error
            await get().fetchConversation(id);
            throw error;
          }
        },

        pinConversation: async (id: string) => {
          const conversation = get().conversations.find((c) => c.id === id);
          const wasPinned = conversation?.isPinned;

          // Optimistic update
          set((state) => {
            const conv = state.conversations.find((c) => c.id === id);
            if (conv) {
              conv.isPinned = !conv.isPinned;
            }
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: PIN_CONVERSATION_MUTATION,
              variables: { id },
            });

            // Update with server response
            if (data?.pinConversation) {
              set((state) => {
                findAndUpdate(state.conversations, id, {
                  isPinned: data.pinConversation.isPinned,
                  updatedAt: data.pinConversation.updatedAt,
                } as Partial<Conversation>);
              });
            }
          } catch (error) {
            // Revert on error
            set((state) => {
              findAndUpdate(state.conversations, id, { isPinned: wasPinned ?? false } as Partial<Conversation>);
            });
            throw error;
          }
        },

        archiveConversation: async (id: string) => {
          const conversation = get().conversations.find((c) => c.id === id);
          const wasArchived = conversation?.isArchived;

          // Optimistic update
          set((state) => {
            const conv = state.conversations.find((c) => c.id === id);
            if (conv) {
              conv.isArchived = !conv.isArchived;
            }
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: ARCHIVE_CONVERSATION_MUTATION,
              variables: { id },
            });

            // Update with server response
            if (data?.archiveConversation) {
              set((state) => {
                findAndUpdate(state.conversations, id, {
                  isArchived: data.archiveConversation.isArchived,
                  updatedAt: data.archiveConversation.updatedAt,
                } as Partial<Conversation>);
              });
            }
          } catch (error) {
            // Revert on error
            set((state) => {
              findAndUpdate(state.conversations, id, { isArchived: wasArchived ?? false } as Partial<Conversation>);
            });
            throw error;
          }
        },

        deleteConversation: async (id: string) => {
          // Optimistic delete — snapshot both the conversation and its index so
          // we can restore it in place on error (push() alone moves the item to
          // the bottom of the list, which users perceive as a phantom reorder).
          const existing = get().conversations;
          const originalIndex = existing.findIndex((c) => c.id === id);
          const conversation = originalIndex >= 0 ? existing[originalIndex] : undefined;
          const previousSelection = get().selectedConversationId;

          set((state) => {
            state.deletions[id] = { settledSeq: null };
            findAndRemove(state.conversations, id);
            if (state.selectedConversationId === id) {
              state.selectedConversationId = null;
            }
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: DELETE_CONVERSATION_MUTATION,
              variables: { id },
              errorPolicy: 'none',
            });
            if (data?.deleteConversation !== true) throw new Error('Conversation could not be deleted');
          } catch (error) {
            // Revert on error — restore at the original index and re-select if
            // we had deselected it. The deletion must settle either way, or a
            // failed delete would hide the row from later list fetches.
            deletionClock += 1;
            const settledSeq = deletionClock;
            set((state) => {
              state.deletions[id] = { settledSeq };
              if (!conversation) return;
              const insertAt = Math.min(originalIndex, state.conversations.length);
              if (!state.conversations.some(item => item.id === id)) {
                state.conversations.splice(insertAt, 0, conversation);
              }
              if (previousSelection === id && state.selectedConversationId === null) {
                state.selectedConversationId = id;
              }
            });
            throw error;
          }

          deletionClock += 1;
          const settledSeq = deletionClock;
          set((state) => {
            state.deletions[id] = { settledSeq };
          });

          // The server has committed. A local cache error must not restore a deleted row.
          try {
            apolloClient.cache.batch({ update(cache) {
              cache.modify({ fields: {
                conversations(existingConversations: any, { readField }) {
                  if (!existingConversations?.items) return existingConversations;
                  // Read IDs before eviction makes the normalized references unreadable.
                  const items = existingConversations.items.filter((ref: any) => readField('id', ref) !== id);
                  const removed = existingConversations.items.length - items.length;
                  return { ...existingConversations, items,
                    totalCount: Math.max(0, (existingConversations.totalCount || 0) - removed) };
                },
              } });
              cache.evict({ id: cache.identify({ __typename: 'Conversation', id }) });
              cache.evict({ id: 'ROOT_QUERY', fieldName: 'conversation', args: { id } });
            } });
            apolloClient.cache.gc();
          } catch (error) {
            console.warn('Conversation deleted, but local cache cleanup failed:', error);
          }
        },

        setSelectedConversation: (id: string | null) => {
          set((state) => {
            state.selectedConversationId = id;
          });
        },

        clearError: () => {
          set((state) => {
            state.error = null;
          });
        },

        reset: () => {
          set((state) => {
            state.conversations = [];
            state.selectedConversationId = null;
            state.isLoading = false;
            state.error = null;
            state.hasMore = true;
            state.offset = 0;
            state.hasLoaded = false;
            state.deletions = {};
          });
        },
      })),
      {
        name: 'conversation-storage',
        partialize: (state) => ({
          selectedConversationId: state.selectedConversationId,
        }),
      },
    ),
  ),
);
