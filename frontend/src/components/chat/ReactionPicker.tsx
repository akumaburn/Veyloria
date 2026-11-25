import { useState } from 'react';
import { useMutation } from '@apollo/client';
import * as Popover from '@radix-ui/react-popover';
import { SmilePlus, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { REACT_TO_MESSAGE_MUTATION, REMOVE_REACTION_MUTATION } from '@/graphql/operations';
import { ReactionSummary } from '@/graphql/types';

// Common emoji reactions
const COMMON_REACTIONS = ['👍', '👎', '❤️', '😂', '😮', '😢', '🎉', '🤔'];

interface ReactionPickerProps {
  messageId: string;
  reactions?: ReactionSummary[];
  onReactionChange?: () => void;
  tone?: 'default' | 'primary';
}

export const ReactionPicker = ({
  messageId,
  reactions = [],
  onReactionChange,
  tone = 'default',
}: ReactionPickerProps) => {
  const [isOpen, setIsOpen] = useState(false);
  const [isAnimating, setIsAnimating] = useState(false);

  const [reactToMessage, { loading: reacting }] = useMutation(REACT_TO_MESSAGE_MUTATION, {
    onCompleted: () => {
      setIsAnimating(true);
      setTimeout(() => setIsAnimating(false), 300);
      onReactionChange?.();
    },
  });

  const [removeReaction, { loading: removing }] = useMutation(REMOVE_REACTION_MUTATION, {
    onCompleted: () => {
      onReactionChange?.();
    },
  });

  const handleReaction = async (emoji: string) => {
    const existingReaction = reactions.find(
      (r) => r.reactionType === emoji && r.userReacted
    );

    if (existingReaction) {
      // Remove reaction
      await removeReaction({
        variables: { messageId, reaction: emoji },
      });
    } else {
      // Add reaction
      await reactToMessage({
        variables: { messageId, reaction: emoji },
      });
    }

    setIsOpen(false);
  };

  const isLoading = reacting || removing;

  // Group reactions by type
  const reactionGroups = reactions.filter((r) => r.count > 0);

  return (
    <div className="inline-flex flex-wrap items-center gap-1">
      {/* Existing reactions display */}
      {reactionGroups.map((reaction) => (
        <button
          key={reaction.reactionType}
          type="button"
          onClick={() => handleReaction(reaction.reactionType)}
          disabled={isLoading}
          className={cn(
            'inline-flex items-center gap-1 rounded-full px-2 py-0.5 max-md:min-h-8 max-md:px-2.5',
            'text-xs font-medium transition-all duration-150',
            'border',
            tone === 'primary'
              ? cn('text-primary-foreground hover:bg-primary-foreground/10', reaction.userReacted
                ? 'bg-primary-foreground/10 border-primary-foreground/70'
                : 'bg-primary-foreground/5 border-primary-foreground/30')
              : reaction.userReacted
                ? 'bg-primary/20 border-primary/50 text-primary'
                : 'bg-muted border-border/50 text-muted-foreground hover:bg-accent',
            isLoading && 'opacity-50 cursor-not-allowed',
            isAnimating && 'animate-scale-in'
          )}
          title={reaction.userReacted ? 'Remove reaction' : 'Add reaction'}
          aria-label={`${reaction.userReacted ? 'Remove' : 'Add'} ${reaction.reactionType} reaction, ${reaction.count} total`}
          aria-pressed={reaction.userReacted}
        >
          <span>{reaction.reactionType}</span>
          <span className="text-2xs">{reaction.count}</span>
        </button>
      ))}

      <Popover.Root open={isOpen} onOpenChange={setIsOpen}>
        <Popover.Trigger asChild>
          <button
            type="button"
            disabled={isLoading}
            className={cn(
              'inline-flex items-center justify-center rounded-full p-1 max-md:-my-2 max-md:h-10 max-md:w-10',
              tone === 'primary'
                ? 'text-primary-foreground hover:bg-primary-foreground/10'
                : 'text-muted-foreground hover:text-foreground hover:bg-muted',
              'transition-colors duration-150',
              isLoading && 'opacity-50 cursor-not-allowed'
            )}
            title="Add reaction"
            aria-label="Add reaction"
          >
            {isLoading ? (
              <Loader2 size={14} className="animate-spin" />
            ) : (
              <SmilePlus size={14} />
            )}
          </button>
        </Popover.Trigger>

        <Popover.Portal>
          <Popover.Content
            side="top"
            align="start"
            sideOffset={4}
            collisionPadding={16}
            aria-label="Choose a reaction"
            className={cn(
              'z-50 grid grid-cols-4 gap-1 p-1.5 rounded-xl sm:flex sm:items-center',
              'bg-card border border-border/50 shadow-lg',
              'animate-scale-in'
            )}
          >
            {COMMON_REACTIONS.map((emoji) => {
              const isSelected = reactions.some(
                (r) => r.reactionType === emoji && r.userReacted
              );
              return (
                <button
                  key={emoji}
                  type="button"
                  onClick={() => handleReaction(emoji)}
                  disabled={isLoading}
                  className={cn(
                    'p-1.5 rounded-lg text-lg',
                    'hover:bg-muted transition-colors duration-150',
                    isSelected && 'bg-primary/20',
                    isLoading && 'opacity-50 cursor-not-allowed'
                  )}
                  title={isSelected ? 'Remove reaction' : 'Add reaction'}
                  aria-label={`${isSelected ? 'Remove' : 'Add'} ${emoji} reaction`}
                  aria-pressed={isSelected}
                >
                  {emoji}
                </button>
              );
            })}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    </div>
  );
};
