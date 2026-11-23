import { Link } from 'react-router-dom';
import React, { useState, useRef, useEffect, useMemo, useCallback, memo } from 'react';
import {
  User, Bot, Copy, Check, Edit2, Trash2, RefreshCw,
  AlertCircle, Loader2, Download, Maximize2, Clock, Sparkles, X, ChevronDown, ChevronRight, Brain, Zap, Timer
} from 'lucide-react';
import { cn, formatDate, getParticipantColorClasses } from '@/lib/utils';
import { Message } from './MessageList';
import NarrationBlock from '@/components/roleplay/NarrationBlock';
import ActionBlock from '@/components/roleplay/ActionBlock';
import OOCBadge from '@/components/roleplay/OOCBadge';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/Tooltip';
import { ReactionPicker } from './ReactionPicker';
import { messageVoiceProfile } from '@/services/conversationSpeech';
import { TTSControls } from './TTSControls';
import { TTSPlayingIndicator } from './TTSPlayingIndicator';
import { useTTSStore } from '@/stores/ttsStore';
import { useCharacterCardStore } from '@/stores/characterCardStore';

// PRE-COMPILED REGEX for parseThinkingBlocks - compiled once at module load
// Avoids creating new RegExp objects on every function call (expensive)
const THINKING_BLOCK_REGEX = /<think(?:ing)?>([\s\S]*?)<\/think(?:ing)?>/gi;
const PARTIAL_THINKING_REGEX = /<think(?:ing)?>([\s\S]*)$/i;

/**
 * OPTIMIZED: Parse thinking blocks from content in minimal passes.
 *
 * Previous implementation: 8 regex operations (6+ string passes)
 * New implementation: 2 regex operations (2 passes) with safety checks
 *
 * For a 10KB response with thinking blocks, this reduces from ~80KB of string
 * scanning to ~20KB - a 4x improvement.
 *
 * Security: Validates content length, detects malicious patterns, and handles
 * malformed/nested tags gracefully.
 */
function parseThinkingBlocks(content: string): { thinking: string[]; response: string } {
  if (!content) {
    return { thinking: [], response: '' };
  }

  // Safety: Limit content size to prevent ReDoS attacks
  const MAX_CONTENT_LENGTH = 500_000; // 500KB
  if (content.length > MAX_CONTENT_LENGTH) {
    console.warn('[parseThinkingBlocks] Content exceeds max length, skipping parsing');
    return { thinking: [], response: content };
  }

  try {
    const thinkingBlocks: string[] = [];

    // Security: Check for potentially malicious script tags
    // This prevents XSS if someone tries to inject <script> inside thinking blocks
    const hasScriptTag = /<script[\s>]/i.test(content);
    if (hasScriptTag) {
      console.warn('[parseThinkingBlocks] Detected script tag, skipping parsing');
      return { thinking: [], response: content };
    }

    // Single regex handles both <thinking> and <think> tags
    // Reset lastIndex for global regex reuse
    THINKING_BLOCK_REGEX.lastIndex = 0;

    // Collect all complete thinking blocks
    let match;
    let iterationCount = 0;
    const MAX_ITERATIONS = 100; // Prevent infinite loops from malformed regex

    while ((match = THINKING_BLOCK_REGEX.exec(content)) !== null) {
      if (++iterationCount > MAX_ITERATIONS) {
        console.warn('[parseThinkingBlocks] Max iterations exceeded, possible malformed content');
        break;
      }

      const trimmed = match[1].trim();
      if (trimmed) {
        // Additional validation: check block isn't suspiciously large
        if (trimmed.length > 50_000) { // 50KB per block
          console.warn('[parseThinkingBlocks] Thinking block exceeds size limit, skipping');
          continue;
        }
        thinkingBlocks.push(trimmed);
      }
    }

    // Remove complete thinking blocks in single replace
    // (regex is already compiled, just doing the replace)
    let response = content.replace(THINKING_BLOCK_REGEX, '');

    // Check for partial/incomplete thinking block (streaming case)
    // Only one check needed since regex handles both variants
    const partialMatch = response.match(PARTIAL_THINKING_REGEX);
    if (partialMatch) {
      const trimmed = partialMatch[1].trim();
      if (trimmed && trimmed.length <= 50_000) {
        thinkingBlocks.push(trimmed);
      }
      // Remove partial block - use indexOf for efficiency when we know the position
      const partialStart = response.search(PARTIAL_THINKING_REGEX);
      if (partialStart !== -1) {
        response = response.substring(0, partialStart);
      }
    }

    return { thinking: thinkingBlocks, response: response.trim() };
  } catch (error) {
    // Graceful degradation: if parsing fails, return original content
    console.error('[parseThinkingBlocks] Error parsing thinking blocks:', error);
    return { thinking: [], response: content };
  }
}

// Extracted ActionButton component - memoized to prevent recreation
const ActionButton = memo(({
  onClick,
  title,
  icon: Icon,
  variant = 'default'
}: {
  onClick: () => void;
  title: string;
  icon: React.ElementType;
  variant?: 'default' | 'danger' | 'success';
}) => (
  <button
    onClick={onClick}
    className={cn(
      'rounded-lg p-1.5 transition-all duration-150',
      variant === 'default' && 'text-muted-foreground hover:bg-accent hover:text-foreground',
      variant === 'danger' && 'text-muted-foreground hover:bg-destructive/10 hover:text-destructive',
      variant === 'success' && 'text-success bg-success/10'
    )}
    title={title}
  >
    <Icon size={14} />
  </button>
));
ActionButton.displayName = 'ActionButton';

interface MessageItemProps {
  allowStoryEditing?: boolean;
  message: Message;
  onRetry: (messageId: string) => void;
  onEdit: (messageId: string, content: string) => void;
  onDelete: (messageId: string) => void;
  isGroupChat?: boolean;
  isMultiUser?: boolean;
  currentUserId?: string;
  /**
   * Group-chat historical editing: true when this own message may be edited
   * despite the age window, because no different player has replied after it
   * (computed in MessageList from the message list).
   */
  historicalEditing?: boolean;
  /** The story this message belongs to, so a character card can show its stats. */
  conversationId?: string;
}

// Edit time window in seconds (must match backend EDIT_TIME_LIMIT_MINUTES * 60)
const EDIT_WINDOW_SECONDS = 5 * 60; // 5 minutes

// Main MessageItem component wrapped in React.memo for performance
// Uses custom comparison to prevent unnecessary re-renders of non-streaming messages
export const MessageItem = memo(({
  message,
  onRetry,
  onEdit,
  onDelete,
  isGroupChat = false,
  isMultiUser = false,
  currentUserId,
  historicalEditing = false,
  allowStoryEditing = true,
  conversationId,
}: MessageItemProps) => {
  const [isEditing, setIsEditing] = useState(false);
  const [editContent, setEditContent] = useState(message.content);
  const [isCopied, setIsCopied] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);
  const [showActions, setShowActions] = useState(false);
  const [showThinking, setShowThinking] = useState(false);
  const [editTimeRemaining, setEditTimeRemaining] = useState<number | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Calculate and track edit time remaining for user messages
  useEffect(() => {
    if (message.role !== 'user') return;

    const calculateTimeRemaining = () => {
      const messageTime = new Date(message.timestamp).getTime();
      const now = Date.now();
      const elapsed = Math.floor((now - messageTime) / 1000);
      const remaining = EDIT_WINDOW_SECONDS - elapsed;
      return remaining > 0 ? remaining : 0;
    };

    // Initial calculation
    const initial = calculateTimeRemaining();
    setEditTimeRemaining(initial);

    // Only set up interval if time is remaining and less than 2 minutes (to avoid unnecessary timers)
    if (initial > 0 && initial < 120) {
      const interval = setInterval(() => {
        const remaining = calculateTimeRemaining();
        setEditTimeRemaining(remaining);
        if (remaining <= 0) {
          clearInterval(interval);
        }
      }, 1000);
      return () => clearInterval(interval);
    }

    // Return undefined explicitly when no cleanup is needed
    return undefined;
  }, [message.timestamp, message.role]);

  // Parse thinking blocks from content (for assistant messages)
  const { thinking, response: displayContent } = useMemo(() => {
    if (message.role === 'user' || !message.content) {
      return { thinking: [], response: message.content || '' };
    }
    return parseThinkingBlocks(message.content);
  }, [message.content, message.role]);

  useEffect(() => {
    if (isEditing && textareaRef.current) {
      textareaRef.current.focus();
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${textareaRef.current.scrollHeight}px`;
    }
  }, [isEditing, editContent]);

  // Playback visualization; automatic playback is owned by MessageList.
  const { playbackStates, currentlyPlayingMessageId } = useTTSStore();
  const openCharacterCard = useCharacterCardStore((state) => state.openCharacterCard);

  // Get TTS playback state for this message
  // Use currentlyPlayingMessageId as the source of truth for which message is actually speaking
  // This ensures only ONE indicator shows at a time in group conversations
  const ttsPlaybackState = playbackStates.get(message.id);
  const isTTSPlaying = currentlyPlayingMessageId === message.id &&
                       ttsPlaybackState?.isPlaying &&
                       !ttsPlaybackState?.isPaused;


  const handleCopy = async () => {
    await navigator.clipboard.writeText(message.content);
    setIsCopied(true);
    setTimeout(() => setIsCopied(false), 2000);
  };

  const handleSaveEdit = () => {
    if (editContent.trim() && editContent !== message.content) {
      onEdit(message.id, editContent);
    }
    setIsEditing(false);
  };

  const handleCancelEdit = () => {
    setEditContent(message.content);
    setIsEditing(false);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      handleCancelEdit();
    } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      handleSaveEdit();
    }
  };

  const isUser = message.role === 'user';
  const isGenerating = message.status === 'generating';
  const hasError = message.status === 'error';

  // In multi-user mode, check if this message is from the current user
  const isCurrentUser = isMultiUser && isUser && message.userId === currentUserId;
  // Show other user's messages on the left like assistant messages
  const showOnRight = isUser && (!isMultiUser || isCurrentUser);

  // Get participant color for group chat messages
  const participantColors = useMemo(() => {
    // For assistant messages in group chat, use character ID for color
    if (!isUser && isGroupChat && message.characterId) {
      return getParticipantColorClasses(message.characterId);
    }
    // For user messages in multi-user mode, use user ID for color
    if (isUser && isMultiUser && !isCurrentUser && message.userId) {
      return getParticipantColorClasses(message.userId);
    }
    return null;
  }, [isUser, isGroupChat, isMultiUser, isCurrentUser, message.characterId, message.userId]);

  // Memoized callbacks for action buttons
  const handleStartEditing = useCallback(() => setIsEditing(true), []);
  const handleToggleExpanded = useCallback(() => setIsExpanded(prev => !prev), []);
  const handleToggleThinking = useCallback(() => setShowThinking(prev => !prev), []);
  const handleMouseEnter = useCallback(() => setShowActions(true), []);
  const handleMouseLeave = useCallback(() => setShowActions(false), []);
  const handleRetryClick = useCallback(() => onRetry(message.id), [onRetry, message.id]);
  const handleDeleteClick = useCallback(() => onDelete(message.id), [onDelete, message.id]);

  // A character's avatar opens that character's card. Group chat messages
  // carry the character id, so the card loads the full profile instead of the
  // short summary attached to the message.
  const characterCardId = isUser ? undefined : message.character?.id || message.characterId;
  const characterCardName = message.character?.name || message.displayName || 'this character';
  const openCharacterCardForMessage = useCallback(() => {
    if (characterCardId) openCharacterCard(characterCardId, conversationId);
  }, [characterCardId, conversationId, openCharacterCard]);

  // Render narration blocks with special styling
  if (message.messageType === 'NARRATION') {
    return (
      <div
        className="group relative"
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        <NarrationBlock
          content={displayContent ?? message.content}
          timestamp={formatDate(message.timestamp)}
        />
        {message.battleId && conversationId && (
          <Link to={`/chat/${conversationId}?battle=${encodeURIComponent(message.battleId)}`}
            className="mt-2 inline-flex rounded-md border border-border px-3 py-1.5 text-sm text-primary hover:bg-accent">
            Open this encounter
          </Link>
        )}
        {!isUser && (
          <div className="mt-2 flex items-center gap-2">
            <TTSControls messageId={message.id} content={displayContent || message.content}
              isStreaming={isGenerating} compact />
            <span className="text-xs text-muted-foreground">Narrator voice</span>
          </div>
        )}
        {isGenerating && (
          <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
            <div className="flex gap-1">
              <div className="h-1.5 w-1.5 rounded-full bg-amber-500 animate-typing-dot" style={{ animationDelay: '0ms' }} />
              <div className="h-1.5 w-1.5 rounded-full bg-amber-500 animate-typing-dot" style={{ animationDelay: '150ms' }} />
              <div className="h-1.5 w-1.5 rounded-full bg-amber-500 animate-typing-dot" style={{ animationDelay: '300ms' }} />
            </div>
          </div>
        )}
        <div className={cn(
          'mt-1 flex items-center gap-0.5 rounded-lg bg-card border border-border/50 p-1 shadow-lg w-fit',
          'transition-all duration-200',
          showActions ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-1 pointer-events-none'
        )}>
          <ActionButton onClick={handleCopy} title="Copy" icon={isCopied ? Check : Copy} variant={isCopied ? 'success' : 'default'} />
          {allowStoryEditing && <ActionButton onClick={handleDeleteClick} title="Delete" icon={Trash2} variant="danger" />}
        </div>
      </div>
    );
  }

  // Render action blocks with special styling
  if (message.messageType === 'ACTION') {
    return (
      <div
        className="group relative"
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        <ActionBlock
          content={displayContent ?? message.content}
          characterName={message.character?.name || message.displayName || 'Unknown'}
          characterAvatar={message.character?.avatar}
          timestamp={formatDate(message.timestamp)}
          onAvatarClick={characterCardId ? openCharacterCardForMessage : undefined}
        />
        {!isUser && (
          <div className="mt-2">
            <TTSControls messageId={message.id} content={displayContent || message.content}
              character={messageVoiceProfile(message)} isStreaming={isGenerating} compact />
          </div>
        )}
        {isGenerating && (
          <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
            <div className="flex gap-1">
              <div className="h-1.5 w-1.5 rounded-full bg-teal-500 animate-typing-dot" style={{ animationDelay: '0ms' }} />
              <div className="h-1.5 w-1.5 rounded-full bg-teal-500 animate-typing-dot" style={{ animationDelay: '150ms' }} />
              <div className="h-1.5 w-1.5 rounded-full bg-teal-500 animate-typing-dot" style={{ animationDelay: '300ms' }} />
            </div>
          </div>
        )}
        <div className={cn(
          'mt-1 flex items-center gap-0.5 rounded-lg bg-card border border-border/50 p-1 shadow-lg w-fit',
          'transition-all duration-200',
          showActions ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-1 pointer-events-none'
        )}>
          <ActionButton onClick={handleCopy} title="Copy" icon={isCopied ? Check : Copy} variant={isCopied ? 'success' : 'default'} />
          {allowStoryEditing && <ActionButton onClick={handleDeleteClick} title="Delete" icon={Trash2} variant="danger" />}
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'group relative flex gap-4',
        showOnRight ? 'flex-row-reverse' : 'flex-row'
      )}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {/* Avatar */}
      <div
        className={cn('flex-shrink-0 pt-1', characterCardId && 'cursor-pointer rounded-xl')}
        onClick={characterCardId ? openCharacterCardForMessage : undefined}
        onKeyDown={
          characterCardId
            ? (event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  openCharacterCardForMessage();
                }
              }
            : undefined
        }
        role={characterCardId ? 'button' : undefined}
        tabIndex={characterCardId ? 0 : undefined}
        title={characterCardId ? `Open ${characterCardName}'s character card` : undefined}
        aria-label={characterCardId ? `Open ${characterCardName}'s character card` : undefined}
      >
        {/* User avatar for multi-user conversations */}
        {isUser && isMultiUser && message.author?.profilePicture ? (
          <div className="relative">
            <img
              src={message.author.profilePicture}
              alt={message.author.displayName}
              className={cn(
                'h-9 w-9 rounded-xl object-cover shadow-lg',
                'transition-transform duration-200 group-hover:scale-105',
                isCurrentUser && 'ring-2 ring-primary ring-offset-2 ring-offset-background'
              )}
            />
          </div>
        ) : /* Character avatar for group chat messages */
        !isUser && isGroupChat && message.character?.avatar ? (
          <div className="relative">
            <img
              src={message.character.avatar}
              alt={message.character.name}
              className={cn(
                'h-9 w-9 rounded-xl object-cover shadow-lg',
                'transition-transform duration-200 group-hover:scale-105',
                message.isInterjection && 'ring-2 ring-warning ring-offset-2 ring-offset-background'
              )}
            />
            {/* Online indicator */}
            <div className={cn(
              'absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 border-background',
              isGenerating ? 'bg-warning animate-pulse' : 'bg-success'
            )} />
          </div>
        ) : (
          <div
            className={cn(
              'relative flex h-9 w-9 items-center justify-center rounded-xl shadow-lg',
              'transition-transform duration-200 group-hover:scale-105',
              isUser
                ? isMultiUser && !isCurrentUser
                  ? participantColors
                    ? `${participantColors.bg} border ${participantColors.border} shadow-sm`  // Other users with participant color
                    : 'bg-gradient-to-br from-accent to-muted shadow-accent/25'  // Other users fallback
                  : 'bg-gradient-to-br from-primary to-primary-hover shadow-primary/25'  // Current user
                : participantColors
                ? `${participantColors.bg} border ${participantColors.border} shadow-sm`  // Character with participant color
                : message.character || message.characterId
                ? 'bg-gradient-to-br from-secondary to-secondary-hover shadow-secondary/25'
                : 'bg-gradient-to-br from-muted to-accent shadow-accent/25',
              !isUser && message.isInterjection && 'ring-2 ring-warning ring-offset-2 ring-offset-background'
            )}
          >
            {isUser ? (
              <User size={18} className={cn(
                isMultiUser && !isCurrentUser
                  ? participantColors ? participantColors.accent : "text-foreground"
                  : "text-primary-foreground"
              )} />
            ) : (
              <Bot size={18} className={cn(
                participantColors
                  ? participantColors.accent
                  : (message.character || message.characterId) ? "text-secondary-foreground" : "text-foreground"
              )} />
            )}

            {/* Online indicator for assistant */}
            {!isUser && (
              <div className={cn(
                'absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 border-background',
                isGenerating ? 'bg-warning animate-pulse' : 'bg-success'
              )} />
            )}
          </div>
        )}
      </div>

      {/* Message content */}
      <div className={cn('flex-1 min-w-0', showOnRight && 'flex justify-end')}>
        <div
          className={cn(
            'relative rounded-2xl px-4 py-3',
            'min-w-0 transition-colors duration-200',
            showOnRight ? 'max-w-[85%]' : 'w-full max-w-[42rem]',
            showOnRight
              ? 'bg-gradient-to-br from-primary to-primary-hover text-primary-foreground rounded-tr-md'
              : participantColors
              ? `${participantColors.bg} border ${participantColors.border} text-foreground rounded-tl-md`  // Participant-colored messages
              : isUser && isMultiUser
              ? 'bg-card border border-accent text-foreground rounded-tl-md'  // Other user's messages (fallback)
              : 'bg-card border border-border/50 text-foreground rounded-tl-md',
            hasError && 'bg-destructive/10 border-destructive/50',
            isExpanded && 'max-w-full',
            // TTS playing glow effect
            !isUser && isTTSPlaying && 'tts-playing'
          )}
        >
          {/* Message header for other users in multi-user mode */}
          {isUser && isMultiUser && !isCurrentUser && (
            <div className="flex items-center gap-2 mb-2">
              <span className={cn(
                'text-xs font-medium',
                participantColors ? participantColors.accent : 'text-foreground'
              )}>
                {message.author?.displayName || message.author?.username || 'User'}
              </span>
              <span className="text-xs text-muted-foreground">
                @{message.author?.username}
              </span>
            </div>
          )}

          {/* Message header for assistant */}
          {!isUser && (
            <div className="flex items-center gap-2 mb-2">
              <span className={cn(
                'text-xs font-medium',
                participantColors ? participantColors.accent : 'text-foreground'
              )}>
                {/* Show character name for assistant/system messages */}
                {message.character?.name || message.displayName
                  ? (message.character?.name || message.displayName)
                  : message.characterId
                  ? 'AI Assistant'
                  : 'Assistant'}
              </span>
              {/* TTS playing indicator */}
              {isTTSPlaying && (
                <TTSPlayingIndicator
                  isPlaying={true}
                  size="sm"
                  color="primary"
                  className="ml-0.5"
                />
              )}
              {/* Show interjection badge if this is an interjection */}
              {message.isInterjection && (
                <span className={cn(
                  'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5',
                  'bg-warning/20 text-2xs text-warning font-medium'
                )}>
                  Interjection
                </span>
              )}
              {/* Show OOC badge for out-of-character messages */}
              {message.messageType === 'OOC' && (
                <OOCBadge />
              )}
              {message.metadata?.model && (
                <span className={cn(
                  'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5',
                  'bg-accent text-2xs text-muted-foreground'
                )}>
                  <Sparkles size={10} />
                  {message.metadata.model}
                </span>
              )}
            </div>
          )}

          {/* Message content or editing */}
          {isEditing && allowStoryEditing ? (
            <div className="space-y-3">
              <textarea
                ref={textareaRef}
                value={editContent}
                onChange={(e) => setEditContent(e.target.value)}
                onKeyDown={handleKeyDown}
                className={cn(
                  'w-full resize-none rounded-xl border border-border',
                  'bg-background px-3 py-2 text-foreground',
                  'focus:outline-none focus:ring-2 focus:ring-primary/50',
                  'placeholder-muted-foreground'
                )}
                rows={3}
                placeholder="Edit your message..."
              />
              <div className="flex items-center justify-between">
                <span className={cn('text-2xs', showOnRight ? 'text-primary-foreground' : 'text-muted-foreground')}>
                  Press Ctrl+Enter to save, Esc to cancel
                </span>
                <div className="flex gap-2">
                  <button
                    onClick={handleCancelEdit}
                    className={cn(
                      'rounded-lg px-3 py-1.5 text-sm font-medium',
                      'bg-muted text-muted-foreground',
                      'hover:bg-accent hover:text-foreground',
                      'transition-colors duration-150'
                    )}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleSaveEdit}
                    className={cn(
                      'rounded-lg px-3 py-1.5 text-sm font-medium',
                      'bg-primary text-primary-foreground',
                      'hover:bg-primary-hover',
                      'transition-colors duration-150'
                    )}
                  >
                    Save
                  </button>
                </div>
              </div>
            </div>
          ) : isGenerating && !message.content ? (
            // Only show loading dots when generating AND no content yet
            <div className="flex items-center gap-3 py-1">
              <div className="flex gap-1">
                <div className="h-2 w-2 rounded-full bg-primary animate-typing-dot" style={{ animationDelay: '0ms' }} />
                <div className="h-2 w-2 rounded-full bg-primary animate-typing-dot" style={{ animationDelay: '150ms' }} />
                <div className="h-2 w-2 rounded-full bg-primary animate-typing-dot" style={{ animationDelay: '300ms' }} />
              </div>
              <span className="text-sm text-muted-foreground">Generating response...</span>
            </div>
          ) : (
            <>
              {/* Thinking blocks (collapsible) */}
              {allowStoryEditing && thinking.length > 0 && (
                <div className="mb-3">
                  <button
                    onClick={handleToggleThinking}
                    className={cn(
                      'flex items-center gap-2 text-xs font-medium',
                      'text-muted-foreground hover:text-foreground',
                      'transition-colors duration-150'
                    )}
                  >
                    {showThinking ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    <Brain size={14} />
                    <span>Thinking ({thinking.length} {thinking.length === 1 ? 'block' : 'blocks'})</span>
                    {isGenerating && (
                      <Loader2 size={12} className="animate-spin ml-1" />
                    )}
                  </button>
                  {showThinking && (
                    <div className={cn(
                      'mt-2 rounded-lg border border-border/50 bg-muted/30 p-3',
                      'text-sm text-muted-foreground italic',
                      'max-h-60 overflow-y-auto break-words custom-scrollbar'
                    )}>
                      {thinking.map((block, idx) => (
                        <div key={idx} className={idx > 0 ? 'mt-3 pt-3 border-t border-border/30' : ''}>
                          <ReactMarkdown>{block}</ReactMarkdown>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* Only show thinking indicator if thinking but no response yet */}
              {thinking.length > 0 && !displayContent && isGenerating && (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Loader2 size={14} className="animate-spin" />
                  <span>Processing thoughts...</span>
                </div>
              )}

              {/* Actual response content */}
              {displayContent && (
                <div className={cn(
                  'prose prose-sm message-prose max-w-none',
                  showOnRight && 'message-prose-user',
                  '[&>*:first-child]:mt-0 [&>*:last-child]:mb-0'
                )}>
                  <ReactMarkdown
                components={{
                  code({ className, children, ...props }: any) {
                    const match = /language-(\w+)/.exec(className || '');
                    const isInline = !className;
                    const language = match ? match[1] : 'text';

                    if (isInline) {
                      return (
                        <code
                          className={cn(
                            'rounded-md px-1.5 py-0.5 text-sm',
                            showOnRight
                              ? 'bg-primary-foreground/10 text-primary-foreground'
                              : 'bg-muted text-foreground'
                          )}
                          {...props}
                        >
                          {children}
                        </code>
                      );
                    }

                    return (
                      <div className="group/code relative my-3 overflow-hidden rounded-xl border border-border/50">
                        {/* Code header */}
                        <div className={cn(
                          'flex items-center justify-between px-4 py-2',
                          'bg-muted border-b border-border/50'
                        )}>
                          <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                            {language}
                          </span>
                          <button
                            onClick={() => {
                              void navigator.clipboard.writeText(String(children));
                            }}
                            className={cn(
                              'flex items-center gap-1.5 rounded-md px-2 py-1',
                              'text-xs text-muted-foreground',
                              'hover:bg-accent hover:text-foreground',
                              'transition-colors duration-150'
                            )}
                          >
                            <Copy size={12} />
                            Copy
                          </button>
                        </div>
                        <SyntaxHighlighter
                          language={language}
                          style={oneDark as any}
                          PreTag="div"
                          customStyle={{
                            margin: 0,
                            borderRadius: 0,
                            fontSize: '0.8125rem',
                            lineHeight: 1.6,
                            padding: '1rem',
                          }}
                        >
                          {String(children).replace(/\n$/, '')}
                        </SyntaxHighlighter>
                      </div>
                    );
                  },
                  // Style links
                  a({ children, href, ...props }) {
                    return (
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className={cn(
                          'underline underline-offset-2',
                          showOnRight ? 'text-primary-foreground hover:text-primary-foreground' : 'text-primary hover:text-primary-hover',
                          'transition-colors duration-150'
                        )}
                        {...props}
                      >
                        {children}
                      </a>
                    );
                  },
                  // Style blockquotes
                  blockquote({ children, ...props }) {
                    return (
                      <blockquote
                        className={cn(
                          'border-l-4 pl-4 my-3 italic',
                          showOnRight ? 'border-primary-foreground/50 text-primary-foreground' : 'border-primary/50 text-muted-foreground'
                        )}
                        {...props}
                      >
                        {children}
                      </blockquote>
                    );
                  },
                  }}
                >
                  {displayContent}
                </ReactMarkdown>
                {/* Streaming indicator when generating with content */}
                {isGenerating && (
                  <div className="flex items-center gap-2 mt-2 text-xs text-muted-foreground">
                    <div className="flex gap-1">
                      <div className="h-1.5 w-1.5 rounded-full bg-primary animate-typing-dot" style={{ animationDelay: '0ms' }} />
                      <div className="h-1.5 w-1.5 rounded-full bg-primary animate-typing-dot" style={{ animationDelay: '150ms' }} />
                      <div className="h-1.5 w-1.5 rounded-full bg-primary animate-typing-dot" style={{ animationDelay: '300ms' }} />
                    </div>
                  </div>
                )}
              </div>
              )}
            </>
          )}

          {/* Error state */}
          {hasError && (
            <div className={cn(
              'mt-3 flex items-center gap-2 rounded-lg',
              'bg-destructive/10 px-3 py-2 text-sm text-destructive'
            )}>
              <AlertCircle size={16} />
              <span>Failed to send message</span>
              <button
                onClick={() => onRetry(message.id)}
                className={cn(
                  'ml-auto flex items-center gap-1.5 rounded-md px-2 py-1',
                  'bg-destructive/20 hover:bg-destructive/30',
                  'transition-colors duration-150'
                )}
              >
                <RefreshCw size={12} />
                Retry
              </button>
            </div>
          )}

          {/* Attachments */}
          {message.attachments && message.attachments.length > 0 && (
            <div className="mt-3 space-y-2">
              {message.attachments.map((attachment) => (
                <div
                  key={attachment.id}
                  className={cn(
                    'flex items-center justify-between rounded-lg',
                    'bg-muted px-3 py-2',
                    'border border-border/50'
                  )}
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <div className="rounded-md bg-accent p-1.5">
                      <Download size={14} className="text-muted-foreground" />
                    </div>
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-foreground truncate">
                        {attachment.name}
                      </p>
                      <p className="text-2xs text-muted-foreground">
                        {(attachment.size / 1024).toFixed(1)} KB
                      </p>
                    </div>
                  </div>
                  <button
                    className={cn(
                      'rounded-lg p-1.5 text-muted-foreground',
                      'hover:bg-accent hover:text-foreground',
                      'transition-colors duration-150'
                    )}
                    title="Download"
                  >
                    <Download size={16} />
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Reactions */}
          <div className="mt-2">
            <ReactionPicker
              messageId={message.id}
              reactions={message.reactionSummary}
              tone={showOnRight ? 'primary' : 'default'}
            />
          </div>

          {/* Metadata footer */}
          <div className={cn(
            'mt-3 flex items-center justify-between text-2xs',
            showOnRight ? 'text-primary-foreground' : 'text-muted-foreground'
          )}>
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex items-center gap-1.5 cursor-help">
                    <Clock size={10} />
                    <span>{formatDate(message.timestamp)}</span>
                    {message.metadata?.edited && (
                      <span>(edited)</span>
                    )}
                  </div>
                </TooltipTrigger>
                <TooltipContent side="top" className="max-w-xs">
                  <div className="space-y-1.5 text-xs">
                    <div className="font-medium border-b border-border/50 pb-1">
                      {new Date(message.timestamp).toLocaleString(undefined, {
                        dateStyle: 'medium',
                        timeStyle: 'medium',
                      })}
                    </div>
                    {message.generationStats && (
                      <div className="space-y-1 pt-0.5">
                        <div className="flex items-center justify-between gap-4">
                          <span className="text-muted-foreground">Prompt tokens:</span>
                          <span className="font-mono">{message.generationStats.promptTokens.toLocaleString()}</span>
                        </div>
                        <div className="flex items-center justify-between gap-4">
                          <span className="text-muted-foreground">Completion tokens:</span>
                          <span className="font-mono">{message.generationStats.completionTokens.toLocaleString()}</span>
                        </div>
                        <div className="flex items-center justify-between gap-4 border-t border-border/50 pt-1">
                          <span className="text-muted-foreground">Total tokens:</span>
                          <span className="font-mono font-medium">{message.generationStats.totalTokens.toLocaleString()}</span>
                        </div>
                        <div className="flex items-center justify-between gap-4 border-t border-border/50 pt-1">
                          <span className="text-muted-foreground flex items-center gap-1">
                            <Zap size={10} /> Speed:
                          </span>
                          <span className="font-mono text-primary">{message.generationStats.tokensPerSecond.toFixed(1)} tok/s</span>
                        </div>
                        <div className="flex items-center justify-between gap-4">
                          <span className="text-muted-foreground flex items-center gap-1">
                            <Timer size={10} /> Generation:
                          </span>
                          <span className="font-mono">{(message.generationStats.generationMs / 1000).toFixed(2)}s</span>
                        </div>
                        {message.generationStats.promptEvalMs > 0 && (
                          <div className="flex items-center justify-between gap-4">
                            <span className="text-muted-foreground">Prompt eval:</span>
                            <span className="font-mono">{(message.generationStats.promptEvalMs / 1000).toFixed(2)}s</span>
                          </div>
                        )}
                        <div className="flex items-center justify-between gap-4 border-t border-border/50 pt-1">
                          <span className="text-muted-foreground">Total time:</span>
                          <span className="font-mono">{(message.generationStats.totalTimeMs / 1000).toFixed(2)}s</span>
                        </div>
                      </div>
                    )}
                    {!message.generationStats && message.metadata?.totalTimeMs && (
                      <div className="flex items-center justify-between gap-4">
                        <span className="text-muted-foreground">Processing time:</span>
                        <span className="font-mono">{(message.metadata.totalTimeMs / 1000).toFixed(2)}s</span>
                      </div>
                    )}
                  </div>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
            {(message.generationStats || message.metadata) && (
              <div className="flex items-center gap-3">
                {message.generationStats ? (
                  <>
                    <span>{message.generationStats.completionTokens.toLocaleString()} tokens</span>
                    <span className="flex items-center gap-0.5">
                      <Zap size={10} />
                      {message.generationStats.tokensPerSecond.toFixed(1)} tok/s
                    </span>
                  </>
                ) : (
                  <>
                    {message.metadata?.completionTokens && (
                      <span>{message.metadata.completionTokens.toLocaleString()} tokens</span>
                    )}
                    {message.metadata?.totalTimeMs && (
                      <span>{(message.metadata.totalTimeMs / 1000).toFixed(1)}s</span>
                    )}
                  </>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Action buttons - floating */}
        <div
          className={cn(
            'flex items-center gap-1 mt-1.5',
            showOnRight ? 'justify-end mr-1' : 'justify-start ml-1',
            'transition-all duration-200',
            showActions ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-1 pointer-events-none'
          )}
        >
          <div className={cn(
            'flex items-center gap-0.5 rounded-lg',
            'bg-card border border-border/50 p-1',
            'shadow-lg backdrop-blur-sm'
          )}>
            <ActionButton
              onClick={handleCopy}
              title="Copy message"
              icon={isCopied ? Check : Copy}
              variant={isCopied ? 'success' : 'default'}
            />

            {/* Editing own messages: within the short time window, or
                historically in group chats when no other player has replied
                after this message (historicalEditing from MessageList). */}
            {allowStoryEditing && isUser && !isGenerating && (!isMultiUser || isCurrentUser) &&
              (historicalEditing || (editTimeRemaining !== null && editTimeRemaining > 0)) && (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <div className="relative">
                      <ActionButton
                        onClick={handleStartEditing}
                        title="Edit message"
                        icon={Edit2}
                      />
                      {/* Show countdown when less than 60 seconds remaining */}
                      {!historicalEditing && editTimeRemaining !== null && editTimeRemaining < 60 && (
                        <span className={cn(
                          'absolute -top-1 -right-1 min-w-4 h-4 flex items-center justify-center',
                          'rounded-full text-[9px] font-bold',
                          editTimeRemaining < 30
                            ? 'bg-destructive text-destructive-foreground animate-pulse'
                            : 'bg-warning text-warning-foreground'
                        )}>
                          {editTimeRemaining}
                        </span>
                      )}
                    </div>
                  </TooltipTrigger>
                  <TooltipContent side="top">
                    {historicalEditing || editTimeRemaining === null
                      ? 'Edit message'
                      : editTimeRemaining < 60
                        ? `${editTimeRemaining}s remaining to edit`
                        : `${Math.floor(editTimeRemaining / 60)}m remaining to edit`}
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            )}

            {allowStoryEditing && !isUser && !isGenerating && (
              <ActionButton
                onClick={handleRetryClick}
                title="Regenerate response"
                icon={RefreshCw}
              />
            )}

            {/* TTS controls for assistant messages */}
            {!isUser && displayContent && (
              <TTSControls
                messageId={message.id}
                content={displayContent}
                character={messageVoiceProfile(message)}
                isStreaming={isGenerating}
                compact
              />
            )}

            <ActionButton
              onClick={handleToggleExpanded}
              title={isExpanded ? "Collapse" : "Expand"}
              icon={isExpanded ? X : Maximize2}
            />

            {allowStoryEditing && <ActionButton
              onClick={handleDeleteClick}
              title="Delete message"
              icon={Trash2}
              variant="danger"
            />}
          </div>
        </div>
      </div>
    </div>
  );
}, (prevProps, nextProps) => {
  // Custom comparison function for React.memo
  // Returns true if props are equal (should NOT re-render)

  if (prevProps.allowStoryEditing !== nextProps.allowStoryEditing) return false;

  // Always re-render if message content changed (streaming)
  if (prevProps.message.content !== nextProps.message.content) return false;

  // Always re-render if status changed
  if (prevProps.message.status !== nextProps.message.status) return false;

  // Re-render if message ID changed (different message)
  if (prevProps.message.id !== nextProps.message.id) return false;

  // Re-render if generationStats changed (completion stats)
  if (prevProps.message.generationStats !== nextProps.message.generationStats) return false;

  if (prevProps.message.battleId !== nextProps.message.battleId) return false;

  // Re-render if messageType changed (e.g., refetch corrects type)
  if (prevProps.message.messageType !== nextProps.message.messageType) return false;

  // For non-streaming messages, skip re-render if other props are same
  if (prevProps.isGroupChat !== nextProps.isGroupChat) return false;
  if (prevProps.isMultiUser !== nextProps.isMultiUser) return false;
  if (prevProps.currentUserId !== nextProps.currentUserId) return false;
  if (prevProps.historicalEditing !== nextProps.historicalEditing) return false;

  // Props are equal, skip re-render
  return true;
});
MessageItem.displayName = 'MessageItem';
