import React, { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import { ConversationReadout } from './ConversationReadout';
import { MessageItem } from './MessageItem';
import { Loader2, ArrowDown, Square, Bot } from 'lucide-react';
import { useMessages, useMessageStream, useStopGeneration, usePendingStreamingMessage } from '@/hooks/useGraphQL';
import { useTTSStore } from '@/stores/ttsStore';
import { useMessageStore } from '@/stores/messageStore';
import { cn, normalizeTimestamp as normalizeTs } from '@/lib/utils';
import { Base64 } from 'js-base64';
import type { StreamingMessage } from '@/graphql/types';

// SessionStorage-backed persistence for the in-memory completed-streaming buffer.
// Survives a refresh during the brief window between streaming completion and DB
// confirmation. sessionStorage (not localStorage) so entries don't outlive the
// browser tab — once the user closes the tab, buffered-but-unsaved messages go
// away too. Quota is small (5MB typical) so we cap per-conversation entries.
const STREAM_BUFFER_KEY_PREFIX = 'chatllm:streamBuffer:';
const STREAM_BUFFER_MAX_PER_CONVERSATION = 10;
const STREAM_BUFFER_MAX_AGE_MS = 60 * 60 * 1000; // 1h

interface PersistedBufferEntry {
  message: Message;
  bufferedAt: number;
}

const streamBufferStorageKey = (conversationId: string): string =>
  `${STREAM_BUFFER_KEY_PREFIX}${conversationId}`;

/**
 * Safely decode Base64-encoded message content using js-base64, which performs
 * correct UTF-8 decoding. Browser atob() returns a binary string and does NOT
 * decode UTF-8, so it corrupts non-ASCII content (emoji, accented chars, CJK).
 */
function safeBase64Decode(encoded: string): string {
  try {
    return Base64.decode(encoded);
  } catch (e) {
    console.error('Failed to decode Base64 message content:', e);
    return '';
  }
}

const readPersistedBuffer = (conversationId: string): Record<string, PersistedBufferEntry> => {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.sessionStorage.getItem(streamBufferStorageKey(conversationId));
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return {};
    // Drop entries older than the max-age cap to prevent stale ghosts.
    const now = Date.now();
    const fresh: Record<string, PersistedBufferEntry> = {};
    for (const [id, entry] of Object.entries(parsed as Record<string, PersistedBufferEntry>)) {
      if (entry?.message && entry.bufferedAt && now - entry.bufferedAt < STREAM_BUFFER_MAX_AGE_MS) {
        fresh[id] = entry;
      }
    }
    return fresh;
  } catch {
    return {};
  }
};

const writePersistedBuffer = (conversationId: string, buffer: Record<string, PersistedBufferEntry>): void => {
  if (typeof window === 'undefined') return;
  try {
    const entries = Object.entries(buffer);
    // Cap entries: keep newest by bufferedAt.
    const capped = entries.length > STREAM_BUFFER_MAX_PER_CONVERSATION
      ? Object.fromEntries(
          entries
            .sort((a, b) => (b[1].bufferedAt || 0) - (a[1].bufferedAt || 0))
            .slice(0, STREAM_BUFFER_MAX_PER_CONVERSATION)
        )
      : buffer;
    if (Object.keys(capped).length === 0) {
      window.sessionStorage.removeItem(streamBufferStorageKey(conversationId));
    } else {
      window.sessionStorage.setItem(streamBufferStorageKey(conversationId), JSON.stringify(capped));
    }
  } catch {
    // sessionStorage may be unavailable (private mode, quota exceeded). The
    // in-memory buffer still works — just no refresh-survival on this tab.
  }
};

const persistBufferEntry = (conversationId: string, messageId: string, entry: PersistedBufferEntry): void => {
  const current = readPersistedBuffer(conversationId);
  current[messageId] = entry;
  writePersistedBuffer(conversationId, current);
};

const removePersistedBufferEntry = (conversationId: string, messageId: string): void => {
  const current = readPersistedBuffer(conversationId);
  if (messageId in current) {
    delete current[messageId];
    writePersistedBuffer(conversationId, current);
  }
};

export interface Message {
  id: string;
  content: string;
  role: 'user' | 'assistant' | 'system';
  timestamp: string;
  userId?: string;
  characterId?: string;
  // Display name for assistant messages (character name in group chat, from streaming)
  displayName?: string;
  // Character info for group chat messages
  character?: {
    id: string;
    name: string;
    personality?: string;
    gender?: string;
    pronouns?: string;
    ageYears?: number;
    avatar?: string;
  };
  // Author info for multi-user conversations (human users)
  author?: {
    id: string;
    username: string;
    displayName: string;
    profilePicture?: string;
  };
  isInterjection?: boolean;
  attachments?: Array<{
    id: string;
    type: string;
    name: string;
    url: string;
    size: number;
  }>;
  metadata?: {
    model?: string;
    completionTokens?: number;
    totalTimeMs?: number;
    edited?: boolean;
    editedAt?: string;
  };
  // Generation statistics for LLM responses
  generationStats?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
    promptEvalMs: number;
    generationMs: number;
    totalTimeMs: number;
    tokensPerSecond: number;
  };
  // Reaction summary for the message
  reactionSummary?: Array<{
    reactionType: string;
    count: number;
    userReacted: boolean;
  }>;
  battleId?: string;
  messageType?: 'DIALOGUE' | 'NARRATION' | 'ACTION' | 'OOC' | 'SYSTEM' | 'DICE';
  status?: 'sending' | 'sent' | 'error' | 'generating';
}

// Group chat participant info
export interface GroupParticipant {
  id: string;
  character: {
    id: string;
    name: string;
    personality?: string;
    gender?: string;
    pronouns?: string;
    ageYears?: number;
    avatar?: string;
  };
  turnOrder: number;
  isActive: boolean;
}

// Human user participant info for multi-user conversations
export interface UserParticipant {
  id: string;
  user: {
    id: string;
    username: string;
    displayName: string;
    profilePicture?: string;
  };
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
  isActive: boolean;
}

interface MessageListProps {
  allowStoryEditing?: boolean;
  conversationId?: string;
  isGenerating?: boolean;
  onGenerationComplete?: () => void;
  onReady?: () => void;
  characterGreeting?: string;
  characterName?: string;
  characterAvatar?: string;
  voiceCharacter?: { id: string; name: string; personality?: string; gender?: string; pronouns?: string; ageYears?: number; avatar?: string };
  // Group chat props
  isGroupChat?: boolean;
  participants?: GroupParticipant[];
  currentSpeaker?: { id: string; name: string; avatar?: string } | null;
  processingLabel?: string;
  // Multi-user props
  isMultiUser?: boolean;
  userParticipants?: UserParticipant[];
  currentUserId?: string;
}

export const MessageList = ({
  allowStoryEditing = true,
  conversationId,
  isGenerating,
  onGenerationComplete,
  onReady,
  characterGreeting,
  characterName,
  characterAvatar,
  voiceCharacter,
  isGroupChat = false,
  participants = [],
  currentSpeaker,
  processingLabel,
  isMultiUser = false,
  userParticipants = [],
  currentUserId,
}: MessageListProps) => {
  // Own automatic playback above message bubbles: the streaming bubble is
  // replaced by a saved/buffered bubble at completion and loses local refs.
  const { settings: voiceSettings, addStreamingChunk, finalizeStreaming, stop: stopVoice, stopAll: stopPlayback, readingConversationId } = useTTSStore();
  const voiceStreams = useRef(new Map<string, string>());
  const finalizedVoiceStreams = useRef(new Set<string>());
  const stopVoiceRef = useRef(stopVoice);
  stopVoiceRef.current = stopVoice;
  const stopPlaybackRef = useRef(stopPlayback);
  stopPlaybackRef.current = stopPlayback;
  useEffect(() => {
    const streams = voiceStreams.current;
    return () => {
      streams.forEach((_, id) => stopVoiceRef.current?.(id));
      stopPlaybackRef.current();
      streams.clear();
      finalizedVoiceStreams.current.clear();
    };
  }, [conversationId]);
  // Automatic playback consumes every stream frame as it arrives. A narration
  // event arrives as one complete frame, often immediately followed by the next
  // speaker's first token; reading only the rendered latest frame would skip it.
  const handleVoiceFrame = (stream: StreamingMessage) => {
    if (readingConversationId || !voiceSettings.enabled || !voiceSettings.autoPlay) return;
    if (stream.id === 'stopped' || stream.id === 'cancelled') {
      voiceStreams.current.forEach((_, id) => stopVoice(id));
      voiceStreams.current.clear();
      return;
    }
    if (stream.role?.toLowerCase() === 'user' ||
        ['OOC', 'SYSTEM', 'DICE'].includes(stream.messageType || '') ||
        (stream.role?.toLowerCase() !== 'assistant' && stream.messageType !== 'NARRATION')) return;
    const text = stream.streamingContent || stream.content || '';
    if (!stream.isComplete) {
      finalizedVoiceStreams.current.delete(stream.id);
      const previous = voiceStreams.current.get(stream.id);
      if (previous && text.length < previous.length - 20) stopVoice(stream.id);
      voiceStreams.current.set(stream.id, text);
      void addStreamingChunk(stream.id, text);
    } else if (!finalizedVoiceStreams.current.has(stream.id)) {
      // Story events (including opening narration) arrive as a single complete
      // frame. They do not need a preceding token frame to join the voice queue.
      finalizedVoiceStreams.current.add(stream.id);
      voiceStreams.current.delete(stream.id);
      const author = stream.character || (isGroupChat
        ? participants.find(participant => participant.character.id === stream.characterId)?.character
        : voiceCharacter);
      const character = author && (!stream.messageType || ['DIALOGUE', 'ACTION'].includes(stream.messageType))
        ? { id: author.id, name: author.name, personality: author.personality,
            gender: author.gender, pronouns: author.pronouns, ageYears: author.ageYears } : undefined;
      void finalizeStreaming(stream.id, stream.error ? '' : text, undefined, character);
    }
  };
  const voiceFrameRef = useRef(handleVoiceFrame);
  voiceFrameRef.current = handleVoiceFrame;
  const onStreamFrame = useCallback((stream: StreamingMessage) => voiceFrameRef.current(stream), []);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [lastMessageCount, setLastMessageCount] = useState(0);
  // Whether the initial message-count baseline has been established for the
  // current conversation. Without this, lastMessageCount=0 on first observation
  // would make a pre-existing assistant message look like a new reply and
  // prematurely fire onGenerationComplete.
  const countBaselineReadyRef = useRef(false);
  // Track message being regenerated for optimistic UI update
  const [regeneratingMessageId, setRegeneratingMessageId] = useState<string | null>(null);
  const regenerationTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const streamingTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const hasSignaledReadyRef = useRef(false);

  // Counter to trigger useMemo re-computation when the completed message buffer changes.
  // Refs don't cause re-renders, so without this, buffered messages (like narration arriving
  // before a character starts streaming) won't appear until the next refetch.
  const [bufferVersion, setBufferVersion] = useState(0);

  // Buffer for completed streaming messages that haven't been refetched yet
  // This prevents message loss when multiple characters respond in sequence
  // Structure: Map<messageId, { message: Message, bufferedAt: number, refetchCount: number }>
  // bufferedAt: timestamp when message was buffered (for safe cleanup)
  // refetchCount: number of refetches since buffering (message should be in DB after 2-3 refetches)
  const completedStreamingMessagesRef = useRef<Map<string, {
    message: Message;
    bufferedAt: number;
    refetchCount: number;
  }>>(new Map());

  // Track message IDs that have completed to suppress ghost "generating" states
  // This handles race conditions where out-of-order WebSocket messages arrive
  // Key: messageId, Value: timestamp when completion was seen
  const completedMessageIdsRef = useRef<Map<string, number>>(new Map());

  // RACE CONDITION FIX: Track the generation session to prevent duplicate placeholder/response
  // This tracks when we've received valid streaming data for the current generation
  // Key: conversationId, Value: { startedAt: timestamp, hasReceivedData: boolean, lastMessageId: string }
  const generationSessionRef = useRef<{
    conversationId: string;
    startedAt: number;
    hasReceivedData: boolean;
    lastStreamingMessageId: string | null;
  } | null>(null);

  // Use GraphQL query and subscription
  const { messages: messagesData, loading: isLoading, refetch } = useMessages(conversationId || '');
  const { message: streamingMessage, error: streamError } = useMessageStream(conversationId || '', onStreamFrame);
  const { stopGeneration } = useStopGeneration();

  // Surface transport-level subscription errors (not in-band rawMessage.error,
  // which is handled via messageStatus). Previously the returned error was
  // dropped, so a failed WebSocket subscription gave no indication to the user.
  useEffect(() => {
    if (streamError) {
      console.error('[MessageList] Streaming subscription error:', streamError);
    }
  }, [streamError]);

  // Streaming recovery: check for pending streaming messages on mount/reconnect
  const { refetch: refetchPending } = usePendingStreamingMessage(conversationId || null);
  const hasCheckedPendingRef = useRef(false);

  // Hydrate the in-memory buffer from sessionStorage on mount/conversation-change.
  // Covers the rare case where the user refreshes during the gap between a stream
  // completing and the DB save being confirmed: without this, a refresh in that
  // window loses the rendered message until the next generation. Hydrated entries
  // are dropped from sessionStorage as soon as the message appears in the DB
  // query result (in the cleanup useEffect below).
  useEffect(() => {
    if (!conversationId) return;
    const persisted = readPersistedBuffer(conversationId);
    const ids = Object.keys(persisted);
    if (ids.length === 0) return;
    let hydrated = 0;
    for (const id of ids) {
      if (completedStreamingMessagesRef.current.has(id)) continue;
      completedStreamingMessagesRef.current.set(id, {
        message: persisted[id].message,
        bufferedAt: persisted[id].bufferedAt,
        refetchCount: 0,
      });
      hydrated++;
    }
    if (hydrated > 0) {
      setBufferVersion(v => v + 1);
    }
  }, [conversationId]);

  // Streaming recovery effect - handles interrupted streams after reconnection
  useEffect(() => {
    // Only check once per conversation load
    if (!conversationId || hasCheckedPendingRef.current) return;
    hasCheckedPendingRef.current = true;

    // Check for pending streaming message on initial load
    refetchPending().then(({ data }) => {
      if (data?.pendingStreamingMessage) {
        const pending = data.pendingStreamingMessage;

        // If there's a pending message with content, add it to the buffer
        // This handles cases where the stream completed while we were disconnected
        if (pending.id && (pending.content || pending.streamingContent)) {
          // Check if this message is already in the buffer to prevent duplicates
          if (!completedStreamingMessagesRef.current.has(pending.id)) {
            const recoveredMsg: Message = {
              id: pending.id,
              content: pending.streamingContent || pending.content || '',
              role: (pending.role?.toLowerCase() || 'assistant') as 'user' | 'assistant' | 'system',
              timestamp: normalizeTimestamp(pending.createdAt),
              characterId: pending.characterId,
              displayName: pending.displayName,
              messageType: pending.messageType,
              generationStats: pending.generationStats,
              status: pending.isComplete ? 'sent' : 'generating',
            };
            completedStreamingMessagesRef.current.set(pending.id, {
              message: recoveredMsg,
              bufferedAt: Date.now(),
              refetchCount: 0,
            });
            setBufferVersion(v => v + 1);
          }

          // If it's complete, refetch to get the final version from database
          // If it's still generating, the streaming subscription should pick it up
          if (pending.isComplete) {
            setTimeout(() => refetch(), 300);
          }
        }
      }
    }).catch((err) => {
      console.warn('[MessageList] Failed to check for pending streaming message:', err);
    });
  }, [conversationId, refetchPending, refetch]);

  // Reset pending check when conversation changes
  useEffect(() => {
    hasCheckedPendingRef.current = false;
  }, [conversationId]);

  // Signal ready when the component has mounted and subscription is established
  // This happens when we have a conversationId and the initial data has loaded
  useEffect(() => {
    if (conversationId && !isLoading && !hasSignaledReadyRef.current && onReady) {
      hasSignaledReadyRef.current = true;
      onReady();
    }
  }, [conversationId, isLoading, onReady]);

  // Reset ready signal when conversation changes
  useEffect(() => {
    hasSignaledReadyRef.current = false;
    // New conversation: drop the message-count baseline so the next observation
    // re-establishes it without firing a spurious generation-complete.
    countBaselineReadyRef.current = false;
    setLastMessageCount(0);
  }, [conversationId]);

  // RACE CONDITION FIX: Track generation session - reset when isGenerating changes or conversation changes
  useEffect(() => {
    if (isGenerating && conversationId) {
      // Starting a new generation - initialize or update session
      if (!generationSessionRef.current ||
          generationSessionRef.current.conversationId !== conversationId ||
          !generationSessionRef.current.hasReceivedData) {
        generationSessionRef.current = {
          conversationId,
          startedAt: Date.now(),
          hasReceivedData: false,
          lastStreamingMessageId: null,
        };
      }
    } else if (!isGenerating) {
      // Generation ended - reset session
      generationSessionRef.current = null;
    }
  }, [isGenerating, conversationId]);

  // RACE CONDITION FIX: Track when we receive valid streaming data
  useEffect(() => {
    if (!streamingMessage || !conversationId) return;

    const isControlMessage = streamingMessage.id === 'stopped' || streamingMessage.id === 'cancelled';
    if (isControlMessage) return;

    // Mark that we've received streaming data for this session
    if (generationSessionRef.current &&
        generationSessionRef.current.conversationId === conversationId) {
      // Only mark as received if it's a new message (not a stale one)
      const isNewMessage = generationSessionRef.current.lastStreamingMessageId !== streamingMessage.id;
      if (isNewMessage || !generationSessionRef.current.hasReceivedData) {
        generationSessionRef.current.hasReceivedData = true;
        generationSessionRef.current.lastStreamingMessageId = streamingMessage.id;
      }
    }
  }, [streamingMessage?.id, conversationId]);

  // Clear regeneration timeout on unmount
  useEffect(() => {
    return () => {
      if (regenerationTimeoutRef.current) {
        clearTimeout(regenerationTimeoutRef.current);
      }
      if (streamingTimeoutRef.current) {
        clearTimeout(streamingTimeoutRef.current);
      }
    };
  }, []);

  // Streaming timeout: if backend times out (120s) but frontend subscription hangs
  useEffect(() => {
    // Clear any existing timeout
    if (streamingTimeoutRef.current) {
      clearTimeout(streamingTimeoutRef.current);
      streamingTimeoutRef.current = null;
    }

    // Only set timeout if we have an active streaming message that's not complete
    if (streamingMessage && !streamingMessage.isComplete) {
      const isControlMessage = streamingMessage.id === 'stopped' || streamingMessage.id === 'cancelled';
      if (!isControlMessage) {
        // Backend has 120s timeout, set frontend to 130s to allow for network delay.
        // The effect cleanup clears the timeout on unmount so callbacks shouldn't
        // fire against a dead component, but we still guard defensively with a
        // `cancelled` flag in case the runtime executes a pending timeout before
        // the cleanup clears it (observed occasionally under fast navigation).
        let cancelled = false;
        streamingTimeoutRef.current = setTimeout(() => {
          if (cancelled) return;
          console.warn('[MessageList] Streaming timeout - message generation took too long');
          streamingTimeoutRef.current = null;
          refetch();
          if (onGenerationComplete) {
            onGenerationComplete();
          }
        }, 130000); // 130 seconds

        return () => {
          cancelled = true;
          if (streamingTimeoutRef.current) {
            clearTimeout(streamingTimeoutRef.current);
            streamingTimeoutRef.current = null;
          }
        };
      }
    }

    return () => {
      if (streamingTimeoutRef.current) {
        clearTimeout(streamingTimeoutRef.current);
        streamingTimeoutRef.current = null;
      }
    };
  }, [streamingMessage?.id, streamingMessage?.isComplete, refetch, onGenerationComplete]);

  // Clear regenerating state when we receive a streaming message
  useEffect(() => {
    if (!regeneratingMessageId) return;

    // If we have a streaming message, check if regeneration is complete
    if (streamingMessage) {
      // Handle control messages (stopped/cancelled) - these signal generation was interrupted
      const isControlMessage = streamingMessage.id === 'stopped' || streamingMessage.id === 'cancelled';
      if (isControlMessage && streamingMessage.isComplete) {
        if (regenerationTimeoutRef.current) {
          clearTimeout(regenerationTimeoutRef.current);
          regenerationTimeoutRef.current = null;
        }
        setRegeneratingMessageId(null);
        refetch();
        return;
      }

      const isNewMessage = streamingMessage.id !== regeneratingMessageId;
      const isActivelyStreaming = !streamingMessage.isComplete;

      // If new message completed, clear regenerating state and refetch
      if (isNewMessage && streamingMessage.isComplete) {
        if (regenerationTimeoutRef.current) {
          clearTimeout(regenerationTimeoutRef.current);
          regenerationTimeoutRef.current = null;
        }
        setRegeneratingMessageId(null);
        refetch();
      }
      // If new message is actively streaming, just clear the timeout (regeneration working)
      else if (isNewMessage && isActivelyStreaming) {
        if (regenerationTimeoutRef.current) {
          clearTimeout(regenerationTimeoutRef.current);
          regenerationTimeoutRef.current = null;
        }
      }
      // If same ID is actively streaming, it's a new stream for the same message
      else if (!isNewMessage && isActivelyStreaming) {
        if (regenerationTimeoutRef.current) {
          clearTimeout(regenerationTimeoutRef.current);
          regenerationTimeoutRef.current = null;
        }
      }
    }
  }, [regeneratingMessageId, streamingMessage?.id, streamingMessage?.isComplete, refetch]);


  useEffect(() => {
    if (readingConversationId) {
      // startReading already canceled live reservations. Do not cancel a new
      // read-through request that happens to reuse the just-completed stream ID.
      voiceStreams.current.clear();
      return;
    }
    if (!voiceSettings.enabled || !voiceSettings.autoPlay) {
      voiceStreams.current.forEach((_, id) => stopVoice(id));
      voiceStreams.current.clear();
    }
  }, [voiceSettings.enabled, voiceSettings.autoPlay, stopVoice, readingConversationId]);

  // When streaming message completes, buffer it and trigger refetch
  // This ensures the message is visible even if another character starts streaming
  useEffect(() => {
    if (!streamingMessage?.isComplete) {
      return;
    }

    const stream = streamingMessage;
    const isControlMessage = stream.id === 'stopped' || stream.id === 'cancelled';

    // Track completed message ID to suppress future ghost "generating" states
    if (!isControlMessage && stream.id) {
      completedMessageIdsRef.current.set(stream.id, Date.now());

      // Clean up old completed IDs (older than 30 seconds) to prevent memory leaks
      const now = Date.now();
      const cleanupThreshold = 30000;
      for (const [id, timestamp] of completedMessageIdsRef.current) {
        if (now - timestamp > cleanupThreshold) {
          completedMessageIdsRef.current.delete(id);
        }
      }
    }

    // Buffer the completed message (unless it's a control message)
    // Note: On completion, content may be empty but streamingContent has the full response
    if (!isControlMessage && stream.id && (stream.content || stream.streamingContent)) {
      // Only buffer if not already buffered (prevent duplicates from race conditions)
      if (!completedStreamingMessagesRef.current.has(stream.id)) {
        // Find character info for group chat messages
        let characterInfo = stream.character || (!isGroupChat ? voiceCharacter : undefined);
        if (isGroupChat && stream.characterId && participants.length > 0) {
          const participant = participants.find(p => p.character?.id === stream.characterId);
          if (participant?.character) {
            characterInfo = participant.character;
          }
        }

        const completedMsg: Message = {
          id: stream.id,
          content: stream.streamingContent || stream.content || '',
          role: (stream.role?.toLowerCase() || 'assistant') as 'user' | 'assistant' | 'system',
          timestamp: normalizeTimestamp(stream.createdAt),
          characterId: stream.characterId,
          displayName: stream.displayName,
          character: characterInfo,
          messageType: stream.messageType,
          battleId: stream.battleId,
          generationStats: stream.generationStats,
          status: 'sent',
        };

        const bufferedAt = Date.now();
        completedStreamingMessagesRef.current.set(stream.id, {
          message: completedMsg,
          bufferedAt,
          refetchCount: 0,
        });
        // Mirror to sessionStorage so a refresh in the brief save window
        // doesn't lose the rendered message. Cleared once the message is
        // confirmed in messagesData.
        if (conversationId) {
          persistBufferEntry(conversationId, stream.id, {
            message: completedMsg,
            bufferedAt,
          });
        }
        setBufferVersion(v => v + 1);
      }
    }

    // Delay to ensure backend has saved the message to database
    // 300ms gives sufficient time for the async save operation
    const timer = setTimeout(() => refetch(), 300);
    return () => clearTimeout(timer);
  }, [streamingMessage?.isComplete, streamingMessage?.id, refetch, isGroupChat, participants, voiceCharacter, conversationId]);

  // Handle streaming completion - notify parent that generation is complete
  // This covers both control messages (stopped/cancelled) and normal completion
  useEffect(() => {
    if (!streamingMessage?.isComplete || !onGenerationComplete) {
      return;
    }
    const isControlMessage = streamingMessage.id === 'stopped' || streamingMessage.id === 'cancelled';
    if (isControlMessage) {
      onGenerationComplete();
    } else if (streamingMessage.streamingContent || streamingMessage.content) {
      // RACE CONDITION FIX: Notify parent immediately when streaming completes
      // This prevents the placeholder from reappearing between completion and refetch
      onGenerationComplete();
    }
  }, [streamingMessage?.id, streamingMessage?.isComplete, onGenerationComplete]);

  // Polling fallback: if isGenerating is true but no streaming data is received,
  // poll for new messages periodically.
  //
  // Race guard: refetch() is async, and with fetchPolicy: 'network-only' and
  // Apollo deduplication off, overlapping in-flight requests would let a later
  // response overwrite a fresher one. We skip ticks while a refetch is pending
  // and also suppress them after unmount.
  useEffect(() => {
    if (!isGenerating || streamingMessage) {
      return;
    }

    let cancelled = false;
    let inFlight = false;
    const safeRefetch = async () => {
      if (cancelled || inFlight) return;
      inFlight = true;
      try {
        await refetch();
      } catch {
        // swallow — polling is best-effort; next tick will retry
      } finally {
        inFlight = false;
      }
    };

    const pollInterval = setInterval(safeRefetch, 1000);
    const immediatePoll = setTimeout(safeRefetch, 300);
    const maxPollTimeout = setTimeout(() => clearInterval(pollInterval), 30000);

    return () => {
      cancelled = true;
      clearInterval(pollInterval);
      clearTimeout(immediatePoll);
      clearTimeout(maxPollTimeout);
    };
  }, [isGenerating, streamingMessage, refetch]);

  // Detect when new assistant message arrives via polling (not streaming)
  // and notify parent that generation is complete.
  //
  // Guard: only fire when the count GREW after the initial baseline was
  // established. The first observation of a message list (baseline) must never
  // signal completion — otherwise a regeneration that already has a prior
  // assistant message on screen would immediately flip isGenerating off.
  useEffect(() => {
    if (!isGenerating || !messagesData || !onGenerationComplete) {
      return;
    }

    const currentCount = messagesData.length;
    const lastAssistantMessage = messagesData[messagesData.length - 1];

    if (!countBaselineReadyRef.current) {
      // First observation: just record the baseline, never fire completion.
      countBaselineReadyRef.current = true;
      setLastMessageCount(currentCount);
      return;
    }

    if (currentCount > lastMessageCount) {
      // A new message arrived since we started polling — if it's an assistant
      // reply, generation is done.
      if (lastAssistantMessage?.role?.toLowerCase() === 'assistant') {
        setLastMessageCount(currentCount);
        onGenerationComplete();
      } else {
        setLastMessageCount(currentCount);
      }
    } else if (currentCount !== lastMessageCount) {
      setLastMessageCount(currentCount);
    }
  }, [messagesData, isGenerating, onGenerationComplete, lastMessageCount]);

  // Delegates to the shared normalizeTimestamp in lib/utils.ts.
  // Wrapped in useCallback so it can safely appear in dependency arrays.
  const normalizeTimestamp = useCallback(
    (ts: string | undefined | null): string => normalizeTs(ts),
    [],
  );

  // Persistent storage for generationStats by message ID
  // This survives across refetches since the database doesn't store stats
  const generationStatsRef = useRef<Map<string, Message['generationStats']>>(new Map());

  // OPTIMIZATION: Split message processing into stable and streaming parts
  // This prevents recalculating all messages on every streaming update

  // Part 1: Process stable messages from database (only recalculates when data changes)
  const stableMessages: Message[] = useMemo(() => {
    if (!conversationId) return [];

    // Filter out the message being regenerated for optimistic UI
    const filteredData = regeneratingMessageId
      ? messagesData?.filter((msg: any) => msg.id !== regeneratingMessageId)
      : messagesData;

    // Get the set of message IDs from fetched data
    const fetchedMessageIds = new Set(filteredData?.map((msg: any) => msg.id) || []);

    const baseMessages: Message[] = filteredData?.map((msg: any): Message => {
      // Find character info for group chat messages
      let characterInfo = msg.character;
      if (!characterInfo && msg.characterId && participants.length > 0) {
        const participant = participants.find(p => p.character?.id === msg.characterId);
        if (participant?.character) {
          characterInfo = participant.character;
        }
      }

      // Find author info for multi-user conversations
      let authorInfo = msg.author;
      if (!authorInfo && msg.userId && userParticipants.length > 0) {
        const userParticipant = userParticipants.find(p => p.user.id === msg.userId);
        if (userParticipant) {
          authorInfo = userParticipant.user;
        }
      }

      // Retrieve preserved generationStats if available
      const preservedStats = generationStatsRef.current.get(msg.id);

      return {
        id: msg.id,
        // Decode Base64 with js-base64 (handles UTF-8). Browser atob() returns a
        // binary string and does NOT UTF-8 decode, which corrupts emoji, accented
        // Latin, and CJK content. The backend encodes as Base64-of-UTF-8.
        content: msg.contentBase64 ? safeBase64Decode(msg.contentBase64) : msg.content,
        role: msg.role.toLowerCase() as 'user' | 'assistant' | 'system',
        timestamp: normalizeTimestamp(msg.createdAt),
        userId: msg.author?.id || msg.userId,
        characterId: msg.characterId,
        displayName: msg.displayName,
        character: characterInfo ? {
          id: characterInfo.id,
          name: characterInfo.name,
          avatar: characterInfo.avatar,
          personality: characterInfo.personality,
          gender: characterInfo.gender, pronouns: characterInfo.pronouns, ageYears: characterInfo.ageYears,
        } : undefined,
        author: authorInfo ? {
          id: authorInfo.id,
          username: authorInfo.username,
          displayName: authorInfo.displayName,
          profilePicture: authorInfo.profilePicture,
        } : undefined,
        isInterjection: msg.isInterjection,
        attachments: msg.attachments,
        metadata: {
          model: msg.model,
          completionTokens: msg.completionTokens,
          totalTimeMs: msg.totalTimeMs,
          edited: msg.isEdited,
          editedAt: msg.updatedAt,
        },
        messageType: msg.messageType,
        battleId: msg.battleId,
        generationStats: preservedStats || msg.generationStats,
        status: 'sent',
      };
    }) || [];

    // Add buffered completed messages that haven't been refetched yet
    // This prevents message loss when multiple characters respond in sequence
    completedStreamingMessagesRef.current.forEach((buffered) => {
      if (!fetchedMessageIds.has(buffered.message.id)) {
        baseMessages.push(buffered.message);
      }
    });

    // Order by the backend's monotonic `seq` (exact insertion order), oldest
    // first. The backend already returns messages in seq order; sorting here
    // again keeps the buffered (just-streamed, no seq yet) messages placed
    // correctly relative to fetched ones — buffered messages are always newer
    // than fetched ones, so a missing seq sorts last (Number.MAX_SAFE_INTEGER).
    // This replaces the previous timestamp+random-UUID sort, which misordered
    // messages created within the same second.
    const seqOf = (msg: Message): number => {
      const anyMsg = msg as unknown as { seq?: number | string | null };
      const s = anyMsg.seq;
      if (s == null || s === '') return Number.MAX_SAFE_INTEGER;
      return Number(s);
    };
    const tsOf = (msg: Message): number => {
      const t = new Date(msg.timestamp).getTime();
      return Number.isNaN(t) ? 0 : t;
    };

    baseMessages.sort((a, b) => {
      const sa = seqOf(a);
      const sb = seqOf(b);
      // Only fall back to timestamp when BOTH messages lack seq (legacy rows).
      if (sa === Number.MAX_SAFE_INTEGER && sb === Number.MAX_SAFE_INTEGER) {
        return tsOf(a) - tsOf(b);
      }
      return sa - sb;
    });

    return baseMessages;
  }, [conversationId, messagesData, regeneratingMessageId, normalizeTimestamp, participants, userParticipants, bufferVersion]);


  // Cleanup completed-streaming buffer once messages are confirmed in DB.
  // Runs as a side-effect so it never mutates refs inside useMemo (which React
  // may invoke more than once in StrictMode / concurrent mode).
  useEffect(() => {
    if (!messagesData) return;

    const filteredData = regeneratingMessageId
      ? messagesData.filter((msg: any) => msg.id !== regeneratingMessageId)
      : messagesData;
    const fetchedIds = new Set(filteredData.map((msg: any) => msg.id as string));

    const toDelete: string[] = [];
    completedStreamingMessagesRef.current.forEach((buffered, id) => {
      if (buffered.message.generationStats) {
        generationStatsRef.current.set(id, buffered.message.generationStats);
      }
      buffered.refetchCount++;
      const isInFetchedData = fetchedIds.has(id);
      const hasEnoughRefetches = buffered.refetchCount >= 2;
      const isOldEnough = Date.now() - buffered.bufferedAt > 5000;
      if (isInFetchedData && (hasEnoughRefetches || isOldEnough)) {
        toDelete.push(id);
      }
    });

    if (toDelete.length > 0) {
      toDelete.forEach(id => {
        completedStreamingMessagesRef.current.delete(id);
        // Drop the sessionStorage mirror once the message is confirmed in DB.
        if (conversationId) {
          removePersistedBufferEntry(conversationId, id);
        }
      });
      setBufferVersion(v => v + 1);
    }
  }, [messagesData, regeneratingMessageId, conversationId]);

  // Part 2: Process streaming message separately (updates frequently during streaming)
  const currentStreamingMessage: Message | null = useMemo(() => {
    if (!streamingMessage) return null;

    const stream = streamingMessage;

    // Skip control messages that are just signals, not actual messages
    const isControlMessage = stream.id === 'stopped' || stream.id === 'cancelled';
    if (isControlMessage) return null;

    // Check if already in stable messages
    const existsInStable = stableMessages.some(m => m.id === stream.id);
    if (existsInStable) return null;

    // RACE CONDITION FIX: Check if message is already in completed buffer.
    // Once a message is in the buffer it is rendered via stableMessages, so we
    // must suppress the streaming copy regardless of whether the final
    // isComplete frame has arrived. Previously this only suppressed the
    // non-complete case, which left the same id in BOTH stableMessages and
    // currentStreamingMessage for ~300ms until refetch() landed the DB row —
    // producing a duplicated bubble and a React key collision.
    const existsInBuffer = completedStreamingMessagesRef.current.has(stream.id);
    if (existsInBuffer) {
      return null;
    }

    // RACE CONDITION FIX: Check if we've already seen this message complete
    // This catches out-of-order WebSocket messages where a "generating" state
    // arrives after we've already processed the completion for the same message
    const hasSeenCompletion = completedMessageIdsRef.current.has(stream.id);
    if (hasSeenCompletion && !stream.isComplete) {
      return null;
    }

    // Determine if this is a stale stream we should hide
    const isStaleStream = regeneratingMessageId &&
      stream.id === regeneratingMessageId &&
      stream.isComplete;
    if (isStaleStream) return null;

    // Find character info for group chat streaming messages
    let streamCharacterInfo = stream.character || (!isGroupChat ? voiceCharacter : undefined);
    if (isGroupChat && stream.characterId && participants.length > 0) {
      const participant = participants.find(p => p.character?.id === stream.characterId);
      if (participant?.character) {
        streamCharacterInfo = {
          id: participant.character.id,
          name: participant.character.name,
          avatar: participant.character.avatar,
          personality: participant.character.personality,
          gender: participant.character.gender, pronouns: participant.character.pronouns, ageYears: participant.character.ageYears,
        };
      }
    }

    // Determine message status
    let messageStatus: 'sending' | 'sent' | 'error' | 'generating' = 'generating';
    if (stream.error) {
      messageStatus = 'error';
    } else if (stream.isComplete) {
      messageStatus = 'sent';
    }

    return {
      id: stream.id,
      content: stream.streamingContent || stream.content || '',
      role: stream.role?.toLowerCase() as 'user' | 'assistant' | 'system',
      timestamp: normalizeTimestamp(stream.createdAt),
      characterId: stream.characterId,
      displayName: stream.displayName,
      character: streamCharacterInfo,
      messageType: stream.messageType,
      generationStats: stream.generationStats,
      status: messageStatus,
    };
  }, [streamingMessage, stableMessages, regeneratingMessageId, normalizeTimestamp, isGroupChat, participants, voiceCharacter]);

  // Part 3: Combine stable and streaming messages (lightweight merge)
  const messages: Message[] = useMemo(() => {
    if (currentStreamingMessage) {
      // Insert streaming message at the correct position (end, sorted by timestamp)
      const allMessages = [...stableMessages, currentStreamingMessage];
      // Only sort if streaming message timestamp is earlier than last stable message
      if (stableMessages.length > 0) {
        const lastStable = stableMessages[stableMessages.length - 1];
        const streamTime = new Date(currentStreamingMessage.timestamp).getTime();
        const lastStableTime = new Date(lastStable.timestamp).getTime();
        if (streamTime < lastStableTime) {
          // Stable messages are already sorted correctly; just insert the
          // streaming message at the right position by pure timestamp order.
          // No role-based priority is needed since all timestamps are server-side.
          const timestampCache = new Map<string, number>();
          timestampCache.set(currentStreamingMessage.id, streamTime);
          timestampCache.set(lastStable.id, lastStableTime);
          const getTimestamp = (msg: Message): number => {
            let ts = timestampCache.get(msg.id);
            if (ts === undefined) {
              ts = new Date(msg.timestamp).getTime();
              timestampCache.set(msg.id, ts);
            }
            return ts;
          };
          allMessages.sort((a, b) => {
            const diff = getTimestamp(a) - getTimestamp(b);
            if (diff !== 0) return diff;
            return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
          });
        }
      }
      return allMessages;
    }
    return stableMessages;
  }, [stableMessages, currentStreamingMessage]);

  // Historical-edit eligibility for GROUP chats. A player message can be
  // edited as long as no DIFFERENT player has sent a message after it —
  // editing would retroactively change the context their reply (and the
  // replies to it) was generated from. In a single-player group chat no
  // other player exists, so every own user message is editable. Regular
  // chats keep the short time-window rule in MessageItem.
  const historicalEditableIds = useMemo(() => {
    const ids = new Set<string>();
    if (!isGroupChat) return ids;
    let foreignUserAfter = false;
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role === 'user' && m.userId) {
        const isMine = !isMultiUser || m.userId === currentUserId;
        if (isMine && !foreignUserAfter) ids.add(m.id);
        if (isMultiUser && m.userId !== currentUserId) foreignUserAfter = true;
      }
    }
    return ids;
  }, [messages, isGroupChat, isMultiUser, currentUserId]);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (autoScroll && messages.length > 0) {
      messagesEndRef.current?.scrollIntoView({
        behavior: messages.some(message => message.status === 'generating') ? 'auto' : 'smooth',
        block: 'end',
      });
    }
  }, [messages, autoScroll]);

  // Detect manual scroll to disable auto-scroll
  const handleScroll = () => {
    if (!containerRef.current) return;

    const { scrollTop, scrollHeight, clientHeight } = containerRef.current;
    const isNearBottom = scrollHeight - scrollTop - clientHeight < 100;
    setAutoScroll(isNearBottom);
  };

  const scrollToBottom = () => {
    setAutoScroll(true);
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const { sendMessage, regenerateMessage, editMessage, deleteMessage } = useMessageStore();

  // Memoized handlers for MessageItem to prevent unnecessary re-renders
  const handleRetry = useCallback(async (messageId: string) => {
    if (!conversationId) {
      console.error('No conversation ID for retry');
      return;
    }

    const message = messages.find((m: Message) => m.id === messageId);
    if (!message) {
      console.error('Message not found for retry');
      return;
    }

    try {
      stopPlayback();
      if (message.role === 'user') {
        if (message.status === 'error') {
          // Retry without attachments - they can't be re-uploaded from URLs.
          // Pass retryOf so the failed bubble is replaced instead of appending
          // a duplicate optimistic message on every retry.
          await sendMessage(conversationId, message.content, [], messageId);
        }
      } else if (message.role === 'assistant') {
        // Clear any existing timeout
        if (regenerationTimeoutRef.current) {
          clearTimeout(regenerationTimeoutRef.current);
        }

        // Optimistically remove old message immediately - streaming will show new one
        setRegeneratingMessageId(messageId);

        // RACE CONDITION FIX: Only remove the specific message being regenerated from buffer
        // Previously we cleared the entire buffer which could lose other concurrent messages
        if (completedStreamingMessagesRef.current.has(messageId)) {
          completedStreamingMessagesRef.current.delete(messageId);
        }

        // For group chats, also remove any messages that came AFTER this one
        // since they will be deleted by the backend during regeneration
        if (isGroupChat) {
          const regeneratingMsg = messages.find((m: Message) => m.id === messageId);
          if (regeneratingMsg) {
            const regeneratingTime = new Date(regeneratingMsg.timestamp).getTime();
            const idsToRemove: string[] = [];
            completedStreamingMessagesRef.current.forEach((buffered, id) => {
              const bufferedTime = new Date(buffered.message.timestamp).getTime();
              if (bufferedTime > regeneratingTime) {
                idsToRemove.push(id);
              }
            });
            idsToRemove.forEach(id => {
              completedStreamingMessagesRef.current.delete(id);
            });
          }
        }

        // Set a timeout to clear regenerating state and refetch if no streaming data arrives
        // This handles cases where streaming doesn't work but the backend still regenerates
        // RACE CONDITION FIX: Store the messageId we're regenerating so timeout can check
        // if a new stream has started for a different message
        const regeneratingId = messageId;
        regenerationTimeoutRef.current = setTimeout(() => {
          // Only clear if we're still waiting for THIS message's regeneration
          // If streamingMessage exists and is for a different message, streaming is working
          // but for a different character in the queue - don't clear yet
          setRegeneratingMessageId((currentId) => {
            if (currentId === regeneratingId) {
              refetch();
              return null;
            }
            // Different message being regenerated now, don't clear
            return currentId;
          });
        }, 15000); // 15 second timeout

        // Fire regeneration - don't await, let streaming subscription handle display
        regenerateMessage(messageId, conversationId).catch((error) => {
          console.error('Failed to regenerate message:', error);
          // On error, restore the old message and refetch
          if (regenerationTimeoutRef.current) {
            clearTimeout(regenerationTimeoutRef.current);
            regenerationTimeoutRef.current = null;
          }
          setRegeneratingMessageId(null);
          refetch();
        });
      }
    } catch (error) {
      console.error('Failed to retry message:', error);
      if (regenerationTimeoutRef.current) {
        clearTimeout(regenerationTimeoutRef.current);
        regenerationTimeoutRef.current = null;
      }
      setRegeneratingMessageId(null);
    }
  }, [conversationId, messages, sendMessage, regenerateMessage, isGroupChat, refetch, stopPlayback]);

  const handleEdit = useCallback(async (messageId: string, newContent: string) => {
    try {
      stopPlayback();
      // Find the message being edited to check if it's a user message
      const message = messages.find((m: Message) => m.id === messageId);
      const isUserMessage = message?.role === 'user';

      // Perform the edit first
      await editMessage(messageId, newContent, conversationId);

      // RACE CONDITION FIX: Only clear SUBSEQUENT messages after edit
      // For both group chats and regular chats with user messages,
      // editing may trigger regeneration of messages that came after the edited one
      // We should only remove those subsequent messages, not the entire buffer
      if (isGroupChat || isUserMessage) {
        const editedMsg = messages.find((m: Message) => m.id === messageId);
        if (editedMsg) {
          const editedTime = new Date(editedMsg.timestamp).getTime();
          const idsToRemove: string[] = [];
          completedStreamingMessagesRef.current.forEach((buffered, id) => {
            const bufferedTime = new Date(buffered.message.timestamp).getTime();
            // Remove messages that came AFTER the edited message
            if (bufferedTime > editedTime) {
              idsToRemove.push(id);
            }
          });
          idsToRemove.forEach(id => {
            completedStreamingMessagesRef.current.delete(id);
          });
        }
      }

      // Refetch to get the updated message list
      // For regular chats, if the immediate next message was an assistant reply,
      // the backend will have deleted it and triggered regeneration
      // The streaming subscription will handle the new response
      refetch();
    } catch (error) {
      console.error('Failed to edit message:', error);
      // Don't clear buffer on error - keep existing messages
    }
  }, [isGroupChat, editMessage, conversationId, refetch, messages, stopPlayback]);

  const handleDelete = useCallback(async (messageId: string) => {
    try {
      stopPlayback();
      await deleteMessage(messageId, conversationId);
      // Refetch to update the message list
      refetch();
    } catch (error) {
      console.error('Failed to delete message:', error);
    }
  }, [deleteMessage, conversationId, refetch, stopPlayback]);

  const handleStopGeneration = useCallback(async () => {
    if (!conversationId) return;

    try {
      await stopGeneration({ variables: { conversationId } });
      // Clear any regeneration timeout
      if (regenerationTimeoutRef.current) {
        clearTimeout(regenerationTimeoutRef.current);
        regenerationTimeoutRef.current = null;
      }
      // Clear regenerating state if we were regenerating
      setRegeneratingMessageId(null);
      // Refetch to get the final state
      refetch();
    } catch (error) {
      console.error('Failed to stop generation:', error);
    }
  }, [conversationId, stopGeneration, refetch]);

  // Determine if we're currently generating (either new message or regeneration)
  // Exclude control messages (stopped/cancelled) as they indicate generation has ended
  const isControlMessage = streamingMessage?.id === 'stopped' || streamingMessage?.id === 'cancelled';
  const isCurrentlyGenerating = isGenerating ||
    (regeneratingMessageId !== null) ||
    (streamingMessage && !streamingMessage.isComplete && !isControlMessage);

  // Loading state
  if (isLoading && messages.length === 0 && !isCurrentlyGenerating) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="relative">
            <Loader2 className="h-10 w-10 animate-spin text-primary" />
            <div className="absolute inset-0 animate-pulse-ring rounded-full border-2 border-primary/30" />
          </div>
          <p className="text-sm text-muted-foreground">Loading messages...</p>
        </div>
      </div>
    );
  }

  // Empty state - Show character greeting or welcome screen
  if ((!conversationId || messages.length === 0) && !isCurrentlyGenerating) {
    // If a character is selected with a greeting, show the greeting as a message
    if (characterGreeting && characterName) {
      return (
        <div
          ref={containerRef}
          onScroll={handleScroll}
          className="flex h-full flex-col overflow-y-auto custom-scrollbar px-3 py-4 sm:px-4 sm:py-6"
        >
          <div className="mx-auto w-full max-w-3xl space-y-4 sm:space-y-6">
            {/* Character greeting message */}
            <div className="animate-fade-up">
              <div className="flex gap-2.5 sm:gap-4">
                {/* Avatar */}
                <div className="flex-shrink-0">
                  {characterAvatar ? (
                    <img
                      src={characterAvatar}
                      alt={characterName}
                      className="h-8 w-8 rounded-xl object-cover shadow-md sm:h-10 sm:w-10"
                    />
                  ) : (
                    <div className={cn(
                      'flex h-8 w-8 items-center justify-center rounded-xl sm:h-10 sm:w-10',
                      'bg-gradient-to-br from-secondary to-secondary-hover shadow-md'
                    )}>
                      <Bot size={20} className="text-secondary-foreground" />
                    </div>
                  )}
                </div>

                {/* Message content */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="truncate text-sm font-medium text-foreground">{characterName}</span>
                    <span className="shrink-0 text-xs text-muted-foreground">Just now</span>
                  </div>
                  <div className={cn(
                    'rounded-2xl rounded-tl-md px-3 py-2.5 sm:px-4 sm:py-3',
                    'bg-muted text-foreground',
                    'shadow-sm'
                  )}>
                    <p className="whitespace-pre-wrap break-words">{characterGreeting}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div ref={messagesEndRef} />
        </div>
      );
    }

    // Default welcome screen when no character is selected
    return (
      <div className="mx-auto w-full max-w-3xl px-5 pb-5 pt-3 sm:px-8">
        <div className="flex flex-col items-center justify-center gap-3 text-center sm:flex-row sm:gap-5 sm:text-left">
          <h2 className="editorial-heading shrink-0 text-4xl leading-none text-foreground sm:text-5xl">AI Chat</h2>
          <p className="max-w-sm text-sm leading-relaxed text-muted-foreground sm:border-l sm:border-border sm:pl-5">Ask a question, write something, or explore an idea. Choose a character for a different perspective.</p>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      onScroll={handleScroll}
      className="flex h-full flex-col overflow-y-auto custom-scrollbar px-3 py-4 sm:px-4 sm:py-6"
    >
      <div className="mx-auto w-full max-w-3xl space-y-4 sm:space-y-6">
        {conversationId && <ConversationReadout conversationId={conversationId} messages={messages} />}
        {messages.map((message: Message, index: number) => (
          <div
            key={message.id}
            className="animate-fade-up"
            style={{ animationDelay: `${Math.min(index * 50, 200)}ms` }}
          >
            <MessageItem
              allowStoryEditing={allowStoryEditing}
              message={message}
              onRetry={handleRetry}
              onEdit={handleEdit}
              onDelete={handleDelete}
              isGroupChat={isGroupChat}
              isMultiUser={isMultiUser}
              currentUserId={currentUserId}
              historicalEditing={historicalEditableIds.has(message.id)}
              conversationId={conversationId}
            />
          </div>
        ))}

        {processingLabel && <p role="status" className="py-3 text-sm text-muted-foreground">{processingLabel}</p>}

        {/* Show generating indicator when sending new message OR regenerating and no streaming message visible yet */}
        {/* RACE CONDITION FIX: Multiple layers of protection against duplicate placeholder/response:
            1. Check if any message in the array has 'generating' status
            2. Check if streamingMessage exists with content (even if not complete)
            3. Check if we've already received streaming data for this generation session
            4. Check if there's a recent completed response (prevents flash after completion)
        */}
        {(() => {
          // Condition 1: Must be generating or regenerating
          if (processingLabel || (!isGenerating && !regeneratingMessageId)) return null;

          // Condition 2: No generating message already in the list
          if (messages.some((m: Message) => m.status === 'generating')) return null;

          // Condition 3: No completed streaming message with content
          if (streamingMessage?.isComplete && (streamingMessage.streamingContent || streamingMessage.content)) return null;

          // RACE CONDITION FIX Condition 4: No active streaming message at all
          // This catches the case where streamingMessage exists but currentStreamingMessage was filtered out
          const hasActiveStreamingMessage = streamingMessage &&
            !streamingMessage.isComplete &&
            streamingMessage.id !== 'stopped' &&
            streamingMessage.id !== 'cancelled' &&
            (streamingMessage.streamingContent || streamingMessage.content);
          if (hasActiveStreamingMessage) return null;

          // RACE CONDITION FIX Condition 5: Check if we've received data for current session
          // This prevents placeholder from appearing if we've already started receiving the response
          const session = generationSessionRef.current;
          if (session?.hasReceivedData && session.conversationId === conversationId) {
            // We've received streaming data - check if the message is still being processed
            const streamingId = session.lastStreamingMessageId;
            if (streamingId) {
              // Check if this message is in the completed buffer or messages
              const inBuffer = completedStreamingMessagesRef.current.has(streamingId);
              const inMessages = messages.some((m: Message) => m.id === streamingId);
              // If message is tracked anywhere, don't show placeholder
              if (inBuffer || inMessages) return null;
            }
          }

          // RACE CONDITION FIX Condition 6: Check for very recent assistant response
          // This prevents flash of placeholder immediately after a fast response completes
          const now = Date.now();
          const recentThreshold = 1000; // 1 second
          const hasRecentResponse = messages.some((m: Message) => {
            if (m.role !== 'assistant') return false;
            const msgTime = new Date(m.timestamp).getTime();
            return (now - msgTime) < recentThreshold && m.status === 'sent';
          });
          if (hasRecentResponse) return null;

          // All conditions passed - show the placeholder
          return (
            <div className="animate-fade-up">
              <MessageItem
                allowStoryEditing={allowStoryEditing}
                message={{
                  id: 'generating',
                  content: '',
                  role: 'assistant',
                  timestamp: new Date().toISOString(),
                  status: 'generating',
                  // Include current speaker info for group chats
                  character: isGroupChat && currentSpeaker ? {
                    id: currentSpeaker.id,
                    name: currentSpeaker.name,
                    avatar: currentSpeaker.avatar,
                  } : undefined,
                }}
                onRetry={() => {}}
                onEdit={() => {}}
                onDelete={() => {}}
                isGroupChat={isGroupChat}
                isMultiUser={isMultiUser}
                currentUserId={currentUserId}
                conversationId={conversationId}
              />
            </div>
          );
        })()}

        {/* Stop generation button */}
        {isCurrentlyGenerating && (
          <div className="flex justify-center animate-fade-up">
            <button
              type="button"
              onClick={handleStopGeneration}
              className={cn(
                'flex items-center gap-2 rounded-full px-4 py-2 max-md:min-h-11',
                'bg-destructive/10 text-destructive',
                'hover:bg-destructive/20 transition-colors duration-150',
                'border border-destructive/30'
              )}
            >
              <Square size={14} className="fill-current" />
              <span className="text-sm font-medium">Stop generating</span>
            </button>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Pinned to the foot of the transcript's own scroll area so it never
          lands on the composer or the tool panel. */}
      {!autoScroll && (
        <div className="pointer-events-none sticky bottom-3 z-10 h-0">
          <button
            type="button"
            onClick={scrollToBottom}
            title="Scroll to the newest message"
            className={cn(
              'pointer-events-auto absolute bottom-0 right-0',
              'flex h-11 w-11 items-center justify-center gap-2 rounded-full sm:h-auto sm:w-auto sm:px-4 sm:py-2',
              'bg-card border border-border',
              'text-sm text-foreground shadow-lg',
              'hover:bg-accent transition-all duration-200',
              'animate-fade-up'
            )}
          >
            <ArrowDown size={16} aria-hidden="true" />
            <span className="max-sm:sr-only">New messages</span>
          </button>
        </div>
      )}
    </div>
  );
};
