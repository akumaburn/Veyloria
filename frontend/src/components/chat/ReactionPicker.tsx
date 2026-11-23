import { useState, useRef, useEffect } from 'react';
import { useMutation } from '@apollo/client';
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
  const pickerRef = useRef<HTMLDivElement>(null);

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

  // Close picker when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

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
    <div ref={pickerRef} className="relative inline-flex items-center gap-1">
      {/* Existing reactions display */}
      {reactionGroups.length > 0 && (
        <div className="flex items-center gap-1 flex-wrap">
          {reactionGroups.map((reaction) => (
            <button
              key={reaction.reactionType}
              onClick={() => handleReaction(reaction.reactionType)}
              disabled={isLoading}
              className={cn(
                'inline-flex items-center gap-1 rounded-full px-2 py-0.5',
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
            >
              <span>{reaction.reactionType}</span>
              <span className="text-2xs">{reaction.count}</span>
            </button>
          ))}
        </div>
      )}

      {/* Add reaction button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        disabled={isLoading}
        className={cn(
          'rounded-full p-1',
          tone === 'primary'
            ? 'text-primary-foreground hover:bg-primary-foreground/10'
            : 'text-muted-foreground hover:text-foreground hover:bg-muted',
          'transition-colors duration-150',
          isLoading && 'opacity-50 cursor-not-allowed'
        )}
        title="Add reaction"
      >
        {isLoading ? (
          <Loader2 size={14} className="animate-spin" />
        ) : (
          <SmilePlus size={14} />
        )}
      </button>

      {/* Reaction picker popup */}
      {isOpen && (
        <div
          className={cn(
            'absolute bottom-full left-0 mb-1 z-50',
            'flex items-center gap-1 p-1.5 rounded-xl',
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
                onClick={() => handleReaction(emoji)}
                disabled={isLoading}
                className={cn(
                  'p-1.5 rounded-lg text-lg',
                  'hover:bg-muted transition-colors duration-150',
                  isSelected && 'bg-primary/20',
                  isLoading && 'opacity-50 cursor-not-allowed'
                )}
                title={isSelected ? 'Remove reaction' : 'Add reaction'}
              >
                {emoji}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};
