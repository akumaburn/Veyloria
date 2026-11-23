import { useEffect, useState, useMemo, useCallback } from 'react';
import {
  MessageSquare, Bot, Clock, Edit2, Trash2, Pin, Archive,
  Filter, ChevronDown, Users, Hash
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { cn, formatDate, normalizeTimestamp, truncateText, toTimestamp } from '@/lib/utils';
import { useConversationStore } from '@/stores/conversationStore';
import { useTTSStore } from '@/stores/ttsStore';

interface ConversationListProps {
  searchTerm?: string;
  filter?: 'all' | 'recent' | 'pinned' | 'archived' | 'character';
  characterId?: string;
  onSelectConversation: (conversationId: string) => void;
  className?: string;
}

export const ConversationList = ({
  searchTerm = '',
  filter = 'all',
  characterId,
  onSelectConversation,
  className
}: ConversationListProps) => {
  const navigate = useNavigate();
  const {
    conversations,
    selectedConversationId,
    isLoading,
    fetchConversations,
    deleteConversation,
    updateConversation,
    setSelectedConversation
  } = useConversationStore();

  const [sortBy, setSortBy] = useState<'recent' | 'alphabetical' | 'messages'>('recent');
  const [showSortMenu, setShowSortMenu] = useState(false);
  const [selectedForDelete, setSelectedForDelete] = useState<string | null>(null);

  // Get TTS stopAll for cleanup when switching conversations
  const { stopAll: stopAllTTS } = useTTSStore();

  useEffect(() => {
    void fetchConversations();
  }, [fetchConversations]);

  /**
   * OPTIMIZED: Reduced array allocations and improved sorting efficiency.
   * Previous: 5 array allocations (spread, 3 filters, final spread)
   * Now: 1 array allocation with single-pass categorization
   */
  const filteredConversations = useMemo(() => {
    // First pass: filter conversations based on search and category
    // Avoid [...conversations] spread - filter creates a new array anyway
    let filtered = conversations;

    // Apply search filter
    if (searchTerm) {
      const term = searchTerm.toLowerCase();
      filtered = filtered.filter((conv) =>
        conv.title.toLowerCase().includes(term) ||
        conv.lastMessage?.content.toLowerCase().includes(term) ||
        conv.character?.name.toLowerCase().includes(term) ||
        // Also search group chat characters
        (conv.isGroupChat && conv.characters?.some(
          (c) => c.character?.name?.toLowerCase().includes(term)
        ))
      );
    }

    // Apply category filter
    switch (filter) {
      case 'recent': {
        const oneDayAgo = Date.now() - 24 * 60 * 60 * 1000;
        filtered = filtered.filter((conv) =>
          toTimestamp(conv.updatedAt) > oneDayAgo
        );
        }
        break;
      case 'pinned':
        filtered = filtered.filter((conv) => conv.isPinned);
        break;
      case 'archived':
        filtered = filtered.filter((conv) => conv.isArchived);
        break;
      case 'character':
        if (characterId) {
          filtered = filtered.filter((conv) =>
            conv.character?.id === characterId ||
            (conv.isGroupChat && conv.characters?.some(c => c.character?.id === characterId))
          );
        } else {
          filtered = filtered.filter((conv) =>
            (conv.character !== undefined && conv.character !== null) ||
            (conv.isGroupChat && conv.characters && conv.characters.length > 0)
          );
        }
        break;
    }

    // OPTIMIZED: Single sort with custom comparator that handles pinned/archived grouping
    // Previous: Sorted then created 3 filtered arrays then spread them (4 allocations)
    // Now: Single sort that groups by category and applies secondary sort (1 allocation)

    // Pre-parse dates for 'recent' sort to avoid parsing in comparator
    const dateCache = sortBy === 'recent'
      ? new Map(filtered.map(c => [c.id, toTimestamp(c.updatedAt)]))
      : null;

    // Create single sorted array with grouping built into comparator
    return [...filtered].sort((a, b) => {
      // First: Group by category (pinned non-archived > regular > archived)
      const aCategory = a.isArchived ? 2 : (a.isPinned ? 0 : 1);
      const bCategory = b.isArchived ? 2 : (b.isPinned ? 0 : 1);
      if (aCategory !== bCategory) return aCategory - bCategory;

      // Then: Apply secondary sort within category
      switch (sortBy) {
        case 'alphabetical':
          return a.title.localeCompare(b.title);
        case 'messages':
          return b.messageCount - a.messageCount;
        case 'recent':
        default:
          // Use cached dates to avoid parsing in comparator
          return (dateCache?.get(b.id) ?? 0) - (dateCache?.get(a.id) ?? 0);
      }
    });
  }, [conversations, searchTerm, filter, characterId, sortBy]);

  // OPTIMIZED: Memoize handlers to prevent unnecessary re-renders
  const handleSelectConversation = useCallback((conversationId: string) => {
    // Stop any TTS playback immediately when switching conversations
    // This provides instant audio cutoff rather than waiting for navigation to complete
    stopAllTTS();
    setSelectedConversation(conversationId);
    onSelectConversation(conversationId);
    navigate(`/chat/${conversationId}`);
  }, [stopAllTTS, setSelectedConversation, onSelectConversation, navigate]);

  const handlePinConversation = useCallback(async (conversationId: string, isPinned: boolean) => {
    await updateConversation(conversationId, { isPinned: !isPinned });
  }, [updateConversation]);

  const handleArchiveConversation = useCallback(async (conversationId: string, isArchived: boolean) => {
    await updateConversation(conversationId, { isArchived: !isArchived });
  }, [updateConversation]);

  const handleDeleteConversation = useCallback(async (conversationId: string) => {
    if (selectedForDelete === conversationId) {
      // Capture whether this is the current conversation BEFORE deleting
      // (deleteConversation sets selectedConversationId to null synchronously)
      const isCurrentConversation = selectedConversationId === conversationId;

      await deleteConversation(conversationId);
      setSelectedForDelete(null);

      // Navigate away if we deleted the currently viewed conversation
      if (isCurrentConversation) {
        navigate('/chat');
      }
    } else {
      setSelectedForDelete(conversationId);
      setTimeout(() => setSelectedForDelete(null), 3000);
    }
  }, [selectedForDelete, deleteConversation, selectedConversationId, navigate]);

  const handleRenameConversation = useCallback(async (conversationId: string) => {
    const conversation = conversations.find((c) => c.id === conversationId);
    if (!conversation) return;

    const newTitle = prompt('Enter new title:', conversation.title);
    if (newTitle && newTitle !== conversation.title) {
      await updateConversation(conversationId, { title: newTitle });
    }
  }, [conversations, updateConversation]);

  // Memoized icon selector
  const getConversationIcon = useCallback((conversation: any) => {
    if (conversation.character) {
      return <Bot className="text-primary" size={18} />;
    } else if (conversation.isGroupChat) {
      return <Users className="text-success" size={18} />;
    } else {
      return <MessageSquare className="text-primary" size={18} />;
    }
  }, []);

  // OPTIMIZED: Memoize stats to avoid recalculating on every render
  const stats = useMemo(() => {
    const total = conversations.length;
    const unread = 0; // Not supported by backend currently
    // Use today's date string once, not in filter loop
    const todayStr = new Date().toDateString();
    const today = conversations.filter((c) =>
      new Date(normalizeTimestamp(c.updatedAt)).toDateString() === todayStr
    ).length;

    return { total, unread, today };
  }, [conversations]);

  if (isLoading && conversations.length === 0) {
    return (
      <div className={cn('flex items-center justify-center py-8', className)}>
        <div className="text-center">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-border border-t-primary" />
          <p className="mt-2 text-sm text-muted-foreground">Loading conversations...</p>
        </div>
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col', className)}>
      {/* Stats bar */}
      <div className="mb-3 flex items-center justify-between border-b border-border pb-3">
        <div className="flex items-center space-x-4 text-xs text-muted-foreground">
          <span>{stats.total} total</span>
          {stats.unread > 0 && (
            <span className="text-primary">{stats.unread} unread</span>
          )}
          {stats.today > 0 && (
            <span className="text-success">{stats.today} today</span>
          )}
        </div>

        {/* Sort menu */}
        <div className="relative">
          <button
            onClick={() => setShowSortMenu(!showSortMenu)}
            className="flex items-center space-x-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <Filter size={14} />
            <span>Sort</span>
            <ChevronDown size={14} />
          </button>

          {showSortMenu && (
            <>
              <div
                className="fixed inset-0 z-10"
                onClick={() => setShowSortMenu(false)}
              />
              <div className="absolute right-0 z-20 mt-1 w-32 rounded-lg border border-border bg-card py-1 shadow-lg">
                {['recent', 'alphabetical', 'messages'].map((sort) => (
                  <button
                    key={sort}
                    onClick={() => {
                      setSortBy(sort as any);
                      setShowSortMenu(false);
                    }}
                    className={cn(
                      'w-full px-3 py-1.5 text-left text-xs capitalize transition-colors hover:bg-accent',
                      sortBy === sort ? 'text-primary' : 'text-muted-foreground'
                    )}
                  >
                    {sort === 'messages' ? 'Message Count' : sort}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Conversation list */}
      <div className="space-y-1 overflow-y-auto">
        {filteredConversations.map((conversation) => (
          <div
            key={conversation.id}
            className={cn(
              'group relative rounded-lg transition-colors hover:bg-accent',
              selectedConversationId === conversation.id && 'bg-accent',
              conversation.isArchived && 'opacity-60'
            )}
          >
            <button
              onClick={() => handleSelectConversation(conversation.id)}
              className="w-full px-3 py-3 text-left"
            >
              <div className="flex items-start space-x-3">
                <div className="mt-0.5 flex-shrink-0">
                  {getConversationIcon(conversation)}
                </div>

                <div className="min-w-0 flex-1">
                  <div className="flex items-center space-x-2">
                    {conversation.isPinned && (
                      <Pin className="text-warning" size={12} fill="currentColor" />
                    )}
                    <h3 className="truncate text-sm font-medium text-foreground">
                      {conversation.title}
                    </h3>
                  </div>

                  {conversation.lastMessage && (
                    <p className="mt-1 truncate text-xs text-muted-foreground">
                      {truncateText(conversation.lastMessage.content, 50)}
                    </p>
                  )}

                  <div className="mt-1 flex items-center space-x-3 text-xs text-muted-foreground">
                    <span className="flex items-center space-x-1">
                      <Clock size={10} />
                      <span>{formatDate(conversation.updatedAt)}</span>
                    </span>
                    <span className="flex items-center space-x-1">
                      <Hash size={10} />
                      <span>{conversation.messageCount}</span>
                    </span>
                    {conversation.isGroupChat && conversation.characters && conversation.characters.length > 0 ? (
                      <span className="flex items-center space-x-1">
                        <Users size={10} />
                        <span>
                          {conversation.characters.length} character{conversation.characters.length !== 1 ? 's' : ''}
                        </span>
                      </span>
                    ) : conversation.character && (
                      <span className="flex items-center space-x-1">
                        <Bot size={10} />
                        <span>{conversation.character.name}</span>
                      </span>
                    )}
                  </div>
                </div>
              </div>
            </button>

            {/* Action buttons */}
            <div className="absolute right-2 top-1/2 -translate-y-1/2 opacity-0 transition-opacity group-hover:opacity-100">
              <div className="flex items-center space-x-1 rounded-lg bg-card border border-border p-1 shadow-lg">
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handlePinConversation(conversation.id, conversation.isPinned);
                  }}
                  className={cn(
                    'rounded p-1 transition-colors hover:bg-accent',
                    conversation.isPinned ? 'text-warning' : 'text-muted-foreground hover:text-foreground'
                  )}
                  title={conversation.isPinned ? 'Unpin' : 'Pin'}
                >
                  <Pin size={14} />
                </button>

                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleArchiveConversation(conversation.id, conversation.isArchived);
                  }}
                  className={cn(
                    'rounded p-1 transition-colors hover:bg-accent',
                    conversation.isArchived ? 'text-success' : 'text-muted-foreground hover:text-foreground'
                  )}
                  title={conversation.isArchived ? 'Unarchive' : 'Archive'}
                >
                  <Archive size={14} />
                </button>

                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleRenameConversation(conversation.id);
                  }}
                  className="rounded p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                  title="Rename"
                >
                  <Edit2 size={14} />
                </button>

                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteConversation(conversation.id);
                  }}
                  className={cn(
                    'rounded p-1 transition-colors hover:bg-accent',
                    selectedForDelete === conversation.id
                      ? 'text-destructive'
                      : 'text-muted-foreground hover:text-destructive'
                  )}
                  title={selectedForDelete === conversation.id ? 'Click again to confirm' : 'Delete'}
                >
                  <Trash2 size={14} />
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {filteredConversations.length === 0 && (
        <div className="py-8 text-center">
          <MessageSquare className="mx-auto mb-3 text-muted-foreground" size={32} />
          <p className="text-sm text-muted-foreground">
            {searchTerm ? 'No conversations found' : 'No conversations yet'}
          </p>
          <button
            onClick={() => navigate('/chat/new')}
            className="mt-3 text-sm text-primary hover:text-primary-hover"
          >
            Start a new conversation
          </button>
        </div>
      )}
    </div>
  );
};