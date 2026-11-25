import { useState } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import {
  Bot,
  Users,
  Settings,
  Play,
  Pause,
  Hand,
  X,
  MessageSquarePlus,
  StopCircle,
  MoreHorizontal,
  Wand2,
  Globe,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { SwitchTrack } from '@/components/ui/SwitchTrack';
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
  /** Roleplay controls: labelled in the action cluster from md up, folded
      into the overflow menu (tools stay visible) on phones. */
  roleplay?: RoleplayHeaderControls;
}

export interface RoleplayHeaderControls {
  narratorMode: boolean;
  onNarratorModeChange: (enabled: boolean) => void;
  toolsOpen: boolean;
  onToggleTools: () => void;
}

const iconButton =
  'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg transition-colors duration-150 md:h-9 md:w-9';
const quietIcon = 'text-muted-foreground hover:bg-accent hover:text-foreground';
const labelledButton =
  'flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors duration-150';
const menuItem =
  'flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm outline-none data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50 data-[highlighted]:bg-accent';

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
  roleplay,
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
      <div className="flex items-center justify-between gap-2 px-3 py-2 md:px-4 md:py-3">
        {/* flex-1 (basis 0%): keeps the action cluster on the same line at
            narrow widths — the title truncates instead of wrapping. */}
        <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
          {/* The avatar stack summarises the cast; tapping it expands the
              full member list, where any participant can be opened. */}
          <button
            type="button"
            onClick={() => setShowParticipants(!showParticipants)}
            aria-expanded={showParticipants}
            aria-controls="group-participants-panel"
            aria-label={`${showParticipants ? 'Hide' : 'Show'} all ${participants.length} members`}
            title="Members"
            className={cn(
              '-ml-1 flex min-h-10 shrink-0 items-center rounded-full p-1 transition-colors hover:bg-accent',
              'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
              showParticipants && 'bg-accent'
            )}
          >
            <span aria-hidden="true" className="flex -space-x-2">
              {participants.slice(0, 3).map((p, i) => (
                <span
                  key={p.id}
                  className="relative h-7 w-7 overflow-hidden rounded-full border-2 border-background shadow-md sm:h-8 sm:w-8"
                  style={{ zIndex: 3 - i }}
                >
                  {p.character?.avatar ? (
                    <img src={p.character.avatar} alt="" className="h-full w-full object-cover" />
                  ) : (
                    <span className="flex h-full w-full items-center justify-center bg-gradient-to-br from-secondary to-secondary-hover">
                      <Bot size={14} className="text-secondary-foreground" />
                    </span>
                  )}
                </span>
              ))}
              {participants.length > 3 && (
                <span className="relative flex h-7 min-w-7 items-center justify-center rounded-full border-2 border-background bg-muted px-1 text-[11px] font-medium tabular-nums text-foreground sm:h-8 sm:min-w-8">
                  +{participants.length - 3}
                </span>
              )}
            </span>
          </button>

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

        {/* Turn controls stay visible at every width; the rest folds into
            the overflow menu below md. */}
        <div className="flex shrink-0 items-center gap-0.5 md:gap-1">
          {isGenerating && (
            <button
              type="button"
              onClick={onStop}
              className={cn(iconButton, 'text-destructive hover:bg-destructive/10')}
              aria-label="Stop generation"
              title="Stop generation"
            >
              <StopCircle size={20} />
            </button>
          )}

          {groupState === 'WAITING_FOR_USER' && !isGenerating && (
            <button
              type="button"
              onClick={onTriggerNextCharacter}
              className={cn(iconButton, quietIcon, 'hidden md:inline-flex')}
              aria-label="Get next character response"
              title="Next character response"
            >
              <MessageSquarePlus size={20} />
            </button>
          )}

          <button
            type="button"
            onClick={onRequestInterjection}
            disabled={hasInterjectionPending}
            className={cn(iconButton, quietIcon, 'hidden disabled:cursor-not-allowed disabled:opacity-50 md:inline-flex')}
            aria-label="Request to speak"
            title="Request to speak"
          >
            <Hand size={20} />
          </button>

          <button
            type="button"
            onClick={isPaused ? onResume : onPause}
            className={cn(
              iconButton,
              'hidden md:inline-flex',
              isPaused ? 'text-warning' : 'text-muted-foreground',
              'hover:bg-accent hover:text-foreground'
            )}
            aria-label={isPaused ? 'Resume conversation' : 'Pause conversation'}
            title={isPaused ? 'Resume' : 'Pause'}
          >
            {isPaused ? <Play size={20} /> : <Pause size={20} />}
          </button>

          <button
            type="button"
            onClick={() => setShowParticipants(!showParticipants)}
            className={cn(iconButton, quietIcon, 'hidden md:inline-flex', showParticipants && 'bg-accent text-foreground')}
            aria-label={showParticipants ? 'Hide participants' : 'Show participants'}
            aria-expanded={showParticipants}
            title="Participants"
          >
            <Users size={20} />
          </button>

          <button
            type="button"
            onClick={onOpenSettings}
            className={cn(iconButton, quietIcon, 'hidden md:inline-flex')}
            aria-label="Group settings"
            title="Group settings"
          >
            <Settings size={20} />
          </button>

          {roleplay && (
            <>
              <div className="ml-1.5 hidden items-center gap-1 border-l border-border/50 pl-2 md:flex">
                <button
                  type="button"
                  role="switch"
                  onClick={() => roleplay.onNarratorModeChange(!roleplay.narratorMode)}
                  aria-checked={roleplay.narratorMode}
                  className={cn(
                    labelledButton,
                    roleplay.narratorMode ? 'text-foreground hover:bg-accent' : quietIcon
                  )}
                >
                  <Wand2 size={14} />
                  Narrator
                  <SwitchTrack checked={roleplay.narratorMode} className="ml-0.5" />
                </button>
                <button
                  type="button"
                  onClick={roleplay.onToggleTools}
                  aria-pressed={roleplay.toolsOpen}
                  className={cn(
                    labelledButton,
                    roleplay.toolsOpen ? 'bg-primary/10 text-primary' : quietIcon
                  )}
                >
                  <Globe size={14} />
                  RP Tools
                </button>
              </div>
            </>
          )}

          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
              <button
                type="button"
                className={cn(iconButton, quietIcon, 'md:hidden')}
                aria-label="More conversation controls"
                title="More"
              >
                <MoreHorizontal size={20} />
              </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content
                align="end"
                sideOffset={8}
                className="z-50 w-60 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-popover p-1.5 text-popover-foreground shadow-lg"
              >
                {groupState === 'WAITING_FOR_USER' && !isGenerating && (
                  <DropdownMenu.Item className={menuItem} onSelect={onTriggerNextCharacter}>
                    <MessageSquarePlus size={16} />
                    Next character response
                  </DropdownMenu.Item>
                )}
                <DropdownMenu.Item className={cn(menuItem, isPaused && 'text-warning')} onSelect={isPaused ? onResume : onPause}>
                  {isPaused ? <Play size={16} /> : <Pause size={16} />}
                  {isPaused ? 'Resume conversation' : 'Pause conversation'}
                </DropdownMenu.Item>
                <DropdownMenu.Item
                  className={menuItem}
                  disabled={hasInterjectionPending}
                  onSelect={onRequestInterjection}
                >
                  <Hand size={16} />
                  {hasInterjectionPending ? 'Interjection requested' : 'Request to speak'}
                </DropdownMenu.Item>
                <DropdownMenu.Item className={menuItem} onSelect={() => setShowParticipants(!showParticipants)}>
                  <Users size={16} />
                  {showParticipants ? 'Hide participants' : 'Participants'}
                  <span className="ml-auto text-xs text-muted-foreground">{participants.length}</span>
                </DropdownMenu.Item>
                <DropdownMenu.Item className={menuItem} onSelect={onOpenSettings}>
                  <Settings size={16} />
                  Group settings
                </DropdownMenu.Item>
                {roleplay && (
                  <>
                    <DropdownMenu.Separator className="my-1 h-px bg-border" />
                    {/* Phones only: the open tools take over the header, and their
                        back button closes them, so this item just opens them. */}
                    <DropdownMenu.Item
                      className={menuItem}
                      onSelect={() => { if (!roleplay.toolsOpen) roleplay.onToggleTools(); }}
                    >
                      <Globe size={16} />
                      Roleplay tools
                    </DropdownMenu.Item>
                    <DropdownMenu.CheckboxItem
                      className={menuItem}
                      checked={roleplay.narratorMode}
                      onCheckedChange={roleplay.onNarratorModeChange}
                    >
                      <Wand2 size={16} />
                      Narrator mode
                      <SwitchTrack checked={roleplay.narratorMode} className="ml-auto" />
                    </DropdownMenu.CheckboxItem>
                  </>
                )}
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>
        </div>
      </div>

      {/* The subtitle already names the speaker on phones; this band adds the
          avatar and typing dots where there is room for it. */}
      {currentSpeaker && isCharacterResponding && (
        <div className={cn(
          'hidden items-center gap-2 px-4 py-2 md:flex',
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
        <div id="group-participants-panel" className={cn(
          'custom-scrollbar max-h-[45dvh] overflow-y-auto border-t border-border/50 bg-background px-3 pb-3 pt-2 md:max-h-none md:p-4',
          'animate-fade-down'
        )}>
          <div className="mb-2 flex items-center justify-between md:mb-3">
            <h3 className="text-sm font-medium text-foreground">
              Participants ({participants.length})
            </h3>
            <button
              type="button"
              onClick={() => setShowParticipants(false)}
              className={cn(iconButton, quietIcon, 'md:h-8 md:w-8')}
              aria-label="Close participants panel"
              title="Close"
            >
              <X size={16} />
            </button>
          </div>

          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {participants.map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() => openCharacterCard(p.character.id, conversationId)}
                aria-label={`Open ${p.character?.name || 'this character'}'s character card`}
                title={`Open ${p.character?.name || 'this character'}'s character card`}
                className={cn(
                  'flex min-h-11 w-full items-center gap-3 rounded-lg p-2 text-left',
                  'bg-muted/30 border border-border/50',
                  'hover:bg-accent/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                  !p.isActive && 'opacity-50'
                )}
              >
                {p.character?.avatar ? (
                  <img
                    src={p.character.avatar}
                    alt=""
                    className="h-8 w-8 shrink-0 rounded-lg object-cover"
                  />
                ) : (
                  <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-secondary to-secondary-hover">
                    <Bot size={16} className="text-secondary-foreground" />
                  </span>
                )}
                <span className="block min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-foreground">
                    {p.character?.name || 'Unknown Character'}
                  </span>
                  <span className="block text-xs text-muted-foreground">
                    {p.responseCount} {p.responseCount === 1 ? 'response' : 'responses'}
                    {p.weight && p.weight !== 1.0 && (
                      <span className="ml-1 text-secondary">· {p.weight.toFixed(1)}x</span>
                    )}
                  </span>
                </span>
                {currentSpeaker?.id === p.character?.id && (
                  <span aria-hidden="true" className="flex h-2 w-2 shrink-0 rounded-full bg-primary animate-pulse" />
                )}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
