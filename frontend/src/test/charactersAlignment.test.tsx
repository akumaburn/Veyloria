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

const alignment = vi.hoisted(() => ({
  relationshipActors: [{ characterId: 'maya', name: 'Maya' }],
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
}));

vi.mock('@/stores/characterStore', () => ({
  useCharacterStore: (selector?: (value: typeof state) => unknown) =>
    selector ? selector(state) : state,
}));
vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useQuery: () => ({ data: alignment, loading: false, error: undefined, refetch: vi.fn() }),
  useMutation: () => [vi.fn(), { loading: false }],
}));

const maya = {
  id: 'maya',
  name: 'Maya',
  description: 'A harbor negotiator.',
  systemPrompt: 'Speak as Maya.',
  category: 'roleplay',
  isPublic: false,
} as Character;

beforeEach(() => {
  state.characters = [maya];
  state.isLoading = false;
  state.fetchCharacters.mockResolvedValue(undefined);
});

it('shows each character alignment on its card and opens the chart for that character', async () => {
  const user = userEvent.setup();
  render(<CharactersPage />);
  await user.click(screen.getByRole('tab', { name: /^Characters/ }));
  expect(screen.getByText('Lawful Good')).toBeInTheDocument();

  const cardAlignment = screen.getAllByRole('button', { name: 'Alignment' })[1];
  await user.click(cardAlignment);

  expect(await screen.findByRole('heading', { name: 'Moral alignment' })).toBeInTheDocument();
  expect(screen.getAllByText('Lawful Good').length).toBeGreaterThan(0);
  expect(screen.getByText(/Believes in honor, justice/)).toBeInTheDocument();
});
