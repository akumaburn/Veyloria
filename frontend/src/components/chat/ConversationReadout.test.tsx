import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { ConversationReadout } from './ConversationReadout';

const state = vi.hoisted(() => ({
  settings: { enabled: true }, readingConversationId: null as string | null,
  startReading: vi.fn(), stopReading: vi.fn(), toggleReadingPause: vi.fn(), syncConversationMessages: vi.fn(), readProgress: {}, readingPaused: false, currentlyPlayingMessageId: "reply", initError: null,
}));
vi.mock('@/stores/ttsStore', () => ({ useTTSStore: Object.assign(() => state, { getState: () => state }) }));
const messages = [{ id: 'reply', content: 'The corridor is quiet.', role: 'system', messageType: 'NARRATION' }];
beforeEach(() => { state.readingConversationId = null; state.readingPaused = false; state.readProgress = {}; });

it('offers an accessible read-through toggle and stops when leaving the conversation', async () => {
  const user = userEvent.setup();
  const view = render(<ConversationReadout conversationId="chat" messages={messages} />);
  await user.click(screen.getByRole('button', { name: 'Read conversation' }));
  expect(state.startReading).toHaveBeenCalledWith('chat', messages, false);
  state.readingConversationId = 'chat';
  view.rerender(<ConversationReadout conversationId="chat" messages={messages} />);
  await user.click(screen.getByRole('button', { name: 'Pause reading' }));
  expect(state.toggleReadingPause).toHaveBeenCalled();
  view.unmount();
  expect(state.stopReading).toHaveBeenCalled();
});

it('does not offer playback for player-only messages', () => {
  render(<ConversationReadout conversationId="chat" messages={[{ id: 'player', content: 'I follow.', role: 'user' }]} />);
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
});

it('offers explicit replay after completion and unread-only continuation when new replies arrive', async () => {
  const { speechSignature } = await import('@/services/conversationSpeech');
  state.readProgress = { chat: { reply: speechSignature(messages[0]) } };
  const user = userEvent.setup();
  const view = render(<ConversationReadout conversationId="chat" messages={messages} />);
  await user.click(screen.getByRole('button', { name: 'Read again' }));
  expect(state.startReading).toHaveBeenCalledWith('chat', messages, true);
  view.rerender(<ConversationReadout conversationId="chat" messages={[...messages, { id: 'new', content: 'She waves.', role: 'assistant' }]} />);
  expect(screen.getByRole('button', { name: 'Continue reading' })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('1 unread reply');
});

it('cancels the old conversation on navigation even when the component is reused', () => {
  state.readingConversationId = 'chat';
  const view = render(<ConversationReadout conversationId="chat" messages={messages} />);
  view.rerender(<ConversationReadout conversationId="other" messages={messages} />);
  expect(state.stopReading).toHaveBeenCalled();
});
