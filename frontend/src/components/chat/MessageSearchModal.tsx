import { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLazyQuery } from '@apollo/client';
import { Search, MessageSquare, User, Bot, Loader2, X, ArrowRight } from 'lucide-react';
import { cn, normalizeTimestamp } from '@/lib/utils';
import { Modal } from '@/components/ui/Modal';
import { SEARCH_MESSAGES_QUERY } from '@/graphql/operations';
import { MessageSearchResult, MessageSearchPagination } from '@/graphql/types';
import { formatDistanceToNow } from 'date-fns';

interface MessageSearchModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const MessageSearchModal = ({ isOpen, onClose }: MessageSearchModalProps) => {
  const navigate = useNavigate();
  const [searchQuery, setSearchQuery] = useState('');
  const [results, setResults] = useState<MessageSearchResult[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [offset, setOffset] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const limit = 20;

  // Tracks which offset the last request was fired with. Apollo's hook always
  // returns the latest network payload in `data`, so we derive append-vs-replace
  // from the offset captured at request time rather than reading state inside
  // a deprecated onCompleted callback.
  const requestedOffsetRef = useRef(0);
  // Monotonic generation counter. A stale response from an older query
  // (e.g. "hel" landing after "hello") must not overwrite the newer results:
  // each request captures the generation it was fired under and only applies
  // its results if it is still the latest request when it resolves.
  const searchGenerationRef = useRef(0);
  const [searchMessages, { loading, error: searchError }] = useLazyQuery<{
    searchMessages: MessageSearchPagination;
  }>(SEARCH_MESSAGES_QUERY, {
    fetchPolicy: 'network-only',
  });

  useEffect(() => {
    if (searchError) console.error('Search error:', searchError);
  }, [searchError]);

  // Debounced search
  const debouncedSearch = useCallback(
    (query: string) => {
      if (query.trim().length < 2) {
        setResults([]);
        setTotalCount(0);
        setHasMore(false);
        return;
      }

      const generation = ++searchGenerationRef.current;
      setOffset(0);
      requestedOffsetRef.current = 0;
      searchMessages({
        variables: {
          input: {
            query: query.trim(),
            limit,
            offset: 0,
          },
        },
      }).then(
        (result) => {
          // Only the latest request may apply its results; a stale "hel"
          // response landing after "hello" is discarded here.
          if (generation !== searchGenerationRef.current) return;
          const page = result.data?.searchMessages;
          if (!page) return;
          setResults(page.items);
          setTotalCount(page.totalCount);
          setHasMore(page.hasMore);
        },
        () => {}
      );
    },
    [searchMessages]
  );

  // Debounce effect
  useEffect(() => {
    const timer = setTimeout(() => {
      debouncedSearch(searchQuery);
    }, 300);

    return () => clearTimeout(timer);
  }, [searchQuery, debouncedSearch]);

  // Focus input on open
  useEffect(() => {
    if (isOpen && searchInputRef.current) {
      setTimeout(() => searchInputRef.current?.focus(), 100);
    }
  }, [isOpen]);

  // Reset state on close
  useEffect(() => {
    if (!isOpen) {
      setSearchQuery('');
      setResults([]);
      setTotalCount(0);
      setOffset(0);
    }
  }, [isOpen]);

  const loadMore = () => {
    const newOffset = offset + limit;
    const generation = ++searchGenerationRef.current;
    setOffset(newOffset);
    requestedOffsetRef.current = newOffset;
    searchMessages({
      variables: {
        input: {
          query: searchQuery.trim(),
          limit,
          offset: newOffset,
        },
      },
    }).then(
      (result) => {
        // Only apply if no newer search/load-more superseded this request.
        if (generation !== searchGenerationRef.current) return;
        const page = result.data?.searchMessages;
        if (!page) return;
        setResults((prev) => [...prev, ...page.items]);
        setTotalCount(page.totalCount);
        setHasMore(page.hasMore);
      },
      () => {}
    );
  };

  const handleResultClick = (result: MessageSearchResult) => {
    navigate(`/chat/${result.conversation.id}?highlight=${result.id}`);
    onClose();
  };

  const renderHighlightedContent = (content: string, highlighted?: string) => {
    if (!highlighted) {
      return <span className="line-clamp-2">{content}</span>;
    }

    // The backend wraps matches with **markers**
    const parts = highlighted.split(/\*\*([^*]+)\*\*/);
    return (
      <span className="line-clamp-2">
        {parts.map((part, index) => {
          // Odd indices are the matched text
          if (index % 2 === 1) {
            return (
              <mark key={index} className="bg-primary/30 text-foreground rounded px-0.5">
                {part}
              </mark>
            );
          }
          return <span key={index}>{part}</span>;
        })}
      </span>
    );
  };

  const getRoleIcon = (role: string) => {
    switch (role) {
      case 'user':
        return <User size={14} />;
      case 'assistant':
        return <Bot size={14} />;
      default:
        return <MessageSquare size={14} />;
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Search Messages"
      description="Search across all your conversations"
      size="lg"
    >
      <div className="space-y-4">
        {/* Search Input */}
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={18} />
          <input
            ref={searchInputRef}
            type="text"
            placeholder="Search for messages..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className={cn(
              'w-full rounded-xl border border-border bg-muted/50',
              'py-3 pl-10 pr-12 text-foreground placeholder-muted-foreground',
              'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
              'transition-all duration-150'
            )}
          />
          {searchQuery && (
            <button
              type="button"
              onClick={() => setSearchQuery('')}
              aria-label="Clear search"
              className="absolute right-1 top-1/2 inline-flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
            >
              <X size={16} />
            </button>
          )}
        </div>

        {/* Search Results */}
        <div className="min-h-[160px] max-h-[min(400px,calc(100dvh-16rem))] overflow-y-auto overscroll-contain custom-scrollbar sm:min-h-[200px]">
          {loading && results.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12">
              <Loader2 size={32} className="animate-spin text-primary" />
              <p className="mt-2 text-sm text-muted-foreground">Searching...</p>
            </div>
          ) : results.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12">
              <div className="rounded-2xl bg-muted/30 p-4 mb-4">
                <Search size={32} className="text-muted-foreground" />
              </div>
              <p className="text-sm font-medium text-foreground">
                {searchQuery.length < 2
                  ? 'Enter at least 2 characters to search'
                  : 'No messages found'}
              </p>
              <p className="text-xs text-muted-foreground mt-1">
                {searchQuery.length >= 2 && 'Try different keywords'}
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {/* Results count */}
              <div className="px-2 py-1">
                <p className="text-xs text-muted-foreground">
                  Found {totalCount} {totalCount === 1 ? 'result' : 'results'}
                </p>
              </div>

              {/* Result items */}
              {results.map((result) => (
                <div
                  key={result.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => handleResultClick(result)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      handleResultClick(result);
                    }
                  }}
                  className={cn(
                    'group relative rounded-xl p-2.5 cursor-pointer sm:p-3',
                    'border border-transparent',
                    'hover:bg-accent hover:border-border/50',
                    'transition-all duration-150'
                  )}
                >
                  <div className="flex items-start gap-2.5 sm:gap-3">
                    {/* Role icon */}
                    <div
                      className={cn(
                        'mt-0.5 rounded-lg p-2 flex-shrink-0',
                        result.role === 'user'
                          ? 'bg-primary/20 text-primary'
                          : 'bg-secondary/20 text-secondary'
                      )}
                    >
                      {getRoleIcon(result.role)}
                    </div>

                    {/* Content */}
                    <div className="min-w-0 flex-1">
                      {/* Header */}
                      <div className="flex items-center justify-between gap-2 mb-1">
                        <span className="text-sm font-medium text-foreground truncate">
                          {result.displayName || (result.role === 'user' ? 'You' : 'Assistant')}
                        </span>
                        <span className="text-xs text-muted-foreground flex-shrink-0">
                          {formatDistanceToNow(new Date(normalizeTimestamp(result.createdAt)), { addSuffix: true })}
                        </span>
                      </div>

                      {/* Message content */}
                      <div className="text-sm text-muted-foreground">
                        {renderHighlightedContent(result.content, result.highlightedContent)}
                      </div>

                      {/* Conversation info */}
                      <div className="flex min-w-0 items-center gap-2 mt-2">
                        <MessageSquare size={12} className="shrink-0 text-muted-foreground" />
                        <span className="text-xs text-muted-foreground truncate">
                          {result.conversation.title || 'Untitled conversation'}
                        </span>
                      </div>
                    </div>

                    {/* Arrow indicator */}
                    <div className="opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0 max-sm:hidden" aria-hidden="true">
                      <ArrowRight size={16} className="text-muted-foreground" />
                    </div>
                  </div>
                </div>
              ))}

              {/* Load more button */}
              {hasMore && (
                <div className="pt-2 pb-1">
                  <button
                    type="button"
                    onClick={loadMore}
                    disabled={loading}
                    className={cn(
                      'w-full rounded-lg py-2 text-sm font-medium max-sm:min-h-11',
                      'bg-muted text-muted-foreground',
                      'hover:bg-accent hover:text-foreground',
                      'disabled:opacity-50 transition-colors duration-150'
                    )}
                  >
                    {loading ? (
                      <span className="flex items-center justify-center gap-2">
                        <Loader2 size={14} className="animate-spin" />
                        Loading...
                      </span>
                    ) : (
                      `Load more (${results.length} of ${totalCount})`
                    )}
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
};
