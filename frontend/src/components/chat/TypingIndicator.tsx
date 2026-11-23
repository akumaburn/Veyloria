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

  return (
    <div className={cn(
      'flex items-center gap-3 px-4 py-3 mx-4 mb-2',
      'rounded-xl bg-card/50 border border-border/30',
      'backdrop-blur-sm animate-fade-up'
    )}>
      {/* Avatar */}
      <div className={cn(
        'flex h-8 w-8 items-center justify-center rounded-lg',
        'bg-gradient-to-br from-muted to-accent'
      )}>
        <Bot size={16} className="text-muted-foreground" />
      </div>

      {/* Typing animation */}
      <div className="flex items-center gap-3">
        <div className="flex gap-1">
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
        <span className="text-sm text-muted-foreground">{getTypingText()}</span>
      </div>
    </div>
  );
};
