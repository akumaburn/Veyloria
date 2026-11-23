import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import BattleOverlay from './BattleOverlay';
import EncounterNotice from './EncounterNotice';

const state = vi.hoisted(() => ({
  battle: null as any,
  selected: null as any,
  error: undefined as Error | undefined,
  manager: false,
  vote: vi.fn(),
  resolve: vi.fn(),
  end: vi.fn(),
  propose: vi.fn(),
  refetch: vi.fn(),
}));
vi.mock('@/hooks/useRoleplay', () => ({
  useActiveBattle: () => ({ battle: state.battle, error: state.error, refetch: state.refetch }),
  useInitiateBattle: () => ({ initiateBattle: vi.fn() }),
  useVoteAction: () => ({ voteAction: state.vote }),
  useResolveBattleRound: () => ({ resolveBattleRound: state.resolve }),
  useEndBattle: () => ({ endBattle: state.end }),
  useInventoryAccess: () => ({ canManage: state.manager }),
  useBattleRoundSubscription: () => ({}),
  useBattleEndedSubscription: () => ({}),
}));
vi.mock('@apollo/client', async (original) => ({
  ...(await original<typeof import('@apollo/client')>()),
  useMutation: () => [state.propose, {}],
  useQuery: (query: any) => ({
    data:
      query.definitions[0].name.value === 'Battles'
        ? { battles: state.battle ? [state.battle] : [] }
        : { battle: state.selected },
    refetch: state.refetch,
  }),
}));
const props = { conversationId: 'story', currentUserId: 'bryn', participants: [] };
const panel = (options = {}) => (
  <MemoryRouter>
    <BattleOverlay {...props} {...options} />
  </MemoryRouter>
);
beforeEach(() => {
  vi.clearAllMocks();
  state.error = undefined;
  state.manager = false;
  state.selected = null;
  state.battle = {
    battleId: 'fight',
    conversationId: 'story',
    status: 'ACTIVE',
    roundNumber: 2,
    currentNarration: 'Elara held the doorway while the guards advanced.',
    canManage: false,
    battleData: JSON.stringify({ enemyName: 'Road guards', enemyHp: 65, enemyMaxHp: 80 }),
    actionOptions: JSON.stringify({
      actions: [
        { title: 'Negotiate', description: 'Offer a truce.', type: 'special' },
        { title: 'Withdraw', description: 'Fall back together.', type: 'retreat' },
      ],
    }),
    votes: [
      { userId: 'bryn', optionIndex: 1 },
      { userId: 'elias', optionIndex: 1 },
    ],
  };
  state.vote.mockResolvedValue({});
  state.resolve.mockResolvedValue({});
  state.propose.mockResolvedValue({});
  state.refetch.mockResolvedValue({});
});
it('restores server votes and health and sends the displayed round when changing a vote', async () => {
  render(panel());
  expect(screen.getByRole('progressbar', { name: 'Enemy health' })).toHaveAttribute('max', '80');
  expect(screen.getByRole('button', { name: /Withdraw/ })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('button', { name: /Withdraw/ })).toHaveTextContent('2 votes');
  fireEvent.click(screen.getByRole('button', { name: /Negotiate/ }));
  await waitFor(() =>
    expect(state.vote).toHaveBeenCalledWith({ battleId: 'fight', optionIndex: 0, roundNumber: 2 }),
  );
});
it('locks repeat round submissions and includes the current round', async () => {
  let finish!: (value: unknown) => void;
  state.resolve.mockReturnValue(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  render(panel());
  const resolve = screen.getByRole('button', { name: 'Resolve party action' });
  fireEvent.click(resolve);
  fireEvent.click(resolve);
  expect(state.resolve).toHaveBeenCalledTimes(1);
  expect(state.resolve).toHaveBeenCalledWith('fight', 2);
  finish({});
  await waitFor(() => expect(resolve).not.toBeDisabled());
});
it('proposes a custom approach for party voting without claiming it succeeded', async () => {
  render(panel());
  fireEvent.change(screen.getByLabelText('Describe another approach'), {
    target: { value: 'Distract the guards.' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Propose to the party' }));
  await waitFor(() =>
    expect(state.propose).toHaveBeenCalledWith({
      variables: { battleId: 'fight', roundNumber: 2, description: 'Distract the guards.' },
    }),
  );
  expect(screen.getByText(/Proposed approaches are attempts/)).toBeInTheDocument();
});
it('shows persisted outcomes and encounter history after a fight ends', () => {
  state.battle.status = 'RETREAT';
  render(panel());
  expect(screen.getByText('Outcome: retreat')).toBeInTheDocument();
  expect(screen.queryByLabelText('Battle actions')).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Road guards · retreat' })).toHaveAttribute(
    'href',
    '/chat/story?battle=fight',
  );
});
it('uses backend authority for host controls even when narrator mode is enabled', () => {
  render(panel({ narratorMode: true }));
  expect(screen.queryByText('Host outcome override')).not.toBeInTheDocument();
});
it('offers recovery for failed queries', () => {
  state.battle = null;
  state.error = new Error('Offline');
  render(panel());
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(state.refetch).toHaveBeenCalledOnce();
});
it('links from a map encounter directly into that fight in chat', () => {
  render(
    <MemoryRouter>
      <EncounterNotice conversationId="story" />
    </MemoryRouter>,
  );
  expect(screen.getByRole('link', { name: 'Open fight in chat' })).toHaveAttribute(
    'href',
    '/chat/story?battle=fight',
  );
});
