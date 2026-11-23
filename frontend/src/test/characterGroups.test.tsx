import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import CharactersPage from '@/pages/CharactersPage';
import type { Character } from '@/graphql/types';

const state = vi.hoisted(() => ({
  characters: [] as Character[],
  isLoading: false,
  error: null,
  fetchCharacters: vi.fn(),
  createCharacter: vi.fn(),
  updateCharacter: vi.fn(),
  deleteCharacter: vi.fn(),
  cloneCharacter: vi.fn(),
  generateCharacter: vi.fn(),
  clearError: vi.fn(),
}));

const data = vi.hoisted(() => ({
  characterAlignments: [
    {
      alignmentId: 'alignment-maya',
      characterId: 'maya',
      name: 'Maya',
      lawfulness: 70,
      goodness: 70,
      alignment: 'LAWFUL_GOOD',
      updatedAt: '2026-11-13T12:00:00',
      events: [],
    },
  ],
  characterGroups: [
    {
      id: 'group-1',
      name: 'Harbor Guild',
      description: 'A harbor city rebuilding after a siege.',
      setting: 'A harbor city rebuilding after a siege.',
      characterIds: ['maya', 'rook'],
      characterCount: 2,
      createdAt: '2026-11-13T12:00:00',
      updatedAt: '2026-11-13T12:00:00',
    },
  ],
}));

vi.mock('@/stores/characterStore', () => ({
  useCharacterStore: (selector?: (value: typeof state) => unknown) =>
    selector ? selector(state) : state,
}));
vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useQuery: () => ({ data, loading: false, error: undefined, refetch: vi.fn() }),
  useMutation: () => [vi.fn(), { loading: false }],
}));

const maya = {
  id: 'maya',
  name: 'Maya Chen',
  description: 'A harbor negotiator.',
  systemPrompt: 'Speak as Maya.',
  category: 'roleplay',
  isPublic: false,
} as Character;
const rook = {
  id: 'rook',
  name: 'Captain Rook',
  description: 'A dock captain.',
  systemPrompt: 'Speak as Rook.',
  category: 'roleplay',
  isPublic: false,
} as Character;

beforeEach(() => {
  state.characters = [maya, rook];
  state.isLoading = false;
  state.fetchCharacters.mockResolvedValue(undefined);
});

it('opens on saved rosters and lists the roster members', async () => {
  const user = userEvent.setup();
  render(<CharactersPage />);

  expect(screen.getByRole('tab', { name: /^Rosters/ })).toHaveAttribute('aria-selected', 'true');
  expect(screen.getByText('Harbor Guild')).toBeInTheDocument();
  expect(screen.getByText('2 of 2 characters · generated roster')).toBeInTheDocument();
  expect(screen.getAllByRole('button', { name: /Maya Chen/ }).length).toBeGreaterThan(0);

  await user.click(screen.getByRole('tab', { name: /^Characters/ }));
  expect(screen.getByRole('tab', { name: /^Characters/ })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  expect(screen.getByText('Lawful Good')).toBeInTheDocument();
});
