import { beforeEach, expect, it, vi } from 'vitest';
import type { Character, CharacterDraft } from '@/graphql/types';

const client = vi.hoisted(() => ({ mutate: vi.fn(), refetchQueries: vi.fn() }));
vi.mock('@/lib/apollo-client', () => ({ apolloClient: client }));
import { useCharacterStore } from '@/stores/characterStore';

const character = (id: string, name: string) =>
  ({
    id,
    name,
    systemPrompt: 'Shared world and history. Connected to the rest of the roster.',
    description: 'A resident.',
    category: 'roleplay',
    isPublic: false,
  }) as Character;

const draft = (id: string, name: string, target?: string): CharacterDraft => ({
  character: character(id, name),
  lawfulness: 70,
  goodness: 60,
  alignment: 'LAWFUL_GOOD',
  relationships: target
    ? [{ targetName: target, tier: 'HATE', reason: 'Sold out the harbor' }]
    : [],
});

const existing = character('existing', 'Maya Chen');
const drafts = [draft('draft-one', 'Maya Chen', 'Maya Torres'), draft('draft-two', 'Maya Torres')];

beforeEach(() => {
  useCharacterStore.setState({
    characters: [existing],
    userCharacters: [existing],
    isLoading: false,
  });
});

it('returns isolated drafts without inserting them into the list or changing shared loading state', async () => {
  client.mutate.mockResolvedValue({ data: { generateCharacterRoster: drafts } });
  useCharacterStore.setState({ isLoading: true });
  const signal = new AbortController().signal;
  expect(
    await useCharacterStore
      .getState()
      .generateCharacterRoster('A harbor city after the siege', 2, signal),
  ).toEqual(drafts);
  expect(useCharacterStore.getState().characters).toEqual([existing]);
  expect(useCharacterStore.getState().isLoading).toBe(true);
  expect(client.mutate).toHaveBeenCalledWith(
    expect.objectContaining({
      variables: { prompt: 'A harbor city after the siege', count: 2 },
      fetchPolicy: 'no-cache',
      errorPolicy: 'none',
      context: { fetchOptions: { signal } },
    }),
  );
});

it('saves the generated alignment and feelings without sending draft IDs', async () => {
  const saved = drafts.map((item) => ({
    ...item.character,
    id: `saved-${item.character.id}`,
  }));
  client.mutate.mockResolvedValue({ data: { createCharacterRoster: saved } });
  await useCharacterStore.getState().createCharacterRoster(drafts);
  expect(client.mutate).toHaveBeenCalledOnce();
  const inputs = client.mutate.mock.calls[0][0].variables.inputs;
  expect(inputs).toHaveLength(2);
  expect(inputs[0].character).toMatchObject({ name: 'Maya Chen', lawfulness: 70, goodness: 60 });
  expect(inputs[0].relationships).toEqual([
    { targetName: 'Maya Torres', tier: 'HATE', reason: 'Sold out the harbor' },
  ]);
  for (const input of inputs) {
    expect(input.character).not.toHaveProperty('id');
    expect(input.character).not.toHaveProperty('ownerId');
    expect(input.character.systemPrompt).toContain('Shared world');
  }
  expect(useCharacterStore.getState().characters.map((character) => character.id)).toEqual([
    'saved-draft-one',
    'saved-draft-two',
    'existing',
  ]);
  expect(
    useCharacterStore.getState().characters.find((character) => character.id === 'existing'),
  ).toEqual(existing);
});

it('leaves both character lists untouched when the atomic save fails', async () => {
  client.mutate.mockRejectedValue(new Error('Transaction rolled back'));
  await expect(useCharacterStore.getState().createCharacterRoster(drafts)).rejects.toThrow(
    'Transaction rolled back',
  );
  expect(useCharacterStore.getState().characters).toEqual([existing]);
  expect(useCharacterStore.getState().userCharacters).toEqual([existing]);
});
