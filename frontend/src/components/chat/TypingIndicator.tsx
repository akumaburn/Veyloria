import { useMemo } from 'react';
import { useTypingIndicator } from '@/hooks/useGraphQL';
import { cn } from '@/lib/utils';
import { Bot } from 'lucide-react';

interface TypingIndicatorProps {
  conversationId?: string;
}

export const TypingIndicator = ({ conversationId }: TypingIndicatorProps = {}) => {
  // Use GraphQL subscription
  const { typingUsers } = useTypingIndicator(conversationId || '');

  // typingUsers is a string[] of usernames (see useTypingIndicator)
  const typingUsersList = useMemo(() => {
    return typingUsers.map((username, index) => ({
      id: `user-${index}`,
      displayName: username,
    }));
  }, [typingUsers]);

  if (typingUsersList.length === 0) return null;

  const getTypingText = () => {
    if (typingUsersList.length === 1) {
      return `${typingUsersList[0].displayName} is typing`;
    } else if (typingUsersList.length === 2) {
      return `${typingUsersList[0].displayName} and ${typingUsersList[1].displayName} are typing`;
    } else {
      return `${typingUsersList[0].displayName} and ${typingUsersList.length - 1} others are typing`;
    }
  };

  // The transcript fills its region and scrolls inside it, so the indicator
  // floats over the transcript's foot instead of being laid out below it.
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 px-3 pb-2 sm:px-4">
      <div
        role="status"
        className={cn(
          'mx-auto flex w-fit max-w-3xl items-center gap-3 px-3 py-2 sm:px-4 sm:py-3',
          'rounded-xl bg-card border border-border shadow-lg',
          'animate-fade-up'
        )}
      >
        <div className={cn(
          'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg',
          'bg-gradient-to-br from-muted to-accent'
        )}>
          <Bot size={16} className="text-muted-foreground" />
        </div>

        <div className="flex min-w-0 items-center gap-3">
          <div className="flex shrink-0 gap-1">
            <div
              className="h-2 w-2 rounded-full bg-primary animate-typing-dot"
              style={{ animationDelay: '0ms' }}
            />
            <div
              className="h-2 w-2 rounded-full bg-primary animate-typing-dot"
              style={{ animationDelay: '150ms' }}
            />
            <div
              className="h-2 w-2 rounded-full bg-primary animate-typing-dot"
              style={{ animationDelay: '300ms' }}
            />
          </div>
          <span className="truncate text-sm text-muted-foreground">{getTypingText()}</span>
        </div>
      </div>
    </div>
  );
};
