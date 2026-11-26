import { act, render } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { MessageList } from './MessageList';

const state = vi.hoisted(() => ({
  stream: { id: 'reply', streamingContent: '*I nod.* “Hello.”', isComplete: false,
    role: 'ASSISTANT', createdAt: '2026-11-19T12:00:00Z', characterId: 'ada', messageType: 'DIALOGUE' },
  add: vi.fn(), finalize: vi.fn(), refetch: vi.fn(), stop: vi.fn(), stopReading: vi.fn(), startReading: vi.fn(), stopAll: vi.fn(),
  readingConversationId: null as string | null,
  onFrame: undefined as undefined | ((frame: unknown) => void),
}));
vi.mock('@/hooks/useGraphQL', async () => {
  const { useEffect } = await vi.importActual<typeof import('react')>('react');
  return {
  useMessages: () => ({ messages: [], loading: false, refetch: state.refetch }),
  // Like the real hook, every frame reaches onFrame; rerendering with a new
  // stream object delivers it as the next frame.
  useMessageStream: (_conversationId: string, onFrame?: (frame: unknown) => void) => {
    state.onFrame = onFrame;
    const frame = state.stream;
    useEffect(() => { onFrame?.(frame); }, [frame, onFrame]);
    return { message: frame };
  },
  useStopGeneration: () => ({ stopGeneration: vi.fn() }),
  usePendingStreamingMessage: () => ({ pendingMessage: null, refetch: state.refetch }),
  };
});
vi.mock('@/stores/messageStore', () => ({ useMessageStore: () => ({}) }));
vi.mock('@/stores/ttsStore', () => ({ useTTSStore: Object.assign(() => ({
  settings: { enabled: true, autoPlay: true }, playbackStates: new Map(),
  readProgress: {}, syncConversationMessages: vi.fn(), stopAll: state.stopAll,
  addStreamingChunk: state.add, finalizeStreaming: state.finalize, stop: state.stop,
  readingConversationId: state.readingConversationId, stopReading: state.stopReading, startReading: state.startReading,
}), { getState: () => ({ readingConversationId: state.readingConversationId }) }) }));
vi.mock('./ReactionPicker', () => ({ ReactionPicker: () => null }));
vi.mock('./TTSControls', () => ({ TTSControls: () => null }));
vi.mock('./TTSPlayingIndicator', () => ({ TTSPlayingIndicator: () => null }));
const character = { id: 'ada', name: 'Ada', personality: 'Wry and quiet', gender: 'female', pronouns: 'she/her', ageYears: 27 };

beforeEach(() => {
  state.readingConversationId = null;
  state.stream = { ...state.stream, isComplete: false, messageType: 'DIALOGUE', role: 'ASSISTANT' };
  state.refetch.mockResolvedValue({ data: {} });
  HTMLElement.prototype.scrollIntoView = vi.fn();
  sessionStorage.clear();
});

it('carries group roster Personality from live tokens through final playback', () => {
  const props = { conversationId: 'voice-group', isGroupChat: true,
    participants: [{ id: 'membership', character, turnOrder: 0, isActive: true }] };
  const view = render(<MessageList {...props} isGenerating />);
  expect(state.add).toHaveBeenCalledWith('reply', expect.any(String));
  state.stream = { ...state.stream, isComplete: true };
  view.rerender(<MessageList {...props} isGenerating={false} />);
  expect(state.finalize).toHaveBeenCalledWith('reply', expect.any(String), undefined, character);
});

it('carries the selected single-chat Personality into streaming playback', () => {
  const view = render(<MessageList conversationId="voice-single" voiceCharacter={character} isGenerating />);
  state.stream = { ...state.stream, isComplete: true };
  view.rerender(<MessageList conversationId="voice-single" voiceCharacter={character} isGenerating={false} />);
  expect(state.finalize).toHaveBeenCalledWith('reply', expect.any(String), undefined, character);
});

it('keeps narration in the narrator voice even if associated with a character', () => {
  state.stream = { ...state.stream, messageType: 'NARRATION' };
  const view = render(<MessageList conversationId="voice-narration" voiceCharacter={character} isGenerating />);
  state.stream = { ...state.stream, isComplete: true };
  view.rerender(<MessageList conversationId="voice-narration" voiceCharacter={character} isGenerating={false} />);
  expect(state.finalize).toHaveBeenCalledWith('reply', expect.any(String), undefined, undefined);
});


it('automatically reads system narration in the narrator voice', () => {
  state.stream = { ...state.stream, role: 'SYSTEM', messageType: 'NARRATION' };
  const view = render(<MessageList conversationId="system-narration" voiceCharacter={character} isGenerating />);
  expect(state.add).toHaveBeenCalledWith('reply', expect.any(String));
  state.stream = { ...state.stream, isComplete: true };
  view.rerender(<MessageList conversationId="system-narration" voiceCharacter={character} isGenerating={false} />);
  expect(state.finalize).toHaveBeenCalledWith('reply', expect.any(String), undefined, undefined);
});


it('does not duplicate or cancel a message already owned by conversation read-through', () => {
  const view = render(<MessageList conversationId="read-through" voiceCharacter={character} isGenerating />);
  expect(state.add).toHaveBeenCalled();
  state.readingConversationId = 'read-through';
  state.stream = { ...state.stream, isComplete: true };
  view.rerender(<MessageList conversationId="read-through" voiceCharacter={character} isGenerating={false} />);
  expect(state.finalize).not.toHaveBeenCalled();
  expect(state.stop).not.toHaveBeenCalled();
});

it('stops all audio, including manual playback, when leaving a conversation', () => {
  const view = render(<MessageList conversationId="voice-group" isGenerating />);
  view.unmount();
  expect(state.stopAll).toHaveBeenCalled();
});


it('plays completed-only opening narration before character replies, exactly once', () => {
  state.stream = { ...state.stream, id: 'opening', role: 'SYSTEM', messageType: 'NARRATION',
    streamingContent: 'The inn is quiet.', isComplete: true };
  const view = render(<MessageList conversationId="opening-story" voiceCharacter={character} />);
  expect(state.finalize.mock.calls).toEqual([['opening', 'The inn is quiet.', undefined, undefined]]);
  state.stream = { ...state.stream };
  view.rerender(<MessageList conversationId="opening-story" voiceCharacter={character} />);
  expect(state.finalize).toHaveBeenCalledTimes(1);
  state.stream = { ...state.stream, id: 'character', role: 'ASSISTANT', messageType: 'DIALOGUE',
    streamingContent: '“Welcome.”', isComplete: false };
  view.rerender(<MessageList conversationId="opening-story" voiceCharacter={character} />);
  state.stream = { ...state.stream, isComplete: true };
  view.rerender(<MessageList conversationId="opening-story" voiceCharacter={character} />);
  expect(state.finalize.mock.calls.map(call => call[0])).toEqual(['opening', 'character']);
});

it('does not automatically speak OOC notices', () => {
  state.stream = { ...state.stream, messageType: 'OOC', isComplete: true };
  render(<MessageList conversationId="ooc" voiceCharacter={character} />);
  expect(state.add).not.toHaveBeenCalled();
  expect(state.finalize).not.toHaveBeenCalled();
});


it('reads a complete narration frame that is followed by the next speaker before any render', () => {
  render(<MessageList conversationId="voice-opening" voiceCharacter={character} isGenerating />);
  state.add.mockClear();
  state.finalize.mockClear();
  act(() => {
    state.onFrame?.({ id: 'opening', streamingContent: 'Rain hisses on the cobbles.', isComplete: true,
      role: 'SYSTEM', messageType: 'NARRATION', createdAt: '2026-11-19T12:00:00Z' });
    state.onFrame?.({ id: 'first-turn', streamingContent: '“Hold', isComplete: false,
      role: 'ASSISTANT', messageType: 'DIALOGUE', characterId: 'ada', createdAt: '2026-11-19T12:00:01Z' });
  });
  expect(state.finalize).toHaveBeenCalledWith('opening', 'Rain hisses on the cobbles.', undefined, undefined);
  expect(state.add).toHaveBeenCalledWith('first-turn', '“Hold');
});
