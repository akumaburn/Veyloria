import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CharactersPage from '@/pages/CharactersPage';
import { CharacterSettings } from '@/components/settings/CharacterSettings';
import type { Character, CharacterDraft } from '@/graphql/types';

const { state } = vi.hoisted(() => ({
  state: {
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
  },
}));
vi.mock('@/stores/characterStore', () => ({
  useCharacterStore: (selector?: (value: typeof state) => unknown) =>
    selector ? selector(state) : state,
}));
vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useQuery: () => ({ data: undefined, loading: false, error: undefined, refetch: vi.fn() }),
  useMutation: () => [vi.fn(), { loading: false }],
}));
const original = {
  id: 'saved-torres',
  name: 'Maya Torres',
  description: 'Original character',
  systemPrompt: 'Original instructions',
  category: 'general',
  isPublic: false,
  createdAt: '2026-11-13T12:47:58Z',
  updatedAt: '2026-11-13T12:47:58Z',
} as Character;
const generated = {
  ...original,
  id: 'unsaved-chen',
  name: 'Maya Chen',
  systemPrompt: 'New instructions',
};
const generatedDraft: CharacterDraft = {
  character: generated,
  lawfulness: 70,
  goodness: 60,
  alignment: 'LAWFUL_GOOD',
  relationships: [],
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

beforeEach(() => {
  state.characters = [original];
  state.isLoading = false;
  state.fetchCharacters.mockResolvedValue(undefined);
  state.createCharacter.mockResolvedValue({ ...generated, id: 'new-saved-id' });
  state.updateCharacter.mockResolvedValue(undefined);
  state.generateCharacter.mockResolvedValue(generatedDraft);
});

for (const [name, Component] of [
  ['Characters page', CharactersPage],
  ['Character settings', CharacterSettings],
] as const) {
  describe(name, () => {
    it('saves a generated character as a new record after editing an existing character', async () => {
      const user = userEvent.setup();
      render(<Component />);
      const charactersTab = screen.queryByRole('tab', { name: /^Characters/ });
      if (charactersTab) await user.click(charactersTab);
      await user.click(screen.getByTitle('Edit'));
      expect(screen.getByRole('textbox', { name: /^Name/ })).toHaveValue('Maya Torres');
      await user.click(screen.getByRole('button', { name: /back to characters/i }));
      await user.click(screen.getByRole('button', { name: /Generate( with AI)?$/ }));
      await user.type(
        screen.getByRole('textbox', { name: 'Character description' }),
        'Create Maya Chen',
      );
      await user.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Generate' }),
      );
      expect(await screen.findByRole('textbox', { name: /^Name/ })).toHaveValue('Maya Chen');
      await user.click(screen.getByRole('button', { name: 'Create Character' }));
      expect(state.createCharacter).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Maya Chen',
          systemPrompt: 'New instructions',
          lawfulness: 70,
          goodness: 60,
        }),
      );
      expect(state.updateCharacter).not.toHaveBeenCalled();
      expect(original.name).toBe('Maya Torres');
    });

    it('ignores a canceled generation even when it finishes after its replacement', async () => {
      const user = userEvent.setup();
      const old = deferred<CharacterDraft>();
      state.generateCharacter
        .mockReturnValueOnce(old.promise)
        .mockResolvedValueOnce(generatedDraft);
      render(<Component />);
      const charactersTab = screen.queryByRole('tab', { name: /^Characters/ });
      if (charactersTab) await user.click(charactersTab);
      await user.click(screen.getByRole('button', { name: /Generate( with AI)?$/ }));
      await user.type(screen.getByRole('textbox', { name: 'Character description' }), 'Old draft');
      await user.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Generate' }),
      );
      const signal = state.generateCharacter.mock.calls[0][1] as AbortSignal;
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      expect(signal.aborted).toBe(true);
      await user.click(screen.getByRole('button', { name: /Generate( with AI)?$/ }));
      await user.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Generate' }),
      );
      expect(await screen.findByRole('textbox', { name: /^Name/ })).toHaveValue('Maya Chen');
      await act(async () => {
        old.resolve({ ...generatedDraft, character: { ...original, name: 'Late stale draft' } });
        await old.promise;
      });
      expect(screen.getByRole('textbox', { name: /^Name/ })).toHaveValue('Maya Chen');
      expect(state.createCharacter).not.toHaveBeenCalled();
      expect(state.updateCharacter).not.toHaveBeenCalled();
    });

    it('does not submit duplicate generation requests and aborts on unmount', async () => {
      const user = userEvent.setup();
      const pending = deferred<CharacterDraft>();
      state.generateCharacter.mockReturnValueOnce(pending.promise);
      const view = render(<Component />);
      await user.click(screen.getByRole('button', { name: /Generate( with AI)?$/ }));
      await user.type(screen.getByRole('textbox', { name: 'Character description' }), 'One draft');
      await user.dblClick(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Generate' }),
      );
      expect(state.generateCharacter).toHaveBeenCalledOnce();
      await waitFor(() =>
        expect(screen.getByRole('button', { name: /Generating/ })).toBeDisabled(),
      );
      const signal = state.generateCharacter.mock.calls[0][1] as AbortSignal;
      view.unmount();
      expect(signal.aborted).toBe(true);
      await act(async () => {
        pending.resolve(generatedDraft);
        await pending.promise;
      });
    });
  });
}
