import { useState, type ReactNode } from 'react';
import { Bot, Users, Settings, Play, Pause, Hand, X, MessageSquarePlus, StopCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Character } from '@/graphql/types';
import { useCharacterCardStore } from '@/stores/characterCardStore';

interface GroupParticipant {
  id: string;
  character: Character;
  turnOrder: number;
  responseCount: number;
  isActive: boolean;
  weight?: number;
}

interface GroupChatHeaderProps {
  title: string;
  participants: GroupParticipant[];
  currentSpeaker?: Character | null;
  processingLabel?: string;
  groupState: string;
  turnMode: string;
  conversationId?: string;
  onPause: () => void;
  onResume: () => void;
  onRequestInterjection: () => void;
  onTriggerNextCharacter: () => void;
  onStop: () => void;
  isGenerating?: boolean;
  onOpenSettings: () => void;
  /** Optional controls inlined into the action cluster at md+ (e.g. roleplay
      Map/Narrator/RP Tools) so they don't waste a full-width header band. */
  trailingActions?: ReactNode;
}

export const GroupChatHeader = ({
  title,
  participants,
  currentSpeaker,
  processingLabel,
  groupState,
  turnMode,
  conversationId,
  onPause,
  onResume,
  onRequestInterjection,
  onTriggerNextCharacter,
  onStop,
  isGenerating = false,
  onOpenSettings,
  trailingActions,
}: GroupChatHeaderProps) => {
  const [showParticipants, setShowParticipants] = useState(false);
  const openCharacterCard = useCharacterCardStore((state) => state.openCharacterCard);

  const isPaused = groupState === 'PAUSED';
  const isCharacterResponding = groupState === 'CHARACTER_RESPONDING' && !processingLabel;
  const hasInterjectionPending = groupState === 'INTERJECTION_PENDING';

  const getStateLabel = () => {
    if (processingLabel) return processingLabel;
    switch (groupState) {
      case 'WAITING_FOR_USER':
        return 'Waiting for you';
      case 'CHARACTER_RESPONDING':
        return currentSpeaker ? `${currentSpeaker.name} is typing...` : 'Resolving the story…';
      case 'PAUSED':
        return 'Paused';
      case 'INTERJECTION_PENDING':
        return 'Interjection requested';
      default:
        return groupState;
    }
  };

  const getTurnModeLabel = () => {
    switch (turnMode?.toUpperCase()) {
      case 'RANDOM':
        return 'Random';
      case 'ROUND_ROBIN':
        return 'Round Robin';
      case 'WEIGHTED':
        return 'Weighted';
      default:
        return turnMode;
    }
  };

  return (
    <div className={cn(
      'border-b border-border/50 bg-background-secondary/50',
      'transition-all duration-200'
    )}>
      {/* Main header */}
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
        {/* flex-1 (basis 0%): keeps the action cluster on the same line at
            narrow widths — the title truncates instead of wrapping. */}
        <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
          {/* Group avatar stack */}
          <div className="relative flex -space-x-2">
            {participants.slice(0, 3).map((p, i) => (
              <button
                key={p.id}
                type="button"
                onClick={() => openCharacterCard(p.character.id, conversationId)}
                className={cn(
                  'relative h-6 w-6 sm:h-8 sm:w-8 cursor-pointer rounded-full border-2 border-background',
                  'overflow-hidden shadow-md',
                  'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary'
                )}
                style={{ zIndex: 3 - i }}
                aria-label={`Open ${p.character.name || 'this character'}'s character card`}
                title={`Open ${p.character.name || 'this character'}'s character card`}
              >
                {p.character?.avatar ? (
                  <img
                    src={p.character.avatar}
                    alt={p.character.name || 'Character'}
                    className="h-full w-full object-cover"
                  />
                ) : (
                  <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-secondary to-secondary-hover">
                    <Bot size={14} className="text-secondary-foreground" />
                  </div>
                )}
              </button>
            ))}
            {participants.length > 3 && (
              <div className={cn(
                'relative flex h-6 w-6 sm:h-8 sm:w-8 items-center justify-center rounded-full',
                'border-2 border-background bg-muted text-xs font-medium'
              )}>
                +{participants.length - 3}
              </div>
            )}
          </div>

          <div className="min-w-0">
            <h2 className="text-base font-semibold text-foreground truncate">
              {title || 'Group Chat'}
            </h2>
            {/* Truncate on mobile so the action cluster stays on the title
                row instead of wrapping into a second line. */}
            <div className="flex min-w-0 items-center gap-2 truncate">
              <span title={getStateLabel()} className={cn(
                'min-w-0 truncate text-xs',
                isCharacterResponding ? 'text-primary' :
                isPaused ? 'text-warning' :
                hasInterjectionPending ? 'text-secondary' :
                'text-muted-foreground'
              )}>
                {getStateLabel()}
              </span>
              <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">
                · {getTurnModeLabel()}
              </span>
            </div>
          </div>
        </div>

        {/* Actions */}
        <div className="ml-auto flex items-center gap-1">
          {/* Stop button - show when generating */}
          {isGenerating && (
            <button
              onClick={onStop}
              className={cn(
                'rounded-lg p-1 sm:p-2 text-destructive',
                'hover:bg-destructive/10 hover:text-destructive',
                'transition-colors duration-150'
              )}
              aria-label="Stop generation"
            >
              <StopCircle size={20} />
            </button>
          )}

          {/* Next Character Response button - only show when waiting for user */}
          {groupState === 'WAITING_FOR_USER' && !isGenerating && (
            <button
              onClick={onTriggerNextCharacter}
              className={cn(
                'rounded-lg p-1 sm:p-2 text-muted-foreground',
                'hover:bg-accent hover:text-foreground',
                'transition-colors duration-150'
              )}
              aria-label="Get next character response"
            >
              <MessageSquarePlus size={20} />
            </button>
          )}

          {/* Interjection button */}
          <button
            onClick={onRequestInterjection}
            disabled={hasInterjectionPending}
            className={cn(
              'rounded-lg p-1 sm:p-2 text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'disabled:opacity-50 disabled:cursor-not-allowed',
              'transition-colors duration-150'
            )}
            aria-label="Request to speak"
          >
            <Hand size={20} />
          </button>

          {/* Pause/Resume button */}
          <button
            onClick={isPaused ? onResume : onPause}
            className={cn(
              'rounded-lg p-1 sm:p-2',
              isPaused ? 'text-warning' : 'text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
            aria-label={isPaused ? 'Resume conversation' : 'Pause conversation'}
          >
            {isPaused ? <Play size={20} /> : <Pause size={20} />}
          </button>

          {/* Participants toggle */}
          <button
            onClick={() => setShowParticipants(!showParticipants)}
            className={cn(
              'rounded-lg p-1 sm:p-2 text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              showParticipants && 'bg-accent text-foreground',
              'transition-colors duration-150'
            )}
            aria-label={showParticipants ? 'Hide participants' : 'Show participants'}
            aria-expanded={showParticipants}
          >
            <Users size={20} />
          </button>

          {/* Settings */}
          <button
            onClick={onOpenSettings}
            className={cn(
              'rounded-lg p-1 sm:p-2 text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
            aria-label="Group settings"
          >
            <Settings size={20} />
          </button>

          {/* Inlined secondary controls — desktop only; mobile keeps its own row */}
          {trailingActions && (
            <div className="ml-1.5 hidden items-center gap-1 border-l border-border/50 pl-2 md:flex">
              {trailingActions}
            </div>
          )}
        </div>
      </div>

      {/* Current speaker indicator */}
      {currentSpeaker && isCharacterResponding && (
        <div className={cn(
          'flex items-center gap-2 px-4 py-2',
          'border-t border-border/30 bg-primary/5'
        )}>
          {currentSpeaker.avatar ? (
            <button
              type="button"
              onClick={() => openCharacterCard(currentSpeaker.id, conversationId)}
              className="cursor-pointer rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              aria-label={`Open ${currentSpeaker.name}'s character card`}
              title={`Open ${currentSpeaker.name}'s character card`}
            >
              <img
                src={currentSpeaker.avatar}
                alt={currentSpeaker.name}
                className="h-6 w-6 rounded-full object-cover"
              />
            </button>
          ) : (
            <button
              type="button"
              onClick={() => openCharacterCard(currentSpeaker.id, conversationId)}
              className="flex h-6 w-6 cursor-pointer items-center justify-center rounded-full bg-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              aria-label={`Open ${currentSpeaker.name}'s character card`}
              title={`Open ${currentSpeaker.name}'s character card`}
            >
              <Bot size={12} className="text-primary-foreground" />
            </button>
          )}
          <span className="text-sm font-medium text-primary">
            {currentSpeaker.name} is responding...
          </span>
          <div className="flex gap-1">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className={cn(
                  'h-1.5 w-1.5 rounded-full bg-primary',
                  'animate-typing-dot'
                )}
                style={{ animationDelay: `${i * 0.1}s` }}
              />
            ))}
          </div>
        </div>
      )}

      {/* Participants panel */}
      {showParticipants && (
        <div className={cn(
          'border-t border-border/50 bg-background p-4',
          'animate-fade-down'
        )}>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-medium text-foreground">
              Participants ({participants.length})
            </h3>
            <button
              onClick={() => setShowParticipants(false)}
              className="text-muted-foreground hover:text-foreground"
              aria-label="Close participants panel"
            >
              <X size={16} />
            </button>
          </div>

          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {participants.map((p) => (
              <div
                key={p.id}
                role="button"
                tabIndex={0}
                onClick={() => openCharacterCard(p.character.id, conversationId)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    openCharacterCard(p.character.id, conversationId);
                  }
                }}
                aria-label={`Open ${p.character?.name || 'this character'}'s character card`}
                title={`Open ${p.character?.name || 'this character'}'s character card`}
                className={cn(
                  'flex cursor-pointer items-center gap-3 rounded-lg p-1 sm:p-2',
                  'bg-muted/30 border border-border/50',
                  'hover:bg-accent/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                  !p.isActive && 'opacity-50'
                )}
              >
                {p.character?.avatar ? (
                  <img
                    src={p.character.avatar}
                    alt={p.character.name || 'Character'}
                    className="h-8 w-8 rounded-lg object-cover"
                  />
                ) : (
                  <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-secondary to-secondary-hover">
                    <Bot size={16} className="text-secondary-foreground" />
                  </div>
                )}
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-foreground truncate">
                    {p.character?.name || 'Unknown Character'}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {p.responseCount} {p.responseCount === 1 ? 'response' : 'responses'}
                    {p.weight && p.weight !== 1.0 && (
                      <span className="ml-1 text-secondary">· {p.weight.toFixed(1)}x</span>
                    )}
                  </p>
                </div>
                {currentSpeaker?.id === p.character?.id && (
                  <div className="flex h-2 w-2 rounded-full bg-primary animate-pulse" />
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
