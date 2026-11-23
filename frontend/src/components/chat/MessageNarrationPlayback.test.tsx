import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { MessageItem } from './MessageItem';
import type { Message } from './MessageList';

const state = vi.hoisted(() => ({
  settings: { enabled: true }, playbackStates: new Map(),
  play: vi.fn(), pause: vi.fn(), resume: vi.fn(), restart: vi.fn(),
}));
vi.mock('@/stores/ttsStore', () => ({ useTTSStore: () => state }));
vi.mock('@apollo/client', async importOriginal => ({
  ...await importOriginal<typeof import('@apollo/client')>(),
  useMutation: () => [vi.fn(), { loading: false }],
  useQuery: () => ({ data: undefined, loading: false }),
}));
const character = { id: 'elara', name: 'Elara', gender: 'Woman', personality: 'Impulsive.' };
const message: Message = { id: 'narration', content: 'I hear the storm.', role: 'system',
  messageType: 'NARRATION', timestamp: '2026-11-19T12:00:00Z', character };
const actions = { onRetry: vi.fn(), onEdit: vi.fn(), onDelete: vi.fn() };
beforeEach(() => { state.play.mockResolvedValue(undefined); });

it('plays narration through the narrator even when it contains I and has an associated character', async () => {
  const user = userEvent.setup();
  render(<MessageItem message={message} {...actions} />);
  await user.click(screen.getByRole('button', { name: 'Play voice playback' }));
  expect(state.play).toHaveBeenCalledWith('narration', message.content, undefined, undefined);
});

it('plays character action blocks with the character profile for first-person detection', async () => {
  const user = userEvent.setup();
  render(<MessageItem message={{ ...message, id: 'action', role: 'assistant', messageType: 'ACTION', content: '*I shift my weight.*' }} {...actions} />);
  await user.click(screen.getByRole('button', { name: 'Play voice playback' }));
  expect(state.play).toHaveBeenCalledWith('action', '*I shift my weight.*', undefined, character);
});

it('does not give player narration a voice playback button', () => {
  render(<MessageItem message={{ ...message, role: 'user' }} {...actions} />);
  expect(screen.queryByRole('button', { name: 'Play voice playback' })).not.toBeInTheDocument();
});

it('links saved combat narration to its battle in chat', () => {
  render(<MemoryRouter><MessageItem message={{ ...message, battleId: 'fight' }} conversationId="story" {...actions} /></MemoryRouter>);
  expect(screen.getByRole('link', { name: 'Open this encounter' })).toHaveAttribute('href', '/chat/story?battle=fight');
});

it.each(['NARRATION', 'ACTION', 'DIALOGUE'] as const)('hides history rewriting for player-mode %s while preserving playback', messageType => {
  const props = { ...actions, message: { ...message, role: 'assistant' as const, messageType } };
  const { rerender } = render(<MessageItem {...props} allowStoryEditing={false} />);
  expect(screen.queryByTitle(/Delete/)).not.toBeInTheDocument();
  expect(screen.queryByTitle('Regenerate response')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Play voice playback' })).toBeInTheDocument();
  rerender(<MessageItem {...props} allowStoryEditing />);
  expect(screen.getByTitle(/Delete/)).toBeInTheDocument();
});
