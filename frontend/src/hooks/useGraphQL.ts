/**
 * GraphQL Hooks
 *
 * React hooks for GraphQL operations using Apollo Client.
 * Uses centralized GraphQL operations from @graphql module.
 */

import { useQuery, useMutation, ApolloError } from '@apollo/client';
import { useCallback, useEffect, useRef, useState, useMemo } from 'react';
// Note: useRef is still used by useTypingIndicator below
import { useAuthStore } from '@/stores/authStore';
import { useWebSocketSubscription } from '@/lib/websocket-client';
import { toTimestamp as toTs } from '@/lib/utils';
import {
  // User operations
  ME_QUERY,
  LOGIN_MUTATION,
  REGISTER_MUTATION,
  LOGOUT_MUTATION,

  // Conversation operations
  CONVERSATIONS_QUERY,
  CONVERSATION_QUERY,
  CREATE_CONVERSATION_MUTATION,
  SWITCH_CHARACTER_MUTATION,

  // Message operations
  MESSAGES_QUERY,
  SEND_MESSAGE_MUTATION,
  SEND_GROUP_MESSAGE_MUTATION,
  EDIT_MESSAGE_MUTATION,
  STOP_GENERATION_MUTATION,
  SEND_TYPING_INDICATOR_MUTATION,

  // Character operations
  CHARACTERS_QUERY,
  CREATE_CHARACTER_MUTATION,
  GENERATE_CHARACTER_MUTATION,

  // Settings operations
  GET_SETTINGS_QUERY,
  UPDATE_SETTINGS_MUTATION,

  // Data management operations
  EXPORT_DATA_QUERY,
  IMPORT_DATA_MUTATION,
  DELETE_ALL_DATA_MUTATION,

  // Streaming recovery
  PENDING_STREAMING_MESSAGE_QUERY,

  // Subscriptions

  // Group chat operations
  CREATE_GROUP_CONVERSATION_MUTATION,
  ADD_CHARACTER_TO_GROUP_MUTATION,
  REMOVE_CHARACTER_FROM_GROUP_MUTATION,
  UPDATE_GROUP_SETTINGS_MUTATION,
  TRIGGER_NEXT_CHARACTER_RESPONSE_MUTATION,
  REQUEST_INTERJECTION_MUTATION,
  CANCEL_INTERJECTION_MUTATION,
  APPROVE_INTERJECTION_MUTATION,
  REJECT_INTERJECTION_MUTATION,
  PAUSE_GROUP_CONVERSATION_MUTATION,
  RESUME_GROUP_CONVERSATION_MUTATION,

  // Multi-user invite operations
  MY_INVITES_QUERY,
  CONVERSATION_PARTICIPANTS_QUERY,
  SEARCH_USERS_QUERY,
  INVITE_USER_TO_CONVERSATION_MUTATION,
  RESPOND_TO_INVITE_MUTATION,
  CANCEL_INVITE_MUTATION,
  UPDATE_PARTICIPANT_MUTATION,
  REMOVE_PARTICIPANT_MUTATION,
  LEAVE_CONVERSATION_MUTATION,
  TRANSFER_OWNERSHIP_MUTATION,
  MARK_CONVERSATION_READ_MUTATION,
} from '@/graphql/operations';

// ============================================
// ERROR HANDLING
// ============================================

export const useErrorHandler = () => {
  const handleError = useCallback((error: ApolloError) => {
    console.error('GraphQL Error:', error);

    // Handle different error types
    if (error.networkError) {
      // Network error
      console.error('Network error:', error.networkError);
    }

    if (error.graphQLErrors?.length > 0) {
      // GraphQL errors
      error.graphQLErrors.forEach((err) => {
        console.error('GraphQL error:', err.message);
        // Note: auth errors (UNAUTHENTICATED etc.) are handled centrally by the
        // Apollo error link in apollo-client.ts, which attempts a token refresh
        // before redirecting. A full-page redirect here would bypass that flow.
      });
    }
  }, []);

  return { handleError };
};

// ============================================
// AUTHENTICATION HOOKS
// ============================================

export const useLogin = () => {
  const { setAuth } = useAuthStore();

  const [login, { loading, error }] = useMutation(LOGIN_MUTATION, {
    onCompleted: (data) => {
      const { user, accessToken, refreshToken } = data.login;
      setAuth(user, accessToken, refreshToken);
    },
  });

  return {
    login: (username: string, password: string) =>
      login({ variables: { username, password } }),
    loading,
    error,
  };
};

export const useRegister = () => {
  const { setAuth } = useAuthStore();

  const [register, { loading, error }] = useMutation(REGISTER_MUTATION, {
    onCompleted: (data) => {
      const { user, accessToken, refreshToken } = data.register;
      setAuth(user, accessToken, refreshToken);
    },
  });

  return {
    register: (username: string, email: string, password: string) =>
      register({ variables: { username, email, password } }),
    loading,
    error,
  };
};

export const useLogout = () => {
  const { logout: clearAuth } = useAuthStore();

  const [logout, { loading }] = useMutation(LOGOUT_MUTATION, {
    onCompleted: () => {
      clearAuth();
    },
  });

  return { logout, loading };
};

export const useMe = () => {
  const { data, loading, error, refetch } = useQuery(ME_QUERY, {
    fetchPolicy: 'cache-and-network',
  });

  return {
    user: data?.me,
    loading,
    error,
    refetch,
  };
};

// ============================================
// CONVERSATION HOOKS
// ============================================

export const useConversations = (filter?: any, limit = 20, offset = 0) => {
  const { data, loading, error, fetchMore, refetch } = useQuery(CONVERSATIONS_QUERY, {
    variables: { filter, limit, offset },
    fetchPolicy: 'cache-and-network',
  });

  const loadMore = useCallback(() => {
    if (!data?.conversations?.hasMore) return;

    // Use cache merge policy instead of deprecated updateQuery
    return fetchMore({
      variables: {
        offset: data.conversations.items.length,
      },
    });
  }, [data, fetchMore]);

  return {
    conversations: data?.conversations?.items || [],
    totalCount: data?.conversations?.totalCount || 0,
    hasMore: data?.conversations?.hasMore || false,
    loading,
    error,
    loadMore,
    refetch,
  };
};

export const useConversation = (id: string) => {
  const { data, loading, error, refetch } = useQuery(CONVERSATION_QUERY, {
    variables: { id },
    skip: !id,
  });

  return {
    conversation: data?.conversation,
    loading,
    error,
    refetch,
  };
};

export const useCreateConversation = () => {

  const [createConversation, { loading, error }] = useMutation(CREATE_CONVERSATION_MUTATION, {
    update: (cache, { data }) => {
      // Update conversations list cache
      const existing = cache.readQuery({
        query: CONVERSATIONS_QUERY,
        variables: { limit: 20, offset: 0 },
      });

      if (existing && data?.createConversation) {
        cache.writeQuery({
          query: CONVERSATIONS_QUERY,
          variables: { limit: 20, offset: 0 },
          data: {
            conversations: {
              ...(existing as any).conversations,
              items: [data.createConversation, ...(existing as any).conversations.items],
              totalCount: (existing as any).conversations.totalCount + 1,
            },
          },
        });
      }
    },
  });

  return {
    createConversation: (input: any) =>
      createConversation({ variables: { input } }),
    loading,
    error,
  };
};

// ============================================
// MESSAGE HOOKS
// ============================================

export const useMessages = (conversationId: string, limit = 50) => {
  const { data, loading, error, fetchMore, refetch } = useQuery(MESSAGES_QUERY, {
    variables: { conversationId, limit },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  const loadMore = useCallback(() => {
    if (!data?.messages?.hasMore) return;

    // Use cache merge policy instead of deprecated updateQuery
    return fetchMore({
      variables: {
        before: data.messages.cursor,
      },
    });
  }, [data, fetchMore]);

  // Order messages by the backend's monotonic `seq` (exact insertion order),
  // oldest first. The backend returns newest-first (seq DESC); reversing by seq
  // gives chronological display WITHOUT relying on createdAt (second-resolution
  // DATETIME) or the random messageUUID tiebreaker, both of which misordered
  // messages created within the same second. Falls back to createdAt only if
  // seq is somehow absent (legacy data).
  const messagesInChronologicalOrder = useMemo(() => {
    const items = (data?.messages?.items || []) as any[];
    if (items.length === 0) return [];
    const hasSeq = items.some(m => m.seq != null);
    if (hasSeq) {
      // Missing seq (e.g. an optimistic just-sent message) sorts as newest.
      const seqOrMax = (m: any) => (m.seq == null ? Number.MAX_SAFE_INTEGER : Number(m.seq));
      return [...items].sort((a, b) => seqOrMax(a) - seqOrMax(b));
    }
    // Legacy fallback for messages without seq.
    const ts = new Map<string, number>();
    items.forEach((msg: any) => ts.set(msg.id, toTs(msg.createdAt)));
    return [...items].sort((a: any, b: any) => {
      const diff = (ts.get(a.id) || 0) - (ts.get(b.id) || 0);
      if (diff !== 0) return diff;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
  }, [data?.messages?.items]);

  return {
    messages: messagesInChronologicalOrder,
    hasMore: data?.messages?.hasMore || false,
    cursor: data?.messages?.cursor,
    loading,
    error,
    loadMore,
    refetch,
  };
};

export const useSendMessage = () => {

  const [sendMessage, { loading, error }] = useMutation(SEND_MESSAGE_MUTATION, {
    update: (cache, { data }) => {
      // Add the user message to the messages cache immediately
      if (!data?.sendMessage) return;

      const newMessage = data.sendMessage;
      const conversationId = newMessage.conversation?.id;
      if (!conversationId) return;

      try {
        // Read existing messages from cache
        const existing = cache.readQuery({
          query: MESSAGES_QUERY,
          variables: { conversationId, limit: 50 },
        });

        if (existing) {
          // Check if message already exists (avoid duplicates)
          const existingItems = (existing as any).messages?.items || [];
          const messageExists = existingItems.some((m: any) => m.id === newMessage.id);

          if (!messageExists) {
            // Add new message to the end of the list
            cache.writeQuery({
              query: MESSAGES_QUERY,
              variables: { conversationId, limit: 50 },
              data: {
                messages: {
                  ...(existing as any).messages,
                  items: [...existingItems, newMessage],
                },
              },
            });
          }
        }
      } catch (e) {
        // Cache might not have been populated yet for this conversation
        // This is fine - the message will be fetched on next query
        console.debug('[useSendMessage] Could not update cache:', e);
      }
    },
  });

  return {
    sendMessage: (input: any) =>
      sendMessage({ variables: { input } }),
    loading,
    error,
  };
};

export const useSendGroupMessage = () => {

  const [sendGroupMessage, { loading, error }] = useMutation(SEND_GROUP_MESSAGE_MUTATION, {
    update: (cache, { data }) => {
      // Add the user message to the messages cache immediately
      if (!data?.sendGroupMessage) return;

      const newMessage = data.sendGroupMessage;
      const conversationId = newMessage.conversation?.id;
      if (!conversationId) return;

      try {
        // Read existing messages from cache
        const existing = cache.readQuery({
          query: MESSAGES_QUERY,
          variables: { conversationId, limit: 50 },
        });

        if (existing) {
          // Check if message already exists (avoid duplicates)
          const existingItems = (existing as any).messages?.items || [];
          const messageExists = existingItems.some((m: any) => m.id === newMessage.id);

          if (!messageExists) {
            // Add new message to the end of the list
            cache.writeQuery({
              query: MESSAGES_QUERY,
              variables: { conversationId, limit: 50 },
              data: {
                messages: {
                  ...(existing as any).messages,
                  items: [...existingItems, newMessage],
                },
              },
            });
          }
        }
      } catch (e) {
        // Cache might not have been populated yet for this conversation
        // This is fine - the message will be fetched on next query
        console.debug('[useSendGroupMessage] Could not update cache:', e);
      }
    },
  });

  return {
    sendGroupMessage: (input: { conversationId: string; content: string }) =>
      sendGroupMessage({ variables: { input } }),
    loading,
    error,
  };
};

// ============================================
// GROUP CHAT HOOKS
// ============================================

export const useCreateGroupConversation = () => {

  const [createGroupConversation, { loading, error }] = useMutation(CREATE_GROUP_CONVERSATION_MUTATION, {
  });

  return {
    createGroupConversation: (input: { title: string; characterIds: string[]; turnMode?: string }) =>
      createGroupConversation({ variables: { input } }),
    loading,
    error,
  };
};

export const useAddCharacterToGroup = () => {

  const [addCharacterToGroup, { loading, error }] = useMutation(ADD_CHARACTER_TO_GROUP_MUTATION, {
  });

  return {
    addCharacterToGroup: (input: { conversationId: string; characterId: string; turnOrder?: number }) =>
      addCharacterToGroup({ variables: { input } }),
    loading,
    error,
  };
};

export const useRemoveCharacterFromGroup = () => {

  const [removeCharacterFromGroup, { loading, error }] = useMutation(REMOVE_CHARACTER_FROM_GROUP_MUTATION, {
  });

  return {
    removeCharacterFromGroup: (input: { conversationId: string; characterId: string }) =>
      removeCharacterFromGroup({ variables: { input } }),
    loading,
    error,
  };
};

export const useUpdateGroupSettings = () => {

  const [updateGroupSettings, { loading, error }] = useMutation(UPDATE_GROUP_SETTINGS_MUTATION, {
  });

  return {
    updateGroupSettings: (conversationId: string, input: { turnMode?: string; maxConsecutiveResponses?: number }) =>
      updateGroupSettings({ variables: { conversationId, input } }),
    loading,
    error,
  };
};

export const useTriggerNextCharacterResponse = () => {

  const [triggerNextCharacterResponse, { loading, error }] = useMutation(TRIGGER_NEXT_CHARACTER_RESPONSE_MUTATION, {
  });

  return {
    triggerNextCharacterResponse: (conversationId: string) =>
      triggerNextCharacterResponse({ variables: { conversationId } }),
    loading,
    error,
  };
};

export const useRequestInterjection = () => {

  const [requestInterjection, { loading, error }] = useMutation(REQUEST_INTERJECTION_MUTATION, {
  });

  return {
    requestInterjection: (input: { conversationId: string; reason?: string; priority?: string }) =>
      requestInterjection({ variables: { input } }),
    loading,
    error,
  };
};

export const useCancelInterjection = () => {

  const [cancelInterjection, { loading, error }] = useMutation(CANCEL_INTERJECTION_MUTATION, {
  });

  return {
    cancelInterjection: (interjectionId: string) =>
      cancelInterjection({ variables: { interjectionId } }),
    loading,
    error,
  };
};

export const useApproveInterjection = () => {

  const [approveInterjection, { loading, error }] = useMutation(APPROVE_INTERJECTION_MUTATION, {
  });

  return {
    approveInterjection: (interjectionId: string) =>
      approveInterjection({ variables: { interjectionId } }),
    loading,
    error,
  };
};

export const useRejectInterjection = () => {

  const [rejectInterjection, { loading, error }] = useMutation(REJECT_INTERJECTION_MUTATION, {
  });

  return {
    rejectInterjection: (interjectionId: string) =>
      rejectInterjection({ variables: { interjectionId } }),
    loading,
    error,
  };
};

export const usePauseGroupConversation = () => {

  const [pauseGroupConversation, { loading, error }] = useMutation(PAUSE_GROUP_CONVERSATION_MUTATION, {
  });

  return {
    pauseGroupConversation: (conversationId: string) =>
      pauseGroupConversation({ variables: { conversationId } }),
    loading,
    error,
  };
};

export const useResumeGroupConversation = () => {

  const [resumeGroupConversation, { loading, error }] = useMutation(RESUME_GROUP_CONVERSATION_MUTATION, {
  });

  return {
    resumeGroupConversation: (conversationId: string) =>
      resumeGroupConversation({ variables: { conversationId } }),
    loading,
    error,
  };
};

export const useEditMessage = () => {

  const [editMessage, { loading, error }] = useMutation(EDIT_MESSAGE_MUTATION, {
  });

  return {
    editMessage: (id: string, content: string) =>
      editMessage({ variables: { id, content } }),
    loading,
    error,
  };
};

// ============================================
// CHARACTER HOOKS
// ============================================

export const useCharacters = (filter?: any, limit = 20, offset = 0) => {
  const { data, loading, error, fetchMore, refetch } = useQuery(CHARACTERS_QUERY, {
    variables: { filter, limit, offset },
    fetchPolicy: 'cache-and-network',
  });

  const loadMore = useCallback(() => {
    if (!data?.characters?.hasMore) return;

    // Use cache merge policy instead of deprecated updateQuery
    return fetchMore({
      variables: {
        offset: data.characters.items.length,
      },
    });
  }, [data, fetchMore]);

  return {
    characters: data?.characters?.items || [],
    totalCount: data?.characters?.totalCount || 0,
    hasMore: data?.characters?.hasMore || false,
    loading,
    error,
    loadMore,
    refetch,
  };
};

export const useCreateCharacter = () => {

  const [createCharacter, { loading, error }] = useMutation(CREATE_CHARACTER_MUTATION, {
    update: (cache, { data }) => {
      // Update characters list cache
      const existing = cache.readQuery({
        query: CHARACTERS_QUERY,
        variables: { limit: 20, offset: 0 },
      });

      if (existing && data?.createCharacter) {
        cache.writeQuery({
          query: CHARACTERS_QUERY,
          variables: { limit: 20, offset: 0 },
          data: {
            characters: {
              ...(existing as any).characters,
              items: [data.createCharacter, ...(existing as any).characters.items],
              totalCount: (existing as any).characters.totalCount + 1,
            },
          },
        });
      }
    },
  });

  return {
    createCharacter: (input: any) =>
      createCharacter({ variables: { input } }),
    loading,
    error,
  };
};

export const useGenerateCharacter = () => {

  const [generateCharacter, { loading, error }] = useMutation(GENERATE_CHARACTER_MUTATION, {
  });

  return {
    generateCharacter: (prompt: string) =>
      generateCharacter({ variables: { prompt } }),
    loading,
    error,
  };
};

// ============================================
// SETTINGS HOOKS
// ============================================

export const useSettings = () => {
  const { data, loading, error, refetch } = useQuery(GET_SETTINGS_QUERY, {
    fetchPolicy: 'cache-and-network',
  });

  return {
    settings: data?.settings,
    loading,
    error,
    refetch,
  };
};

export const useUpdateSettings = () => {

  const [updateSettings, { loading, error }] = useMutation(UPDATE_SETTINGS_MUTATION, {
  });

  return {
    updateSettings: (input: any) =>
      updateSettings({ variables: { input } }),
    loading,
    error,
  };
};

// ============================================
// SUBSCRIPTION HOOKS (using standalone WebSocket client)
// ============================================

// Raw GraphQL subscription queries as strings for the WebSocket client
const MESSAGE_STREAM_QUERY = `
  subscription MessageStream($conversationId: String!) {
    messageStream(conversationId: $conversationId) {
      id
      content
      streamingContent
      role
      isComplete
      tokenCount
      error
      createdAt
      characterId
      character { id name personality gender pronouns ageYears avatar }
      displayName
      messageType
      battleId
      conversationId
      generationStats {
        promptTokens
        completionTokens
        totalTokens
        promptEvalMs
        generationMs
        totalTimeMs
        tokensPerSecond
      }
    }
  }
`;

const CONVERSATION_UPDATES_QUERY = `
  subscription ConversationUpdates($conversationId: String!) {
    conversationUpdates(conversationId: $conversationId) {
      id
      title
      description
      isPinned
      isArchived
      updatedAt
    }
  }
`;

const TYPING_INDICATOR_QUERY = `
  subscription TypingIndicator($conversationId: String!) {
    typingIndicator(conversationId: $conversationId) {
      userId
      username
      isTyping
    }
  }
`;

const USER_PRESENCE_QUERY = `
  subscription UserPresence {
    userPresence {
      userId
      username
      status
      lastSeen
    }
  }
`;

const SYSTEM_NOTIFICATION_QUERY = `
  subscription SystemNotification {
    systemNotification {
      id
      type
      title
      message
      timestamp
    }
  }
`;

const GROUP_CHARACTER_RESPONSE_QUERY = `
  subscription GroupCharacterResponse($conversationId: String!) {
    groupCharacterResponse(conversationId: $conversationId) {
      id
      content
      streamingContent
      role
      messageType
      battleId
      isComplete
      tokenCount
      error
      createdAt
      characterId
      displayName
      conversationId
    }
  }
`;

const GROUP_STATE_CHANGED_QUERY = `
  subscription GroupStateChanged($conversationId: String!) {
    groupStateChanged(conversationId: $conversationId) {
      id
      groupState
      turnMode
      currentSpeaker {
        id
        name
        avatar
      }
      lastSpeaker {
        id
        name
        avatar
      }
      consecutiveResponseCount
    }
  }
`;

const INTERJECTION_REQUESTED_QUERY = `
  subscription InterjectionRequested($conversationId: String!) {
    interjectionRequested(conversationId: $conversationId) {
      id
      requester {
        id
        name
        avatar
      }
      requesterType
      reason
      status
      createdAt
    }
  }
`;

const INTERJECTION_RESOLVED_QUERY = `
  subscription InterjectionResolved($conversationId: String!) {
    interjectionResolved(conversationId: $conversationId) {
      id
      requester {
        id
        name
        avatar
      }
      requesterType
      reason
      status
      createdAt
    }
  }
`;

const CHARACTER_JOINED_QUERY = `
  subscription CharacterJoined($conversationId: String!) {
    characterJoined(conversationId: $conversationId) {
      id
      character {
        id
        name
        description
        personality
        gender
        pronouns
        ageYears
        avatar
      }
      turnOrder
      isActive
    }
  }
`;

const CHARACTER_LEFT_QUERY = `
  subscription CharacterLeft($conversationId: String!) {
    characterLeft(conversationId: $conversationId)
  }
`;

const TURN_CHANGED_QUERY = `
  subscription TurnChanged($conversationId: String!) {
    turnChanged(conversationId: $conversationId) {
      id
      name
      avatar
    }
  }
`;

/**
 * Hook for streaming LLM responses.
 *
 * HYBRID STREAMING: Backend sends small deltas frequently (10fps) with
 * periodic full content syncs (every 2s). This minimizes WebSocket data
 * while keeping the UI responsive.
 *
 * Protocol:
 * - streamingContent present → Full sync, replace accumulated content
 * - streamingContent null/undefined + content present → Delta, append to accumulated
 * - isComplete=true → Final message, use streamingContent as final content
 */
export const useMessageStream = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    MESSAGE_STREAM_QUERY,
    { conversationId },
    !conversationId
  );

  const rawMessage = data?.messageStream;

  // OPTIMIZED: Track delta content directly without repeated join() calls.
  //
  // Key insight: With sync every 500ms and deltas every 50ms, we get ~10 deltas per sync.
  // Calling chunks.join('') on each delta means O(10 + 9 + 8 + ... + 1) = O(55) string ops per sync window.
  // By tracking deltaContent directly, we do O(10) string ops per sync window.
  //
  // This is a 5x improvement in string operations during streaming.
  const accumulatedRef = useRef<{
    conversationId: string;  // Guards against cross-conversation frame leakage
    messageId: string;
    syncedContent: string;   // Content from last sync (or full content)
    deltaContent: string;    // Accumulated deltas since last sync
    hasCompleted: boolean;   // RACE CONDITION FIX: Track if message has completed
  }>({ conversationId: '', messageId: '', syncedContent: '', deltaContent: '', hasCompleted: false });
  const [message, setMessage] = useState<any>(null);

  // Reset accumulator whenever the active conversation changes, so a late
  // frame from the previous subscription can't pollute the new conversation.
  useEffect(() => {
    accumulatedRef.current = {
      conversationId,
      messageId: '',
      syncedContent: '',
      deltaContent: '',
      hasCompleted: false,
    };
    setMessage(null);
  }, [conversationId]);

  useEffect(() => {
    if (!rawMessage) {
      setMessage(null);
      accumulatedRef.current = {
        conversationId,
        messageId: '',
        syncedContent: '',
        deltaContent: '',
        hasCompleted: false,
      };
      return;
    }

    // Ignore frames whose conversationId doesn't match the currently subscribed
    // one. The server tags each streaming frame with its conversationId; when
    // the user switches conversations a stale frame can still arrive before the
    // old subscription unsubscribes, and we must not accumulate it here.
    // A frame with a MISSING conversationId is also rejected: accumulating it
    // would splice unknown conversation content into the current message.
    if (
      !rawMessage.conversationId ||
      rawMessage.conversationId !== conversationId
    ) {
      return;
    }

    const acc = accumulatedRef.current;

    // Reset accumulator if message ID changed (new message)
    if (rawMessage.id !== acc.messageId) {
      acc.conversationId = conversationId;
      acc.messageId = rawMessage.id;
      acc.syncedContent = '';
      acc.deltaContent = '';
      acc.hasCompleted = false;
    }

    // RACE CONDITION FIX: Once a message has completed, don't allow it to go back to generating
    // This handles out-of-order WebSocket messages where an old "generating" state arrives after completion
    if (acc.hasCompleted && !rawMessage.isComplete) {
      return; // Keep the current message state, don't update with stale data
    }

    // Track when message completes
    if (rawMessage.isComplete) {
      acc.hasCompleted = true;
    }

    let finalContent: string;

    // Handle hybrid protocol:
    // - If streamingContent is present (not null/undefined), it's a SYNC - use it directly
    // - If streamingContent is null but content exists, it's a DELTA - accumulate
    //
    // RACE CONDITION FIX: When a sync arrives, check if the sync content is shorter than
    // our accumulated content. This can happen if a sync and delta arrive out of order.
    // In that case, keep the longer content to avoid losing data.
    if (rawMessage.streamingContent != null && rawMessage.streamingContent !== '') {
      // SYNC: Backend sent full content
      const currentTotal = acc.syncedContent + acc.deltaContent;

      if (rawMessage.streamingContent.length >= currentTotal.length) {
        // Normal case: sync has more or equal content - replace accumulator
        acc.syncedContent = rawMessage.streamingContent;
        acc.deltaContent = '';
        finalContent = acc.syncedContent;
      } else {
        // RACE CONDITION: sync arrived with less content than we already have
        // This can happen if messages arrive out of order. Keep the longer content
        // but update syncedContent so future syncs work correctly
        // Check if sync content is a prefix of our content (it should be)
        if (currentTotal.startsWith(rawMessage.streamingContent)) {
          // Update sync point but keep delta content that extends beyond it
          acc.syncedContent = rawMessage.streamingContent;
          acc.deltaContent = currentTotal.slice(rawMessage.streamingContent.length);
        }
        // Keep the longer content
        finalContent = currentTotal;
      }
    } else if (rawMessage.content) {
      // DELTA: Append to deltaContent (simple string concat)
      // This is O(k) where k is the new delta size
      acc.deltaContent += rawMessage.content;
      finalContent = acc.syncedContent + acc.deltaContent;
    } else {
      // No new content - use cached values
      finalContent = acc.syncedContent + acc.deltaContent;
    }

    // Update state with current accumulated content
    setMessage({
      ...rawMessage,
      streamingContent: finalContent,
    });
  }, [rawMessage, conversationId]);

  return {
    message,
    loading,
    error,
  };
};

export const useConversationUpdates = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    CONVERSATION_UPDATES_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    update: data?.conversationUpdates,
    loading,
    error,
  };
};

/**
 * Hook to recover pending streaming messages after disconnect/reconnect.
 * Call this when the page loads or websocket reconnects to check for
 * any messages that were being streamed when the client disconnected.
 */
export const usePendingStreamingMessage = (conversationId: string | null) => {
  const { data, loading, error, refetch } = useQuery(PENDING_STREAMING_MESSAGE_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'network-only',
  });

  return {
    pendingMessage: data?.pendingStreamingMessage,
    loading,
    error,
    refetch,
  };
};

export const useTypingIndicator = (conversationId: string) => {
  // RACE CONDITION FIX: Use a more robust structure to track timeouts
  // Store both the display timeout and a "grace period" flag to prevent flickering
  const [typingUsers, setTypingUsers] = useState<Map<string, {
    username: string;
    timeout: NodeJS.Timeout;
    lastSeen: number; // Monotonic id of the latest typing indicator
  }>>(new Map());

  // Monotonic id for typing updates. Date.now() is NOT unique: two typing
  // frames within the same millisecond collide, so the first timeout's
  // `lastSeen === timeoutId` check could fire for the newer update.
  const typingUpdateIdRef = useRef(0);

  const { data } = useWebSocketSubscription(
    TYPING_INDICATOR_QUERY,
    { conversationId },
    !conversationId
  );

  // Process incoming typing indicator data
  useEffect(() => {
    const indicator = data?.typingIndicator;
    if (!indicator) return;

    setTypingUsers((prev) => {
      const next = new Map(prev);

      if (indicator.isTyping) {
        // Clear existing timeout if any
        const existing = next.get(indicator.userId);
        if (existing?.timeout) {
          clearTimeout(existing.timeout);
        }

        // Set new timeout to clear typing after 3 seconds
        // RACE CONDITION FIX: Use a unique monotonic id for this timeout to
        // prevent stale callbacks (Date.now() can collide within a millisecond)
        const timeoutId = ++typingUpdateIdRef.current;
        const timeout = setTimeout(() => {
          setTypingUsers((p) => {
            const user = p.get(indicator.userId);
            // Only delete if this is still the same timeout (prevents race with newer updates)
            if (user && user.lastSeen === timeoutId) {
              const n = new Map(p);
              n.delete(indicator.userId);
              return n;
            }
            return p; // Don't modify if a newer update came in
          });
        }, 3000);

        next.set(indicator.userId, { username: indicator.username, timeout, lastSeen: timeoutId });
      } else {
        // Clear timeout and remove user
        const existing = next.get(indicator.userId);
        if (existing?.timeout) {
          clearTimeout(existing.timeout);
        }
        next.delete(indicator.userId);
      }

      return next;
    });
  }, [data]);

  // Clean up timeouts on unmount
  const typingUsersRef = useRef(typingUsers);
  typingUsersRef.current = typingUsers;

  useEffect(() => {
    return () => {
      typingUsersRef.current.forEach((user) => {
        if (user.timeout) {
          clearTimeout(user.timeout);
        }
      });
    };
  }, []);

  const typingUsersList = Array.from(typingUsers.values()).map(u => u.username);

  return {
    typingUsers: typingUsersList,
    isAnyoneTyping: typingUsersList.length > 0,
  };
};

export const useUserPresence = () => {
  const [onlineUsers, setOnlineUsers] = useState<Map<string, { username: string; status: string; lastSeen: string }>>(new Map());

  const { data } = useWebSocketSubscription(USER_PRESENCE_QUERY);

  // Process incoming presence data
  useEffect(() => {
    const presence = data?.userPresence;
    if (!presence) return;

    setOnlineUsers((prev) => {
      const next = new Map(prev);

      if (presence.status === 'offline') {
        next.delete(presence.userId);
      } else {
        next.set(presence.userId, {
          username: presence.username,
          status: presence.status,
          lastSeen: presence.lastSeen,
        });
      }

      return next;
    });
  }, [data]);

  return {
    onlineUsers: Array.from(onlineUsers.values()),
    onlineCount: onlineUsers.size,
  };
};

export const useSystemNotifications = (onNotification?: (notification: any) => void) => {
  const { data } = useWebSocketSubscription(SYSTEM_NOTIFICATION_QUERY);

  // Hold the latest callback in a ref so a re-rendered parent that passes a
  // new function identity doesn't re-fire the effect (which would double-emit
  // the current notification).
  const onNotificationRef = useRef(onNotification);
  useEffect(() => {
    onNotificationRef.current = onNotification;
  }, [onNotification]);

  // Call the notification callback only when new data arrives.
  useEffect(() => {
    const notification = data?.systemNotification;
    if (notification && onNotificationRef.current) {
      onNotificationRef.current(notification);
    }
  }, [data]);

  return {
    latestNotification: data?.systemNotification,
  };
};

// ============================================
// GROUP CHAT SUBSCRIPTION HOOKS
// ============================================

export const useGroupCharacterResponse = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    GROUP_CHARACTER_RESPONSE_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    response: data?.groupCharacterResponse,
    loading,
    error,
  };
};

export const useGroupStateChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    GROUP_STATE_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    state: data?.groupStateChanged,
    loading,
    error,
  };
};

export const useInterjectionRequested = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    INTERJECTION_REQUESTED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    request: data?.interjectionRequested,
    loading,
    error,
  };
};

export const useInterjectionResolved = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    INTERJECTION_RESOLVED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    resolution: data?.interjectionResolved,
    loading,
    error,
  };
};

export const useCharacterJoined = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    CHARACTER_JOINED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    character: data?.characterJoined,
    loading,
    error,
  };
};

export const useCharacterLeft = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    CHARACTER_LEFT_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    characterId: data?.characterLeft,
    loading,
    error,
  };
};

export const useTurnChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    TURN_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    currentSpeaker: data?.turnChanged,
    loading,
    error,
  };
};

// ============================================
// UTILITY HOOKS
// ============================================

export const useOptimisticUpdate = <T,>(
  mutation: any,
  optimisticResponse: (variables: any) => T,
  updateCache?: (cache: any, result: any) => void
) => {

  const [mutate, { loading, error }] = useMutation(mutation, {
    optimisticResponse,
    update: updateCache,
  });

  return {
    mutate,
    loading,
    error,
  };
};

export const usePagination = (initialLimit = 20) => {
  const [limit, setLimit] = useState(initialLimit);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(true);

  const nextPage = useCallback(() => {
    if (hasMore) {
      setOffset((prev) => prev + limit);
    }
  }, [hasMore, limit]);

  const previousPage = useCallback(() => {
    setOffset((prev) => Math.max(0, prev - limit));
  }, [limit]);

  const reset = useCallback(() => {
    setOffset(0);
    setHasMore(true);
  }, []);

  return {
    limit,
    offset,
    hasMore,
    setLimit,
    setHasMore,
    nextPage,
    previousPage,
    reset,
  };
};

export const useDebounce = <T,>(value: T, delay = 500): T => {
  const [debouncedValue, setDebouncedValue] = useState<T>(value);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    return () => {
      clearTimeout(timer);
    };
  }, [value, delay]);

  return debouncedValue;
};

// ============================================
// ADDITIONAL HOOKS FOR CHAT INTERFACE
// ============================================

export const useSendTypingIndicator = () => {

  const [sendTypingIndicator, { loading, error }] = useMutation(
    SEND_TYPING_INDICATOR_MUTATION,
    {
      }
  );

  return {
    sendTypingIndicator,
    loading,
    error,
  };
};

export const useStopGeneration = () => {

  const [stopGeneration, { loading, error }] = useMutation(
    STOP_GENERATION_MUTATION,
    {
      }
  );

  return {
    stopGeneration,
    loading,
    error,
  };
};

export const useSwitchCharacter = () => {

  const [switchCharacter, { loading, error }] = useMutation(
    SWITCH_CHARACTER_MUTATION,
    {
        // Refetch messages query to get the new character's greeting
      refetchQueries: (result) => {
        const conversationId = result.data?.switchCharacter?.id;
        if (conversationId) {
          return [
            {
              query: MESSAGES_QUERY,
              variables: { conversationId },
            },
          ];
        }
        return [];
      },
      awaitRefetchQueries: true,
      update: (cache, { data }) => {
        if (!data?.switchCharacter) return;

        // Update conversation in cache.
        // `conversations` is a paginated object { items, hasMore, totalCount },
        // not a bare array, so map over existing.items and preserve the shape.
        const conversation = data.switchCharacter;
        cache.modify({
          fields: {
            conversations(existingConversations = {}) {
              const existingItems = existingConversations.items || [];
              const updatedItems = existingItems.map((conv: any) =>
                conv.id === conversation.id ? conversation : conv
              );
              return {
                ...existingConversations,
                items: updatedItems,
              };
            },
          },
        });
      },
    }
  );

  return {
    switchCharacter,
    loading,
    error,
  };
};

// ============================================
// DATA MANAGEMENT HOOKS
// ============================================

export const useExportData = () => {

  const { data, loading, error, refetch } = useQuery(EXPORT_DATA_QUERY, {
    skip: true, // Don't run automatically
    fetchPolicy: 'network-only',
  });

  return {
    exportData: refetch,
    data: data?.exportData,
    loading,
    error,
  };
};

export const useImportData = () => {

  const [importData, { loading, error }] = useMutation(IMPORT_DATA_MUTATION, {
    refetchQueries: ['Conversations', 'Characters'],
  });

  return {
    importData: (data: string) => importData({ variables: { data } }),
    loading,
    error,
  };
};

export const useDeleteUserData = () => {

  const [deleteData, { loading, error }] = useMutation(DELETE_ALL_DATA_MUTATION, {
    refetchQueries: ['Conversations', 'Messages', 'Characters'],
  });

  return {
    deleteData: (dataType: string) => deleteData({ variables: { dataType } }),
    loading,
    error,
  };
};

// ============================================
// MULTI-USER INVITE HOOKS
// ============================================

export interface InviteFilter {
  status?: string;
}

export interface UserSearchResult {
  id: string;
  username: string;
  displayName: string;
  profilePicture?: string;
  email?: string;
}

export interface ConversationParticipant {
  id: string;
  conversationId: string;
  user: {
    id: string;
    username: string;
    displayName: string;
    profilePicture?: string;
    email?: string;
  };
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
  nickname?: string;
  isActive: boolean;
  canInvite: boolean;
  canAddCharacters: boolean;
  joinedAt: string;
  leftAt?: string;
  lastReadAt?: string;
  lastTypingAt?: string;
}

export interface ConversationInvite {
  id: string;
  conversationId: string;
  conversation: {
    id: string;
    title: string;
    description?: string;
  };
  inviter: {
    id: string;
    username: string;
    displayName: string;
    profilePicture?: string;
  };
  invitee?: {
    id: string;
    username: string;
    displayName: string;
    profilePicture?: string;
  };
  inviteeEmail?: string;
  inviteeUsername?: string;
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED';
  message?: string;
  roleToGrant: 'OWNER' | 'ADMIN' | 'MEMBER';
  createdAt: string;
  respondedAt?: string;
  expiresAt?: string;
}

export const useMyInvites = (filter?: InviteFilter, limit = 20, offset = 0) => {
  const { data, loading, error, fetchMore, refetch } = useQuery(MY_INVITES_QUERY, {
    variables: { filter, limit, offset },
    fetchPolicy: 'cache-and-network',
  });

  const loadMore = useCallback(() => {
    if (!data?.myInvites?.hasMore) return;

    // Use cache merge policy instead of deprecated updateQuery
    return fetchMore({
      variables: {
        offset: data.myInvites.items.length,
      },
    });
  }, [data, fetchMore]);

  return {
    invites: (data?.myInvites?.items || []) as ConversationInvite[],
    totalCount: data?.myInvites?.totalCount || 0,
    hasMore: data?.myInvites?.hasMore || false,
    loading,
    error,
    loadMore,
    refetch,
  };
};

export const useConversationParticipants = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(CONVERSATION_PARTICIPANTS_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    participants: (data?.conversationParticipants || []) as ConversationParticipant[],
    loading,
    error,
    refetch,
  };
};

export const useSearchUsers = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [results, setResults] = useState<UserSearchResult[]>([]);

  // The query itself reacts to searchQuery (state-bound variables), so the
  // search callback must ONLY update state. Previously it also called
  // refetch() with the same variables, firing two identical requests per
  // keystroke.
  const { data, loading, error } = useQuery(SEARCH_USERS_QUERY, {
    variables: { query: searchQuery, limit: 10 },
    skip: !searchQuery || searchQuery.length < 2,
    fetchPolicy: 'network-only',
  });

  useEffect(() => {
    if (data?.searchUsers) {
      setResults(data.searchUsers);
    }
  }, [data]);

  const search = useCallback((query: string) => {
    setSearchQuery(query);
    if (query.length < 2) {
      setResults([]);
    }
  }, []);

  const clearResults = useCallback(() => {
    setSearchQuery('');
    setResults([]);
  }, []);

  return {
    results,
    loading,
    error,
    search,
    clearResults,
  };
};

export const useInviteUser = () => {

  const [inviteUser, { loading, error }] = useMutation(INVITE_USER_TO_CONVERSATION_MUTATION, {
    refetchQueries: ['ConversationParticipants'],
  });

  return {
    inviteUser: (input: {
      conversationId: string;
      usernameOrEmail: string;
      message?: string;
      roleToGrant?: string;
    }) => inviteUser({ variables: { input } }),
    loading,
    error,
  };
};

export const useRespondToInvite = () => {

  const [respondToInvite, { loading, error }] = useMutation(RESPOND_TO_INVITE_MUTATION, {
    refetchQueries: ['MyInvites', 'Conversations'],
  });

  return {
    respondToInvite: (input: { inviteId: string; accept: boolean }) =>
      respondToInvite({ variables: { input } }),
    loading,
    error,
  };
};

export const useCancelInvite = () => {

  const [cancelInvite, { loading, error }] = useMutation(CANCEL_INVITE_MUTATION, {
    refetchQueries: ['ConversationParticipants'],
  });

  return {
    cancelInvite: (inviteId: string) => cancelInvite({ variables: { inviteId } }),
    loading,
    error,
  };
};

export const useUpdateParticipant = () => {

  const [updateParticipant, { loading, error }] = useMutation(UPDATE_PARTICIPANT_MUTATION, {
    refetchQueries: ['ConversationParticipants'],
  });

  return {
    updateParticipant: (input: {
      conversationId: string;
      userId: string;
      role?: string;
      canInvite?: boolean;
      canAddCharacters?: boolean;
      nickname?: string;
    }) => updateParticipant({ variables: { input } }),
    loading,
    error,
  };
};

export const useRemoveParticipant = () => {

  const [removeParticipant, { loading, error }] = useMutation(REMOVE_PARTICIPANT_MUTATION, {
    refetchQueries: ['ConversationParticipants'],
  });

  return {
    removeParticipant: (input: { conversationId: string; userId: string }) =>
      removeParticipant({ variables: { input } }),
    loading,
    error,
  };
};

export const useLeaveConversation = () => {

  const [leaveConversation, { loading, error }] = useMutation(LEAVE_CONVERSATION_MUTATION, {
    refetchQueries: ['Conversations'],
  });

  return {
    leaveConversation: (conversationId: string) =>
      leaveConversation({ variables: { conversationId } }),
    loading,
    error,
  };
};

export const useTransferOwnership = () => {

  const [transferOwnership, { loading, error }] = useMutation(TRANSFER_OWNERSHIP_MUTATION, {
    refetchQueries: ['ConversationParticipants', 'Conversations'],
  });

  return {
    transferOwnership: (input: { conversationId: string; newOwnerId: string }) =>
      transferOwnership({ variables: { input } }),
    loading,
    error,
  };
};

export const useMarkConversationRead = () => {

  const [markConversationRead, { loading, error }] = useMutation(MARK_CONVERSATION_READ_MUTATION, {
  });

  return {
    markConversationRead: (conversationId: string) =>
      markConversationRead({ variables: { conversationId } }),
    loading,
    error,
  };
};