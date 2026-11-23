import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import type { Character, CharacterStat } from '@/graphql/types';
import { CharacterCardModal } from './CharacterCardModal';
import { useCharacterCardStore } from '@/stores/characterCardStore';

const mocks = vi.hoisted(() => ({
  data: undefined as { character: Character | null } | undefined,
  stats: undefined as { characterStats: CharacterStat[] } | undefined,
  loading: false,
  error: undefined as Error | undefined,
}));

vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useQuery: (
    document: { definitions?: Array<{ name?: { value?: string } }> },
    options?: { skip?: boolean },
  ) => {
    const name = document?.definitions?.[0]?.name?.value;
    if (options?.skip) return { data: undefined, loading: false, error: undefined };
    if (name === 'CharacterStats') {
      return { data: mocks.stats, loading: false, error: undefined };
    }
    return { data: mocks.data, loading: mocks.loading, error: mocks.error };
  },
}));

const elara: Character = {
  id: '830a5b95-4620-41a9-bb55-c15dff692d55',
  name: 'Elara Vane',
  description: 'A student diviner who reads the Dungeon through sound.',
  personality: 'Precise, blunt, and slow to trust a plan she did not test.',
  avatar: 'https://example.test/elara.png',
  systemPrompt: 'You are Elara Vane.',
  temperature: 0.8,
  maxTokens: 800,
  topP: 0.9,
  topK: 40,
  repeatPenalty: 1.1,
  isPublic: false,
  isDefault: false,
  usageCount: 4,
  creator: { id: 'user-1', username: 'amir' },
  tags: ['student', 'diviner'],
  category: 'Academy',
  appearance: 'Tall, dark braid, silver eyes, a worn field coat.',
  likes: 'Quiet libraries and a reading that repeats.',
  dislikes: 'Guesswork passed off as certainty.',
  backstory: 'Raised by the docks until the Academy bought her a place.',
  goals: 'Finish the dive without losing anyone in her care.',
  gender: 'Woman',
  pronouns: 'she/her',
  ageYears: 19,
  height: '5 ft 8 in / 173 cm',
  build: 'Lean and quick',
  hair: 'Dark brown, braided tight',
  eyes: 'Silver-gray',
  strength: 9,
  dexterity: 13,
  constitution: 11,
  intelligence: 16,
  wisdom: 12,
  charisma: 10,
  createdAt: '2026-11-16T12:00:00',
  updatedAt: '2026-11-16T12:00:00',
};

const stats: CharacterStat[] = [
  {
    statId: 'stat-1',
    conversationId: 'conversation-1',
    characterId: elara.id,
    statName: 'Health',
    statValue: 14,
    statMax: 20,
    statType: 'numeric',
    statCategory: 'Vitals',
  },
  {
    statId: 'stat-2',
    conversationId: 'conversation-1',
    characterId: elara.id,
    statName: 'Mana',
    statValue: 9,
    statMax: 12,
    statType: 'numeric',
    statCategory: 'Vitals',
  },
];

beforeEach(() => {
  mocks.data = { character: elara };
  mocks.stats = { characterStats: stats };
  mocks.loading = false;
  mocks.error = undefined;
  useCharacterCardStore.setState({ characterId: elara.id, conversationId: null });
});

it('shows the character card with the full profile behind the avatar', () => {
  render(<CharacterCardModal />);

  expect(screen.getByRole('heading', { name: 'Elara Vane' })).toBeInTheDocument();
  expect(screen.getByText(elara.description)).toBeInTheDocument();
  expect(screen.getByText(elara.personality!)).toBeInTheDocument();
  expect(screen.getByText(elara.appearance!)).toBeInTheDocument();
  expect(screen.getByText(elara.likes!)).toBeInTheDocument();
  expect(screen.getByText(elara.dislikes!)).toBeInTheDocument();
  expect(screen.getByText(elara.backstory!)).toBeInTheDocument();
  expect(screen.getByText(elara.goals!)).toBeInTheDocument();
  expect(screen.getByText('Academy')).toBeInTheDocument();
  expect(screen.getByText('diviner')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Elara Vane' })).toHaveAttribute(
    'src',
    elara.avatar,
  );
});

it('shows physical facts and a base attribute breakdown', () => {
  render(<CharacterCardModal />);

  expect(screen.getByText('Woman')).toBeInTheDocument();
  expect(screen.getByText('she/her')).toBeInTheDocument();
  expect(screen.getByText('19')).toBeInTheDocument();
  expect(screen.getByText('5 ft 8 in / 173 cm')).toBeInTheDocument();
  expect(screen.getByText('Lean and quick')).toBeInTheDocument();
  expect(screen.getByText('Dark brown, braided tight')).toBeInTheDocument();
  expect(screen.getByText('Silver-gray')).toBeInTheDocument();
  expect(screen.getByRole('meter', { name: 'Strength: 9 of 20' })).toHaveAttribute(
    'aria-valuenow',
    '9',
  );
  expect(screen.getByRole('meter', { name: 'Intelligence: 16 of 20' })).toBeInTheDocument();
  expect(screen.getByRole('meter', { name: 'Charisma: 10 of 20' })).toBeInTheDocument();
});

it('shows the stats recorded in the story the card was opened from', () => {
  useCharacterCardStore.setState({ characterId: elara.id, conversationId: 'conversation-1' });
  render(<CharacterCardModal />);

  expect(screen.getByRole('region', { name: 'Current stats' })).toBeInTheDocument();
  expect(screen.getByText('Vitals')).toBeInTheDocument();
  expect(screen.getByText('14 / 20')).toBeInTheDocument();
  expect(screen.getByRole('meter', { name: 'Health: 14 of 20' })).toHaveAttribute(
    'aria-valuenow',
    '14',
  );
});

it('hides the story stats when the card did not come from a story', () => {
  render(<CharacterCardModal />);

  expect(screen.queryByRole('region', { name: 'Current stats' })).not.toBeInTheDocument();
});

it('closes the card and clears the selected character', async () => {
  const user = userEvent.setup();
  render(<CharacterCardModal />);

  await user.click(screen.getByRole('button', { name: 'Close modal' }));

  expect(useCharacterCardStore.getState().characterId).toBeNull();
});

it('reports a character that is no longer available', () => {
  mocks.data = { character: null };
  render(<CharacterCardModal />);

  expect(screen.getByText('This character is no longer available.')).toBeInTheDocument();
});
