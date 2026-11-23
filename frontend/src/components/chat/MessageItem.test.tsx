import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { useCharacterCardStore } from '@/stores/characterCardStore';
import { MessageItem } from './MessageItem';
import type { Message } from './MessageList';

vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useMutation: () => [vi.fn(), { loading: false }],
  useQuery: () => ({ data: undefined, loading: false, error: undefined }),
}));

const message: Message = {
  id: 'message-1',
  content: 'The contact is late, not dead. We hold.',
  role: 'assistant',
  timestamp: '2026-11-16T18:30:00',
  displayName: 'Elara Vane',
  characterId: '830a5b95-4620-41a9-bb55-c15dff692d55',
  character: {
    id: '830a5b95-4620-41a9-bb55-c15dff692d55',
    name: 'Elara Vane',
    avatar: 'https://example.test/elara.png',
  },
};

beforeEach(() => {
  useCharacterCardStore.setState({ characterId: null });
});

it('opens the character card when a message avatar is clicked', async () => {
  const user = userEvent.setup();
  render(
    <MessageItem
      message={message}
      isGroupChat
      onRetry={vi.fn()}
      onEdit={vi.fn()}
      onDelete={vi.fn()}
    />,
  );

  await user.click(screen.getByRole('button', { name: /Open Elara Vane's character card/ }));

  expect(useCharacterCardStore.getState().characterId).toBe(message.character!.id);
});

it('opens the card from the keyboard on a message avatar', async () => {
  const user = userEvent.setup();
  render(
    <MessageItem
      message={message}
      isGroupChat
      onRetry={vi.fn()}
      onEdit={vi.fn()}
      onDelete={vi.fn()}
    />,
  );

  screen.getByRole('button', { name: /Open Elara Vane's character card/ }).focus();
  await user.keyboard('{Enter}');

  expect(useCharacterCardStore.getState().characterId).toBe(message.character!.id);
});
