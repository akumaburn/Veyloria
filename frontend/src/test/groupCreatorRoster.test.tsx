import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { GroupConversationCreator } from '@/components/chat/GroupConversationCreator';
import type { Character } from '@/graphql/types';

const data = vi.hoisted(() => ({
  characters: [
    { id: 'maya', name: 'Maya Chen', description: 'A harbor negotiator.' },
    { id: 'rook', name: 'Captain Rook', description: 'A dock captain.' },
    { id: 'sable', name: 'Sable Quinn', description: 'A fence with a conscience.' },
  ] as Character[],
  rosters: [
    {
      id: 'roster-harbor',
      name: 'Harbor Guild',
      description: 'A harbor city rebuilding after a siege.',
      characterIds: ['maya', 'rook', 'sable'],
      characterCount: 3,
    },
  ],
}));

const createGroupConversation = vi.hoisted(() => vi.fn());

vi.mock('@/hooks', () => ({
  useCharacters: () => ({ characters: data.characters, loading: false }),
  useCharacterGroups: () => ({ rosters: data.rosters, loading: false }),
}));

vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useMutation: () => [createGroupConversation, { loading: false }],
}));

beforeEach(() => {
  createGroupConversation.mockReset();
  createGroupConversation.mockResolvedValue({
    data: { createGroupConversation: { id: 'group-1' } },
  });
});

it('selects a whole roster for a group chat', async () => {
  const user = userEvent.setup();
  const onCreated = vi.fn();

  render(<GroupConversationCreator onCreated={onCreated} onClose={vi.fn()} />);

  await user.click(screen.getByRole('button', { name: 'Add 3 members of Harbor Guild' }));
  expect(screen.getByText('3 characters selected')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: /Start Group Chat/ }));

  await waitFor(() => expect(createGroupConversation).toHaveBeenCalledTimes(1));
  expect(createGroupConversation.mock.calls[0][0].variables.input).toEqual(
    expect.objectContaining({
      characterIds: ['maya', 'rook', 'sable'],
      turnMode: 'RANDOM',
      maxConsecutiveResponses: 3,
    })
  );
  expect(onCreated).toHaveBeenCalledWith('group-1');
});
