/**
 * Message Store
 *
 * Manages message state including CRUD operations, streaming, and pagination.
 * Uses centralized GraphQL operations from @graphql module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev } from '@/lib/storeHelpers';
import { Base64 } from 'js-base64';
import {
  MESSAGES_QUERY,
  SEND_MESSAGE_MUTATION,
  EDIT_MESSAGE_MUTATION,
  DELETE_MESSAGE_MUTATION,
  REGENERATE_MESSAGE_MUTATION,
  STOP_GENERATION_MUTATION,
} from '@/graphql/operations';
import type { StoreMessage } from '@/graphql/types';
import { setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';

// Unique ID generator for optimistic updates - uses crypto.randomUUID() for guaranteed uniqueness
const generateTempId = (): string => {
  // crypto.randomUUID() provides guaranteed uniqueness across tabs and sessions
  return `temp-${crypto.randomUUID()}`;
};

// Per-conversation message cap. Subscription-driven appends can otherwise grow
// the array without bound during long-lived chats. Only applied on append paths;
// user-driven pagination (prepend of older messages) is respected.
const MAX_MESSAGES_PER_CONVERSATION = 1000;
const capAppended = (messages: Message[]): Message[] =>
  messages.length > MAX_MESSAGES_PER_CONVERSATION
    ? messages.slice(-MAX_MESSAGES_PER_CONVERSATION)
    : messages;

// Re-export for backward compatibility
type Message = StoreMessage;

interface StreamingMessage {
  conversationId: string;
  content: string;
  role: 'assistant';
  isComplete: boolean;
}

interface MessageState {
  messages: Map<string, Message[]>; // conversationId -> messages
  streamingMessage: StreamingMessage | null;
  isLoading: boolean;
  isSending: boolean;
  error: string | null;
  hasMore: Map<string, boolean>; // conversationId -> hasMore
  cursors: Map<string, string | null>; // conversationId -> cursor for pagination
  typingUsers: Map<string, string[]>; // conversationId -> userIds

  // Actions
  fetchMessages: (conversationId: string, before?: string) => Promise<void>;
  sendMessage: (conversationId: string, content: string, attachments?: File[], retryOf?: string) => Promise<Message | null>;
  editMessage: (messageId: string, content: string, conversationId?: string) => Promise<void>;
  deleteMessage: (messageId: string, conversationId?: string) => Promise<void>;
  regenerateMessage: (messageId: string, conversationId?: string) => Promise<void>;
  stopGeneration: (conversationId?: string) => void;

  // Streaming
  setStreamingMessage: (message: StreamingMessage | null) => void;
  appendToStreamingMessage: (content: string) => void;
  finalizeStreamingMessage: (message: Message) => void;

  // Typing indicators
  setTypingUsers: (conversationId: string, userIds: string[]) => void;

  // Utilities
  clearMessages: (conversationId: string) => void;
  clearError: () => void;
  reset: () => void;
}

// Note: GraphQL subscriptions are available in @/graphql/operations:
// - MESSAGE_STREAM_SUBSCRIPTION
// - TYPING_INDICATOR_SUBSCRIPTION

/**
 * Safely decode Base64 content with error handling.
 * Returns decoded content or an error message if decoding fails.
 */
function safeBase64Decode(encodedContent: string): string {
  try {
    return Base64.decode(encodedContent);
  } catch (error) {
    console.error('Failed to decode Base64 message content:', error);
    // Return a user-friendly error message with a preview
    const preview = encodedContent.length > 50
      ? `${encodedContent.slice(0, 50)}...`
      : encodedContent;
    return `[Decoding Error: Unable to decode message content. Preview: ${preview}]`;
  }
}

export const useMessageStore = create<MessageState>()(
  devtoolsInDev(
    immer((set, get) => ({
      messages: new Map(),
      streamingMessage: null,
      isLoading: false,
      isSending: false,
      error: null,
      hasMore: new Map(),
      cursors: new Map(),
      typingUsers: new Map(),

      fetchMessages: async (conversationId: string, before?: string) => {
        set((state) => {
          setLoading(state);
        });

        try {
          const { data } = await apolloClient.query({
            query: MESSAGES_QUERY,
            variables: { conversationId, limit: 50, before },
            fetchPolicy: 'network-only',
          });

          set((state) => {
            const existingMessages = state.messages.get(conversationId) || [];
            const newMessages = data.messages.items.map((msg: any) => ({
              ...msg,
              conversationId: msg.conversation?.id || conversationId,
              content: msg.contentBase64 && msg.isBase64Encoded
                ? safeBase64Decode(msg.contentBase64)
                : msg.content,
            }));

            if (!before) {
              // First load - replace all messages, but keep optimistic
              // (sending/error) messages that haven't been persisted yet.
              // Replacing outright would make a send-then-refetch flicker the
              // in-flight bubble away, then re-append it at the END via the
              // mutation's success path (a phantom reorder).
              const serverIds = new Set(newMessages.map((m: any) => m.id));
              for (const m of existingMessages) {
                if ((m.status === 'sending' || m.status === 'error') && !serverIds.has(m.id)) {
                  newMessages.push(m);
                }
              }
              state.messages.set(conversationId, newMessages);
            } else {
              // Load more - prepend older messages
              state.messages.set(conversationId, [...newMessages, ...existingMessages]);
            }

            state.hasMore.set(conversationId, data.messages.hasMore);
            state.cursors.set(conversationId, data.messages.cursor);
            clearLoadingAndError(state);
          });
        } catch (error) {
          set((state) => {
            setError(state, handleStoreError(error, 'Failed to fetch messages'));
          });
        }
      },

      sendMessage: async (conversationId: string, content: string, _attachments?: File[], retryOf?: string) => {
        set((state) => {
          state.isSending = true;
          state.error = null;
        });

        // Create optimistic message with unique ID to prevent collisions
        const tempId = generateTempId();
        const now = new Date().toISOString();
        const optimisticMessage: Message = {
          id: tempId,
          conversationId,
          content,
          contentBase64: Base64.encode(content),
          role: 'user',
          createdAt: now,
          updatedAt: now,
          editCount: 0,
          isEdited: false,
          isDeleted: false,
          status: 'sending',
        };

        set((state) => {
          const messages = state.messages.get(conversationId) || [];
          if (retryOf) {
            // Retry replaces the failed message in place — a fresh optimistic
            // bubble would otherwise pile up alongside the original 'error'
            // message on every retry click.
            const index = messages.findIndex((m: Message) => m.id === retryOf);
            if (index >= 0) {
              messages[index] = optimisticMessage;
            } else {
              messages.push(optimisticMessage);
            }
          } else {
            messages.push(optimisticMessage);
          }
          state.messages.set(conversationId, capAppended([...messages]));
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: SEND_MESSAGE_MUTATION,
            variables: {
              input: {
                conversationId,
                content,
              }
            },
          });

          if (!data?.sendMessage) {
            throw new Error('Failed to send message: empty response from server');
          }

          const sentMessage = {
            ...data.sendMessage,
            conversationId: data.sendMessage.conversation?.id || conversationId,
            content: data.sendMessage.contentBase64 && data.sendMessage.isBase64Encoded
              ? safeBase64Decode(data.sendMessage.contentBase64)
              : data.sendMessage.content,
            status: 'sent' as const,
          };

          set((state) => {
            const messages = state.messages.get(conversationId) || [];
            const index = messages.findIndex((m: Message) => m.id === tempId);
            if (index >= 0) {
              messages[index] = sentMessage;
            } else {
              messages.push(sentMessage);
            }
            state.messages.set(conversationId, [...messages]);
            state.isSending = false;
          });

          return sentMessage;
        } catch (error) {
          set((state) => {
            const messages = state.messages.get(conversationId) || [];
            const index = messages.findIndex((m: Message) => m.id === tempId);
            if (index >= 0) {
              messages[index] = {
                ...messages[index],
                status: 'error',
                error: handleStoreError(error, 'Failed to send'),
              };
              state.messages.set(conversationId, [...messages]);
            }
            state.error = handleStoreError(error, 'Failed to send message');
            state.isSending = false;
          });
          return null;
        }
      },

      editMessage: async (messageId: string, content: string, passedConversationId?: string) => {
        // Find the message and update optimistically (if in local state)
        let conversationId: string | null = passedConversationId || null;
        let originalContent: string | null = null;

        set((state) => {
          for (const [convId, messages] of state.messages) {
            const index = messages.findIndex((m: Message) => m.id === messageId);
            if (index >= 0) {
              conversationId = convId;
              originalContent = messages[index].content;
              messages[index].content = content;
              messages[index].contentBase64 = Base64.encode(content);
              state.messages.set(convId, [...messages]);
              break;
            }
          }
        });

        try {
          const { data } = await apolloClient.mutate({
            mutation: EDIT_MESSAGE_MUTATION,
            variables: { id: messageId, content },
          });

          // Update local state if we have it
          if (conversationId && data?.editMessage) {
            set((state) => {
              const messages = state.messages.get(conversationId!) || [];
              const index = messages.findIndex((m: Message) => m.id === messageId);
              if (index >= 0) {
                messages[index] = {
                  ...messages[index],
                  ...data.editMessage,
                  content: data.editMessage.contentBase64
                    ? safeBase64Decode(data.editMessage.contentBase64)
                    : data.editMessage.content,
                };
                state.messages.set(conversationId!, [...messages]);
              }
            });
          }
        } catch (error) {
          // Revert on error if we had local state
          if (conversationId && originalContent !== null) {
            set((state) => {
              const messages = state.messages.get(conversationId!) || [];
              const index = messages.findIndex((m: Message) => m.id === messageId);
              if (index >= 0) {
                messages[index].content = originalContent!;
                messages[index].contentBase64 = Base64.encode(originalContent!);
                state.messages.set(conversationId!, [...messages]);
              }
            });
          }
          throw error;
        }
      },

      deleteMessage: async (messageId: string, passedConversationId?: string) => {
        // Find and remove optimistically (if in local state)
        let conversationId: string | null = passedConversationId || null;
        let deletedMessage: Message | null = null;
        let deletedIndex: number = -1;

        set((state) => {
          for (const [convId, messages] of state.messages) {
            const index = messages.findIndex((m: Message) => m.id === messageId);
            if (index >= 0) {
              conversationId = convId;
              deletedMessage = messages[index];
              deletedIndex = index;
              messages.splice(index, 1);
              state.messages.set(convId, [...messages]);
              break;
            }
          }
        });

        try {
          await apolloClient.mutate({
            mutation: DELETE_MESSAGE_MUTATION,
            variables: { id: messageId },
          });
        } catch (error) {
          // Revert on error if we had local state
          if (conversationId && deletedMessage && deletedIndex >= 0) {
            set((state) => {
              const messages = state.messages.get(conversationId!) || [];
              messages.splice(deletedIndex, 0, deletedMessage!);
              state.messages.set(conversationId!, [...messages]);
            });
          }
          throw error;
        }
      },

      regenerateMessage: async (messageId: string, passedConversationId?: string) => {
        // Find the conversation containing this message (if in local state)
        let conversationId: string | null = passedConversationId || null;

        if (!conversationId) {
          for (const [convId, messages] of get().messages) {
            if (messages.find((m: Message) => m.id === messageId)) {
              conversationId = convId;
              break;
            }
          }
        }

        // Update local state if we have it
        if (conversationId) {
          set((state) => {
            state.error = null;
            const messages = state.messages.get(conversationId!) || [];
            const index = messages.findIndex((m: Message) => m.id === messageId);
            if (index >= 0) {
              messages[index].status = 'generating';
              state.messages.set(conversationId!, [...messages]);
            }
          });
        }

        try {
          const { data } = await apolloClient.mutate({
            mutation: REGENERATE_MESSAGE_MUTATION,
            variables: { id: messageId },
          });

          // Update local state if we have it
          if (conversationId && data?.regenerateMessage) {
            set((state) => {
              const messages = state.messages.get(conversationId!) || [];
              const index = messages.findIndex((m: Message) => m.id === messageId);
              if (index >= 0) {
                messages[index] = {
                  ...messages[index],
                  ...data.regenerateMessage,
                  conversationId: conversationId!,
                  content: data.regenerateMessage.contentBase64
                    ? safeBase64Decode(data.regenerateMessage.contentBase64)
                    : data.regenerateMessage.content,
                  status: 'sent',
                };
                state.messages.set(conversationId!, [...messages]);
              }
            });
          }
        } catch (error) {
          // Update local state if we have it
          if (conversationId) {
            set((state) => {
              const messages = state.messages.get(conversationId!) || [];
              const index = messages.findIndex((m: Message) => m.id === messageId);
              if (index >= 0) {
                messages[index].status = 'error';
                messages[index].error = handleStoreError(error, 'Regeneration failed');
                state.messages.set(conversationId!, [...messages]);
              }
            });
          }
          set((state) => {
            state.error = handleStoreError(error, 'Failed to regenerate');
          });
          throw error;
        }
      },

      stopGeneration: async (passedConversationId?: string) => {
        const streamingMessage = get().streamingMessage;
        const conversationId = passedConversationId || streamingMessage?.conversationId;

        if (!conversationId) {
          console.error('No conversation ID to stop generation');
          return;
        }

        try {
          const result = await apolloClient.mutate({
            mutation: STOP_GENERATION_MUTATION,
            variables: { conversationId },
          });

          if (result.data?.stopGeneration) {
            set((state) => {
              state.streamingMessage = null;
              // Update any generating messages to sent status
              const messages = state.messages.get(conversationId);
              if (messages) {
                const updatedMessages = messages.map((msg: Message) =>
                  msg.status === 'generating'
                    ? { ...msg, status: 'sent' as const }
                    : msg
                );
                state.messages.set(conversationId, updatedMessages);
              }
            });
          }
        } catch (error) {
          console.error('Failed to stop generation:', error);
          set((state) => {
            state.streamingMessage = null;
            state.error = handleStoreError(error, 'Failed to stop generation');
          });
        }
      },

      setStreamingMessage: (message: StreamingMessage | null) => {
        set((state) => {
          state.streamingMessage = message;
        });
      },

      appendToStreamingMessage: (content: string) => {
        set((state) => {
          if (state.streamingMessage) {
            state.streamingMessage.content += content;
          }
        });
      },

      finalizeStreamingMessage: (message: Message) => {
        set((state) => {
          const messages = state.messages.get(message.conversationId) || [];
          // Prevent duplicate messages - check if message already exists by ID
          const existingIndex = messages.findIndex((m: Message) => m.id === message.id);
          if (existingIndex === -1) {
            // Message doesn't exist, add it
            messages.push(message);
            state.messages.set(message.conversationId, capAppended([...messages]));
          } else {
            // Message already exists, update it instead of adding duplicate
            messages[existingIndex] = message;
            state.messages.set(message.conversationId, [...messages]);
          }
          state.streamingMessage = null;
        });

        // Notify the app that a new message arrived (plays the notification sound
        // when enabled). Do not fire for messages sent by the current user.
        if (message.role !== 'user' && typeof window !== 'undefined') {
          try {
            window.dispatchEvent(new CustomEvent('newMessage', { detail: message }));
          } catch (e) {
            // CustomEvent construction can throw in very old environments
            console.warn('Failed to dispatch newMessage event:', e);
          }
        }
      },

      setTypingUsers: (conversationId: string, userIds: string[]) => {
        set((state) => {
          if (userIds.length === 0) {
            state.typingUsers.delete(conversationId);
          } else {
            state.typingUsers.set(conversationId, userIds);
          }
        });
      },

      clearMessages: (conversationId: string) => {
        set((state) => {
          state.messages.delete(conversationId);
          state.hasMore.delete(conversationId);
          state.cursors.delete(conversationId);
          state.typingUsers.delete(conversationId);
        });
      },

      clearError: () => {
        set((state) => {
          state.error = null;
        });
      },

      reset: () => {
        set((state) => {
          state.messages.clear();
          state.streamingMessage = null;
          state.isLoading = false;
          state.isSending = false;
          state.error = null;
          state.hasMore.clear();
          state.cursors.clear();
          state.typingUsers.clear();
        });
      },
    })),
  ),
);