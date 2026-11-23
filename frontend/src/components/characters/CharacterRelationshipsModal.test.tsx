import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import type { RelationshipsData } from './relationships';
import { CharacterRelationshipsModal } from './CharacterRelationshipsModal';

const mocks = vi.hoisted(() => ({
  data: undefined as RelationshipsData | undefined,
  refetch: vi.fn(),
  react: vi.fn(),
  setExact: vi.fn(),
}));

vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useQuery: () => ({ data: mocks.data, loading: false, error: undefined, refetch: mocks.refetch }),
  useMutation: (document: { definitions?: Array<{ name?: { value?: string } }> }) =>
    document?.definitions?.[0]?.name?.value === 'SetCharacterAffinity'
      ? [mocks.setExact, { loading: false }]
      : [mocks.react, { loading: false }],
}));

const sableId = '22222222-2222-4222-8222-222222222222';

beforeEach(() => {
  mocks.data = {
    relationshipActors: [
      {
        characterId: sableId,
        name: 'Sable',
        initialAffinityToUser: 60,
        initialAffinityFromUser: -20,
      },
    ],
    characterAffinities: [],
  };
  mocks.refetch.mockReset();
  mocks.react.mockReset();
  mocks.setExact.mockReset();
});

it('shows the configured starting feeling when the pair has no saved record', () => {
  render(<CharacterRelationshipsModal characterId={sableId} onClose={vi.fn()} />);
  const current = screen.getByRole('region', { name: 'Current relationship' });
  expect(screen.getByText('Starting value')).toBeInTheDocument();
  expect(current).toHaveTextContent('Dislike');
  expect(screen.getByRole('meter', { name: 'Relationship points' })).toHaveAttribute(
    'aria-valuenow',
    '-20',
  );
});

it('shows the other direction from the same starting configuration', async () => {
  const user = userEvent.setup();
  render(<CharacterRelationshipsModal characterId={sableId} onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: 'Reverse relationship direction' }));
  const current = screen.getByRole('region', { name: 'Current relationship' });
  expect(current).toHaveTextContent('Love');
  expect(current).toHaveTextContent('+60');
});

it('records a reaction with a stable request id and clears the starting badge', async () => {
  const user = userEvent.setup();
  mocks.react.mockImplementation(async () => ({
    data: {
      reactToCharacter: {
        affinityId: 'affinity-1',
        fromCharacterId: null,
        toCharacterId: sableId,
        fromName: 'You',
        toName: 'Sable',
        points: -10,
        tier: 'NEUTRAL',
        updatedAt: '2026-11-13T12:00:00',
        events: [],
      },
    },
  }));
  render(<CharacterRelationshipsModal characterId={sableId} onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: 'Like +10' }));
  await waitFor(() => expect(mocks.react).toHaveBeenCalledOnce());
  expect(mocks.react.mock.calls[0][0].variables).toMatchObject({
    fromCharacterId: null,
    toCharacterId: sableId,
    reaction: 'LIKE',
    reason: null,
  });
  expect(mocks.react.mock.calls[0][0].variables.requestId).toMatch(/^[0-9a-f-]{36}$/);
  await waitFor(() => expect(screen.queryByText('Starting value')).not.toBeInTheDocument());
});

it('sets an exact tier instead of adding its delta', async () => {
  const user = userEvent.setup();
  mocks.setExact.mockImplementation(
    async ({ variables }: { variables: Record<string, unknown> }) => ({
      data: {
        setCharacterAffinity: {
          affinityId: 'affinity-1',
          fromCharacterId: null,
          toCharacterId: sableId,
          fromName: 'You',
          toName: 'Sable',
          points: variables.points,
          tier: 'LOVE',
          updatedAt: '2026-11-13T12:00:00',
          events: [],
        },
      },
    }),
  );
  render(<CharacterRelationshipsModal characterId={sableId} onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: 'Set exact feeling: love' }));
  await waitFor(() => expect(mocks.setExact).toHaveBeenCalledOnce());
  expect(mocks.setExact.mock.calls[0][0].variables).toMatchObject({
    fromCharacterId: null,
    toCharacterId: sableId,
    points: 60,
    reason: null,
  });
  expect(mocks.setExact.mock.calls[0][0].variables.requestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(mocks.react).not.toHaveBeenCalled();
  expect(await screen.findByText('You → Sable: Love, +60 points. Saved.')).toBeInTheDocument();
});
