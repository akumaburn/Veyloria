import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import RoleplayConversationCreator from '@/components/roleplay/RoleplayConversationCreator';
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

const createRoleplayConversation = vi.hoisted(() => vi.fn());

vi.mock('@/hooks', () => ({
  useCharacters: () => ({ characters: data.characters, loading: false }),
  useCharacterGroups: () => ({ rosters: data.rosters, loading: false }),
}));

vi.mock('@/hooks/useRoleplay', () => ({
  useCreateRoleplayConversation: () => ({
    createRoleplayConversation,
    loading: false,
  }),
  useStoryTemplates: () => ({ templates: [], loading: false }),
}));

beforeEach(() => {
  createRoleplayConversation.mockReset();
  createRoleplayConversation.mockResolvedValue({
    data: { createRoleplayConversation: { id: 'conversation-1' } },
  });
});

it('creates a story from a saved roster and keeps the description', async () => {
  const user = userEvent.setup();
  const onCreated = vi.fn();
  const onClose = vi.fn();

  render(
    <RoleplayConversationCreator isOpen onClose={onClose} onCreated={onCreated} />
  );

  await user.click(screen.getByRole('button', { name: 'Add 3 members of Harbor Guild' }));
  expect(screen.getByText('3 selected')).toBeInTheDocument();

  await user.type(screen.getByLabelText('Title'), 'Siege of Harbor');
  await user.type(
    screen.getByLabelText('Description'),
    'A harbor city rebuilding after a siege.'
  );

  await user.click(screen.getByRole('button', { name: /Create Roleplay/ }));

  await waitFor(() => expect(createRoleplayConversation).toHaveBeenCalledTimes(1));
  expect(createRoleplayConversation.mock.calls[0][0]).toEqual(
    expect.objectContaining({
      title: 'Siege of Harbor',
      description: 'A harbor city rebuilding after a siege.',
      characterIds: ['maya', 'rook', 'sable'],
      rpMode: 'narrative',
      turnMode: 'ROUND_ROBIN',
      agentPipelineEnabled: true,
    })
  );
  expect(onCreated).toHaveBeenCalledWith('conversation-1');
  expect(onClose).toHaveBeenCalled();
});

it('clears a roster selection when its chip is clicked again', async () => {
  const user = userEvent.setup();

  render(<RoleplayConversationCreator isOpen onClose={vi.fn()} onCreated={vi.fn()} />);

  await user.click(screen.getByRole('button', { name: 'Add 3 members of Harbor Guild' }));
  expect(screen.getByText('3 selected')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Remove all 3 members of Harbor Guild' }));
  expect(screen.queryByText('3 selected')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Create Roleplay/ })).toBeDisabled();
});
