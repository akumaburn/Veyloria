import { useContext, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Volume2, Square, Pause, Play, RotateCcw } from 'lucide-react';
import { useTTSStore } from '@/stores/ttsStore';
import { isReadableMessage, speechSignature, type ConversationSpeechMessage } from '@/services/conversationSpeech';
import { ChatToolbarContext } from '@/components/layout/ChatToolbarContext';
import { cn } from '@/lib/utils';

const buttonClass = 'workspace-icon-button relative focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-50';

export function ConversationReadout({ conversationId, messages }: {
  conversationId: string;
  messages: ConversationSpeechMessage[];
}) {
  const toolbarHost = useContext(ChatToolbarContext);
  const { settings, readingConversationId, readingPaused, currentlyPlayingMessageId,
    readProgress, syncConversationMessages, startReading, stopReading, toggleReadingPause, initError } = useTTSStore();
  const active = readingConversationId === conversationId;
  const readable = messages.filter(isReadableMessage);
  const unread = readable.filter(message => readProgress[conversationId]?.[message.id] !== speechSignature(message));
  const hasProgress = unread.length < readable.length;
  const finished = readable.length > 0 && unread.length === 0;

  useEffect(() => {
    syncConversationMessages(conversationId, messages);
  }, [conversationId, messages, syncConversationMessages]);
  useEffect(() => () => {
    if (useTTSStore.getState().readingConversationId === conversationId) stopReading();
  }, [conversationId, stopReading]);

  if (!settings.enabled || (!active && !readable.length)) return null;

  const status = initError
    ? `Playback interrupted: ${initError}`
    : active
      ? readingPaused ? 'Paused · your place is kept' : 'Reading · skips player messages'
      : finished
        ? 'All loaded replies read'
        : `${unread.length} unread ${unread.length === 1 ? 'reply' : 'replies'}`;
  const primaryLabel = active
    ? !currentlyPlayingMessageId ? 'Preparing voices…' : readingPaused ? 'Resume reading' : 'Pause reading'
    : finished ? 'Read again' : hasProgress ? 'Continue reading' : 'Read conversation';
  const PrimaryIcon = active ? readingPaused ? Play : Pause : finished ? RotateCcw : Volume2;
  const unreadBadge = !active && !finished && unread.length > 0
    ? unread.length > 99 ? '99+' : String(unread.length)
    : null;

  const controls = (
    <div role="group" aria-label="Read conversation aloud" className="order-first flex shrink-0 items-center gap-0.5">
      <button
        type="button"
        disabled={active && !currentlyPlayingMessageId}
        onClick={() => active ? void toggleReadingPause() : void startReading(conversationId, messages, finished)}
        aria-label={primaryLabel}
        title={`${primaryLabel} — ${status}`}
        className={cn(buttonClass, initError ? 'text-destructive' : active && 'bg-primary/10 text-primary')}
      >
        <PrimaryIcon size={18} aria-hidden="true" />
        {unreadBadge && (
          <span aria-hidden="true" className="absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-semibold leading-none text-primary-foreground">
            {unreadBadge}
          </span>
        )}
      </button>
      {active && (
        <button type="button" onClick={stopReading} aria-label="Stop reading" title="Stop reading" className={buttonClass}>
          <Square size={16} aria-hidden="true" />
        </button>
      )}
      {!active && hasProgress && !finished && (
        <button type="button" onClick={() => void startReading(conversationId, messages, true)} aria-label="Read from beginning" title="Read from beginning" className={buttonClass}>
          <RotateCcw size={16} aria-hidden="true" />
        </button>
      )}
      <span role="status" className={cn('sr-only', toolbarHost ? '2xl:not-sr-only 2xl:max-w-40 2xl:truncate 2xl:px-1 2xl:text-xs 2xl:text-muted-foreground' : 'sm:not-sr-only sm:px-1 sm:text-xs sm:text-muted-foreground')}>
        {status}
      </span>
    </div>
  );

  return toolbarHost ? createPortal(controls, toolbarHost) : controls;
}
