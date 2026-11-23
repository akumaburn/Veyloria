import { beforeEach, expect, it, vi } from 'vitest';
import { enableMapSet } from 'immer';
import { useTTSStore } from './ttsStore';
import { ttsService, type MessagePlaybackState } from '@/services/ttsService';
import type { ConversationSpeechMessage } from '@/services/conversationSpeech';

enableMapSet();
const callbacks = vi.hoisted(() => ({ notify: null as null | ((id: string, state: MessagePlaybackState) => void) }));
vi.mock('@/services/ttsService', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/ttsService')>(),
  ttsService: {
    unlockAudio: vi.fn(), updateConfig: vi.fn(), fetchVoices: vi.fn(), subscribeAll: vi.fn(), finalizeStreaming: vi.fn(),
    pause: vi.fn(), resume: vi.fn(), restart: vi.fn(), stopAll: vi.fn(), play: vi.fn(), stop: vi.fn(), dispose: vi.fn(), selectVoiceForCharacter: vi.fn(),
  },
}));
const character = { id: 'elara', name: 'Elara', personality: 'Impulsive.', gender: 'Woman' };
const messages: ConversationSpeechMessage[] = [
  { id: 'narration', content: 'The corridor is quiet.', role: 'system', messageType: 'NARRATION', character },
  { id: 'player', content: 'I follow her.', role: 'user' },
  { id: 'action', content: '*I shift my weight.*', role: 'assistant', messageType: 'ACTION', character },
  { id: 'reply', content: '“Stay close.”', role: 'assistant', character },
  { id: 'stream', content: 'Not finished', role: 'assistant', status: 'generating' },
  { id: 'hidden', content: '<think>Only thinking</think>', role: 'assistant' },
];

beforeEach(() => {
  useTTSStore.getState().reset();
  useTTSStore.setState(state => ({ settings: { ...state.settings, enabled: true, autoPlay: false }, initError: null, readProgress: {}, speechMessages: {}, playbackSignatures: {} }));
  vi.mocked(ttsService.fetchVoices).mockResolvedValue([]);
  vi.mocked(ttsService.subscribeAll).mockImplementation(callback => { callbacks.notify = callback; return vi.fn(); });
  vi.mocked(ttsService.finalizeStreaming).mockResolvedValue();
});

it('queues narration and character messages in order, skips players and uses the right voices', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  expect(vi.mocked(ttsService.finalizeStreaming).mock.calls).toEqual([
    ['narration', messages[0].content, undefined, undefined],
    ['action', messages[2].content, undefined, character],
    ['reply', messages[3].content, undefined, character],
  ]);
  expect(useTTSStore.getState().readingConversationId).toBe('chat');
  callbacks.notify?.('reply', { messageId: 'reply', isPlaying: false, isPaused: false, currentChunkIndex: 1, totalChunks: 1, progress: 100, completed: true, voice: 'narrator' });
  expect(useTTSStore.getState().readingConversationId).toBeNull();
});

it('keeps earlier unread narration when character playback has already begun', async () => {
  useTTSStore.setState({ currentlyPlayingMessageId: 'action' });
  await useTTSStore.getState().startReading('chat', messages);
  expect(vi.mocked(ttsService.finalizeStreaming).mock.calls.map(call => call[0])).toEqual(['narration', 'action', 'reply']);
});

it('can stop while the first message is initializing without queueing the rest', async () => {
  let release!: () => void;
  vi.mocked(ttsService.finalizeStreaming).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  const pending = useTTSStore.getState().startReading('chat', messages);
  for (let i = 0; i < 10; i++) await Promise.resolve();
  useTTSStore.getState().stopReading();
  release();
  await pending;
  expect(ttsService.finalizeStreaming).toHaveBeenCalledTimes(1);
  expect(useTTSStore.getState().readingConversationId).toBeNull();
});

it('manual playback cancels the read-through queue', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  await useTTSStore.getState().play('manual', 'Another line.');
  expect(useTTSStore.getState().readingConversationId).toBeNull();
  expect(ttsService.play).toHaveBeenCalledWith('manual', 'Another line.', undefined, undefined);
});


it('clears read-through state when browser audio cannot start', async () => {
  vi.mocked(ttsService.unlockAudio).mockRejectedValueOnce(new Error('Audio permission denied'));
  await useTTSStore.getState().startReading('chat', messages);
  expect(useTTSStore.getState().readingConversationId).toBeNull();
  expect(useTTSStore.getState().initError).toBe('Audio permission denied');
  expect(ttsService.finalizeStreaming).not.toHaveBeenCalled();
});

function finish(id: string, failed = false) {
  callbacks.notify?.(id, { messageId: id, isPlaying: false, isPaused: false,
    currentChunkIndex: 1, totalChunks: 1, progress: failed ? 0 : 100,
    completed: !failed, error: failed ? 'Disconnected' : undefined, voice: 'narrator' });
}

it('continues after completed messages across stop, reload persistence, and repeated read-through', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  finish('narration'); finish('action');
  useTTSStore.getState().stopReading();
  const saved = vi.mocked(localStorage.setItem).mock.calls.filter(call => call[0] === 'tts-storage').at(-1)![1];
  expect(JSON.parse(saved).state.readProgress.chat).toHaveProperty('action');
  useTTSStore.setState({ readProgress: {} });
  vi.mocked(localStorage.getItem).mockReturnValueOnce(saved);
  await useTTSStore.persist.rehydrate();
  vi.mocked(ttsService.finalizeStreaming).mockClear();
  await useTTSStore.getState().startReading('chat', messages);
  expect(vi.mocked(ttsService.finalizeStreaming).mock.calls.map(call => call[0])).toEqual(['reply']);
  finish('reply');
  vi.mocked(ttsService.finalizeStreaming).mockClear();
  await useTTSStore.getState().startReading('chat', messages);
  expect(ttsService.finalizeStreaming).not.toHaveBeenCalled();
  await useTTSStore.getState().startReading('chat', messages, true);
  expect(ttsService.finalizeStreaming).toHaveBeenCalledTimes(3);
});

it('does not skip failed, edited, or different-conversation replies', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  finish('narration'); finish('action', true); finish('reply');
  vi.mocked(ttsService.finalizeStreaming).mockClear();
  await useTTSStore.getState().startReading('chat', messages.map(m => m.id === 'reply' ? { ...m, content: 'Changed reply.' } : m));
  expect(vi.mocked(ttsService.finalizeStreaming).mock.calls.map(call => call[0])).toEqual(['action', 'reply']);
  vi.mocked(ttsService.finalizeStreaming).mockClear();
  await useTTSStore.getState().startReading('other', messages);
  expect(ttsService.finalizeStreaming).toHaveBeenCalledTimes(3);
});

it('pauses and resumes the active audio without requeueing or regenerating', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  useTTSStore.setState({ currentlyPlayingMessageId: 'narration' });
  await useTTSStore.getState().toggleReadingPause();
  expect(ttsService.pause).toHaveBeenCalledWith('narration');
  expect(useTTSStore.getState().readingPaused).toBe(true);
  await useTTSStore.getState().toggleReadingPause();
  expect(ttsService.resume).toHaveBeenCalledWith('narration');
  expect(useTTSStore.getState().readingPaused).toBe(false);
  expect(ttsService.finalizeStreaming).toHaveBeenCalledTimes(3);
});

it('appends newly saved replies once while a read-through is active', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  const updated = [...messages, { id: 'new', content: 'The door opens.', role: 'assistant' }];
  useTTSStore.getState().syncConversationMessages('chat', updated);
  useTTSStore.getState().syncConversationMessages('chat', updated);
  await Promise.resolve();
  expect(vi.mocked(ttsService.finalizeStreaming).mock.calls.map(call => call[0])).toEqual(['narration', 'action', 'reply', 'new']);
  finish('reply');
  expect(useTTSStore.getState().readingConversationId).toBe('chat');
  finish('new');
  expect(useTTSStore.getState().readingConversationId).toBeNull();
});

it('cancels manual playback waiting for initialization when voices are disabled', async () => {
  let release!: () => void;
  vi.mocked(ttsService.unlockAudio).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  const pending = useTTSStore.getState().play('manual', 'A line.');
  useTTSStore.getState().setEnabled(false);
  release();
  await pending;
  expect(ttsService.play).not.toHaveBeenCalled();
});

it.each([false, true])('does not loop with recreated message arrays (read-through active: %s)', async active => {
  if (active) await useTTSStore.getState().startReading('chat', messages);
  const { createElement } = await import('react');
  const { render } = await import('@testing-library/react');
  const { ConversationReadout } = await import('@/components/chat/ConversationReadout');
  let renders = 0;
  function Conversation() {
    useTTSStore();
    renders++;
    return createElement(ConversationReadout, {
      conversationId: 'chat', messages: messages.map(message => ({ ...message })),
    });
  }
  const view = render(createElement(Conversation));
  expect(renders).toBeLessThan(5);
  view.unmount();
});

it('publishes no updates for equivalent metadata or an empty active queue', async () => {
  await useTTSStore.getState().startReading('chat', messages);
  const updates = vi.fn();
  const unsubscribe = useTTSStore.subscribe(updates);
  useTTSStore.getState().syncConversationMessages('chat', messages.map(message => ({ ...message })));
  await useTTSStore.getState().drainReadingQueue();
  expect(updates).not.toHaveBeenCalled();
  expect(ttsService.finalizeStreaming).toHaveBeenCalledTimes(3);
  unsubscribe();
});
