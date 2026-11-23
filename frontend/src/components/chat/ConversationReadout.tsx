import { useEffect } from 'react';
import { Volume2, Square, Pause, Play, RotateCcw } from 'lucide-react';
import { useTTSStore } from '@/stores/ttsStore';
import { isReadableMessage, speechSignature, type ConversationSpeechMessage } from '@/services/conversationSpeech';

export function ConversationReadout({ conversationId, messages }: {
  conversationId: string;
  messages: ConversationSpeechMessage[];
}) {
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
  const buttonClass = 'inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-md px-2 text-sm font-medium transition-colors hover:bg-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-50';
  return (
    <div className="sticky top-0 z-10 mb-4 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-background px-3 py-1">
      <div className="flex flex-wrap items-center gap-1">
        <button
          type="button"
          disabled={active && !currentlyPlayingMessageId}
          onClick={() => active ? void toggleReadingPause() : void startReading(conversationId, messages, finished)}
          title={active ? 'Pause or resume without losing your place' : 'Read unread loaded replies in order, skipping player messages'}
          className={buttonClass}
        >
          {active ? readingPaused ? <Play size={16} aria-hidden="true" /> : <Pause size={16} aria-hidden="true" /> : finished ? <RotateCcw size={16} aria-hidden="true" /> : <Volume2 size={16} aria-hidden="true" />}
          {active ? !currentlyPlayingMessageId ? 'Preparing voices…' : readingPaused ? 'Resume reading' : 'Pause reading' : finished ? 'Read again' : hasProgress ? 'Continue reading' : 'Read conversation'}
        </button>
        {active && <button type="button" onClick={stopReading} className={buttonClass}><Square size={16} aria-hidden="true" />Stop reading</button>}
        {!active && hasProgress && !finished && <button type="button" onClick={() => void startReading(conversationId, messages, true)} className={buttonClass}><RotateCcw size={16} aria-hidden="true" />Read from beginning</button>}
      </div>
      <span className="text-xs text-muted-foreground" role="status">
        {initError ? `Playback interrupted: ${initError}` : active ? readingPaused ? 'Paused · your place is kept' : 'Reading · skips player messages' : finished ? 'All loaded replies read' : `${unread.length} unread ${unread.length === 1 ? 'reply' : 'replies'} · skips player messages`}
      </span>
    </div>
  );
}
