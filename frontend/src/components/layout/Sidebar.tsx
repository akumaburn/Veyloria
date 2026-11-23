import * as Dialog from '@radix-ui/react-dialog';
import { useState, useMemo, useEffect, useRef } from 'react';
import { Plus, Search, MessageSquare, Bot, Trash2, X, Clock, FileSearch } from 'lucide-react';
import { useNavigate, useLocation } from 'react-router-dom';
import { cn, normalizeTimestamp } from '@/lib/utils';
import { useConversationStore } from '@/stores/conversationStore';
import { useTTSStore } from '@/stores/ttsStore';
import { formatDistanceToNow, isToday, isYesterday, isThisWeek } from 'date-fns';
import { toast } from 'react-hot-toast';
import { MessageSearchModal } from '@/components/chat/MessageSearchModal';

interface Conversation {
  id: string;
  title: string;
  lastMessage?: string;
  lastMessageTime?: string;
  lastMessageAt?: Date;
  characterId?: string;
  messageCount: number;
}

interface SidebarProps {
  isOpen: boolean;
  isCollapsed?: boolean;
  onClose: () => void;
}

type ConversationGroup = {
  label: string;
  conversations: Conversation[];
};

export const Sidebar = ({ isOpen, onClose, isCollapsed = false }: SidebarProps) => {
  const [isMobile, setIsMobile] = useState(() => !window.matchMedia('(min-width: 1024px)').matches);
  useEffect(() => {
    const query = window.matchMedia('(min-width: 1024px)');
    const update = () => setIsMobile(!query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  const navigate = useNavigate();
  const location = useLocation();
  const latestPath = useRef(location.pathname);
  const routeExit = useRef<{ from: string; resolve: () => void } | null>(null);
  useEffect(() => {
    latestPath.current = location.pathname;
    if (routeExit.current && location.pathname !== routeExit.current.from) {
      routeExit.current.resolve();
      routeExit.current = null;
    }
  }, [location.pathname]);
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedForDelete, setSelectedForDelete] = useState<string | null>(null);
  const [isSearchModalOpen, setIsSearchModalOpen] = useState(false);

  // Get current conversation ID from URL
  const currentConversationId = location.pathname.split('/chat/')[1];

  // Use conversation store (single source of truth)
  const {
    conversations: storeConversations,
    isLoading: loading,
    hasLoaded,
    deleteConversation,
    fetchConversations,
    setSelectedConversation,
  } = useConversationStore();

  // Get TTS stopAll for cleanup when switching/creating conversations
  const { stopAll: stopAllTTS } = useTTSStore();

  // Load once. Reacting to an empty list refetched the moment a delete
  // emptied it, and that fetch could reach the server before the delete
  // committed, which put the deleted row back on screen until a reload.
  useEffect(() => {
    if (!hasLoaded) {
      fetchConversations();
    }
  }, [hasLoaded, fetchConversations]);

  // Transform store data to component format
  const conversations = useMemo(() => {
    return storeConversations.map((conv: any) => ({
      id: conv.id,
      title: conv.title || `Conversation ${conv.id.slice(0, 8)}`,
      lastMessage: conv.lastMessage?.content,
      lastMessageTime: conv.updatedAt
        ? formatDistanceToNow(new Date(normalizeTimestamp(conv.updatedAt)), { addSuffix: true })
        : undefined,
      lastMessageAt: conv.updatedAt ? new Date(normalizeTimestamp(conv.updatedAt)) : undefined,
      characterId: conv.character?.id,
      messageCount: conv.messageCount || 0,
    }));
  }, [storeConversations]);

  // Group conversations by time
  const groupedConversations = useMemo(() => {
    const filtered = conversations.filter((conv: Conversation) =>
      conv.title.toLowerCase().includes(searchTerm.toLowerCase()) ||
      conv.lastMessage?.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const groups: ConversationGroup[] = [];
    const today: Conversation[] = [];
    const yesterday: Conversation[] = [];
    const thisWeek: Conversation[] = [];
    const older: Conversation[] = [];

    filtered.forEach((conv: Conversation) => {
      if (!conv.lastMessageAt) {
        older.push(conv);
        return;
      }

      if (isToday(conv.lastMessageAt)) {
        today.push(conv);
      } else if (isYesterday(conv.lastMessageAt)) {
        yesterday.push(conv);
      } else if (isThisWeek(conv.lastMessageAt)) {
        thisWeek.push(conv);
      } else {
        older.push(conv);
      }
    });

    if (today.length > 0) groups.push({ label: 'Today', conversations: today });
    if (yesterday.length > 0) groups.push({ label: 'Yesterday', conversations: yesterday });
    if (thisWeek.length > 0) groups.push({ label: 'This Week', conversations: thisWeek });
    if (older.length > 0) groups.push({ label: 'Older', conversations: older });

    return groups;
  }, [conversations, searchTerm]);

  const handleNewConversation = () => {
    // If we're already at /chat (new conversation mode with no ID), just close the sidebar
    // This prevents confusion - user is already in "new conversation" mode
    if (location.pathname === '/chat') {
      onClose();
      return;
    }

    // Stop any TTS playback immediately when starting a new conversation
    stopAllTTS();

    // Clear the selected conversation in the store BEFORE navigating
    // This ensures ChatInterface knows we want a new conversation even if
    // React hasn't re-rendered yet after navigation
    setSelectedConversation(null);

    // Navigate to /chat to start a new conversation
    // The actual conversation creation happens in ChatInterface when user sends first message
    navigate('/chat', { replace: true });
    onClose();
  };

  const handleSelectConversation = (conversationId: string) => {
    // Stop any TTS playback immediately when switching conversations
    stopAllTTS();
    navigate(`/chat/${conversationId}`);
    onClose();
  };

  const handleDeleteConversation = async (e: React.MouseEvent, conversationId: string) => {
    e.stopPropagation();

    if (selectedForDelete === conversationId) {
      const deletingCurrent = currentConversationId === conversationId;
      // Unmount active queries before cache eviction can refetch the deleted ID.
      if (deletingCurrent) {
        stopAllTTS();
        await new Promise<void>(resolve => {
          routeExit.current = { from: location.pathname, resolve };
          navigate('/chat', { replace: true });
        });
      }
      try {
        await deleteConversation(conversationId);
        setSelectedForDelete(null);
        toast.success('Conversation deleted');

      } catch (error) {
        if (deletingCurrent && latestPath.current === '/chat') {
          navigate(`/chat/${conversationId}`, { replace: true });
        }
        console.error('Failed to delete conversation:', error);
        toast.error('Failed to delete conversation');
      }
    } else {
      setSelectedForDelete(conversationId);
      setTimeout(() => setSelectedForDelete(null), 3000);
    }
  };

  const sidebar = (
      <aside
        id={isMobile ? 'app-sidebar' : undefined}
        aria-label="Conversation history"
        className={cn(
          'fixed inset-y-0 left-0 z-50 h-dvh w-60 max-w-[calc(100vw-3rem)] shrink-0',
          'lg:relative lg:left-0 lg:top-0 lg:z-0 lg:h-full lg:max-w-none',
          'border-r border-border',
          'bg-background-secondary/95 backdrop-blur-md lg:bg-background-secondary',
          'transform transition-transform duration-300 ease-smooth',
          isOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
      >
        <div className="flex h-full min-h-0 flex-col">
          <div className="flex items-center gap-2 p-3">
            <button type="button" onClick={handleNewConversation} className="flex min-h-9 flex-1 items-center justify-center gap-2 rounded-md bg-primary px-3 py-2 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary-hover"><Plus size={16} />New chat</button>
            <button type="button" onClick={onClose} className="workspace-icon-button lg:hidden" aria-label="Close conversations"><X size={18} /></button>
          </div>

          {/* Search */}
          <div className="px-3 pb-2">
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={16} />
                <input
                  aria-label="Filter conversations"
                  type="search"
                  placeholder="Search chats..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className={cn(
                    'w-full rounded-lg border border-border bg-muted/50',
                    'py-2 pl-9 pr-2 text-xs text-foreground placeholder-muted-foreground',
                    'focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary/50',
                    'transition-all duration-150'
                  )}
                />
              </div>
              <button
                onClick={() => setIsSearchModalOpen(true)}
                className={cn(
                  'flex-shrink-0 rounded-lg border border-border bg-muted/50 p-2',
                  'text-muted-foreground hover:text-foreground hover:bg-accent',
                  'transition-colors duration-150'
                )}
                aria-label="Search all messages"
                title="Search all messages"
              >
                <FileSearch size={16} />
              </button>
            </div>
          </div>

          {/* Conversations list */}
          <div className="min-h-0 flex-1 overflow-y-auto custom-scrollbar px-2 pb-2">
            {loading ? (
              <div className="space-y-2 p-2">
                {[...Array(5)].map((_, i) => (
                  <div key={i} className="animate-pulse rounded-lg bg-muted/30 h-16" />
                ))}
              </div>
            ) : groupedConversations.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 px-4">
                <div className="rounded-2xl bg-muted/30 p-4 mb-4">
                  <MessageSquare size={32} className="text-muted-foreground" />
                </div>
                <p className="text-xs font-medium text-foreground">
                  {searchTerm ? 'No conversations found' : 'No conversations yet'}
                </p>
                <p className="text-xs text-muted-foreground mt-1 text-center">
                  {searchTerm
                    ? 'Try a different search term'
                    : 'Start a new conversation to begin'}
                </p>
              </div>
            ) : (
              <div className="space-y-2">
                {groupedConversations.map((group) => (
                  <div key={group.label}>
                    <div className="flex items-center gap-2 px-2 py-1.5">
                      <Clock size={12} className="text-muted-foreground" />
                      <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">
                        {group.label}
                      </span>
                    </div>

                    <div className="space-y-1">
                      {group.conversations.map((conversation: Conversation) => (
                        <div
                          key={conversation.id}
                          className={cn(
                            'group relative w-full rounded-xl text-left',
                            'transition-all duration-150',
                            currentConversationId === conversation.id
                              ? 'bg-accent shadow-sm'
                              : 'hover:bg-accent/50'
                          )}
                        >
                          <button
                            type="button"
                            onClick={() => handleSelectConversation(conversation.id)}
                            aria-label={`Open ${conversation.title}`}
                            aria-current={currentConversationId === conversation.id ? 'page' : undefined}
                            className="flex w-full items-start gap-2 rounded-lg px-2 py-2 text-left"
                          >
                            <div className={cn(
                              'mt-0.5 rounded-lg p-1.5',
                              conversation.characterId
                                ? 'bg-secondary/20 text-secondary'
                                : 'bg-primary/20 text-primary'
                            )}>
                              {conversation.characterId ? (
                                <Bot size={14} />
                              ) : (
                                <MessageSquare size={14} />
                              )}
                            </div>

                            <div className="min-w-0 flex-1 pr-6">
                              <h3 className={cn(
                                'truncate text-xs font-medium',
                                currentConversationId === conversation.id
                                  ? 'text-foreground'
                                  : 'text-foreground/90'
                              )}>
                                {conversation.title}
                              </h3>
                            </div>
                          </button>

                          {/* Delete button */}
                          <button
                            onClick={(e) => handleDeleteConversation(e, conversation.id)}
                            className={cn(
                              'absolute right-2 top-1/2 -translate-y-1/2 rounded-lg p-1.5',
                              'transition-all duration-150',
                              selectedForDelete === conversation.id
                                ? 'bg-destructive text-destructive-foreground opacity-100'
                                : 'opacity-100 lg:opacity-0 hover:bg-destructive/10 hover:text-destructive group-hover:opacity-100 group-focus-within:opacity-100'
                            )}
                            aria-label={selectedForDelete === conversation.id ? `Confirm deletion of ${conversation.title}` : `Delete ${conversation.title}`}
                            title={
                              selectedForDelete === conversation.id
                                ? 'Click again to confirm'
                                : 'Delete conversation'
                            }
                          >
                            <Trash2 size={14} />
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>


        </div>
      </aside>
  );

  return (
    <>
      {isMobile ? (
        <Dialog.Root open={isOpen} onOpenChange={(open) => { if (!open) onClose(); }}>
          <Dialog.Portal>
            <Dialog.Overlay className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm" />
            <Dialog.Content asChild aria-describedby={undefined} onCloseAutoFocus={(event) => {
              event.preventDefault();
              document.getElementById('conversation-toggle-mobile')?.focus();
            }}>
              {sidebar}
            </Dialog.Content>
            <Dialog.Title className="sr-only">Your conversations</Dialog.Title>
          </Dialog.Portal>
        </Dialog.Root>
      ) : <div id="app-sidebar" hidden={isCollapsed} className="h-full shrink-0">{sidebar}</div>}

      <MessageSearchModal isOpen={isSearchModalOpen} onClose={() => setIsSearchModalOpen(false)} />
    </>
  );
};
