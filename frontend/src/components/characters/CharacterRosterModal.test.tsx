import { useState } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import type { Character, CharacterDraft } from '@/graphql/types';
import { CharacterRosterModal } from './CharacterRosterModal';

const mocks = vi.hoisted(() => ({
  generate: vi.fn(),
  save: vi.fn(),
  close: vi.fn(),
  saved: vi.fn(),
}));
vi.mock('@/stores/characterStore', () => ({
  useCharacterStore: (selector: (state: unknown) => unknown) =>
    selector({ generateCharacterRoster: mocks.generate, createCharacterRoster: mocks.save }),
}));
vi.mock('react-hot-toast', () => ({ toast: { success: vi.fn() } }));
const context =
  'A harbor city rebuilding after a siege, with rival merchant guilds and displaced families.';
const roster = (count: number): CharacterDraft[] =>
  Array.from({ length: count }, (_, i) => ({
    character: {
      id: `draft-${i}`,
      name: `Resident ${i + 1}`,
      description: 'A guild member in the harbor.',
      systemPrompt: `${context}\nConnections: Resident ${((i + 1) % count) + 1}`,
      backstory: 'Shared rebuilding work after the siege.',
    } as Character,
    lawfulness: 70,
    goodness: 60,
    alignment: 'LAWFUL_GOOD',
    relationships:
      count > 1 && i === 0
        ? [{ targetName: 'Resident 2', tier: 'HATE', reason: 'Sold out the harbor' }]
        : [],
  }));
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { resolve, promise };
}
function mount() {
  render(<CharacterRosterModal onClose={mocks.close} onSaved={mocks.saved} />);
}
async function fill(count = 2) {
  const user = userEvent.setup();
  await user.type(screen.getByRole('textbox', { name: /Location, world & history/ }), context);
  fireEvent.change(screen.getByRole('slider', { name: 'Number of characters' }), {
    target: { value: String(count) },
  });
  return user;
}
beforeEach(() => {
  mocks.generate.mockImplementation(async (_context: string, count: number) => roster(count));
  mocks.save.mockImplementation(async (drafts: CharacterDraft[]) =>
    drafts.map((draft) => ({ ...draft.character, id: `saved-${draft.character.id}` })),
  );
});

it.each([2, 12])(
  'generates exactly %i characters from shared context and leaves them unsaved for review',
  async (count) => {
    mount();
    const user = await fill(count);
    const slider = screen.getByRole('slider', { name: 'Number of characters' });
    expect(slider).toHaveAttribute('min', '2');
    expect(slider).toHaveAttribute('max', '12');
    expect(slider).toHaveAttribute('aria-valuetext', `${count} characters`);
    await user.click(screen.getByRole('button', { name: 'Generate roster' }));
    expect(mocks.generate).toHaveBeenCalledWith(context, count, expect.any(AbortSignal));
    expect(await screen.findByRole('heading', { name: 'Review your roster' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(count);
    const alignments = screen.getAllByRole('combobox', { name: /^Alignment for/ });
    expect(alignments).toHaveLength(count);
    for (const select of alignments) expect(select).toHaveValue('LAWFUL_GOOD');
    expect(mocks.save).not.toHaveBeenCalled();
    await user.click(screen.getByText('Shared setting & history'));
    expect(screen.getByText(context)).toBeVisible();
  },
);

it('edits a generated feeling and an alignment before saving', async () => {
  mount();
  const user = await fill();
  await user.click(screen.getByRole('button', { name: 'Generate roster' }));
  await screen.findByRole('heading', { name: 'Review your roster' });

  const feeling = screen.getByRole('combobox', {
    name: 'Feeling of Resident 1 toward Resident 2',
  });
  expect(feeling).toHaveValue('HATE');
  await user.selectOptions(feeling, 'LOVE');
  const alignment = screen.getByRole('combobox', { name: 'Alignment for Resident 1' });
  await user.selectOptions(alignment, 'CHAOTIC_EVIL');

  await user.click(screen.getByRole('button', { name: 'Save all 2 characters' }));
  await waitFor(() => expect(mocks.save).toHaveBeenCalledOnce());
  const saved = mocks.save.mock.calls[0][0] as CharacterDraft[];
  expect(saved[0].alignment).toBe('CHAOTIC_EVIL');
  expect(saved[0].lawfulness).toBe(-70);
  expect(saved[0].goodness).toBe(-70);
  expect(saved[0].relationships[0]).toMatchObject({ targetName: 'Resident 2', tier: 'LOVE' });
});

it('saves the reviewed roster in one call and prevents duplicate submissions', async () => {
  mount();
  const user = await fill();
  await user.click(screen.getByRole('button', { name: 'Generate roster' }));
  const pending = deferred<Character[]>();
  mocks.save.mockReturnValueOnce(pending.promise);
  await user.dblClick(await screen.findByRole('button', { name: 'Save all 2 characters' }));
  expect(mocks.save).toHaveBeenCalledOnce();
  expect(mocks.save).toHaveBeenCalledWith(roster(2), expect.any(String), expect.any(String));
  expect(screen.getByRole('button', { name: 'Saving roster…' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  await act(async () => {
    pending.resolve(roster(2).map((draft) => draft.character));
    await pending.promise;
  });
  expect(mocks.saved).toHaveBeenCalledOnce();
  expect(mocks.close).toHaveBeenCalledOnce();
});

it('keeps drafts available when the save fails', async () => {
  mount();
  const user = await fill();
  await user.click(screen.getByRole('button', { name: 'Generate roster' }));
  mocks.save.mockRejectedValueOnce(new Error('Unable to save this roster'));
  await user.click(await screen.findByRole('button', { name: 'Save all 2 characters' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Unable to save this roster');
  expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(2);
  expect(mocks.close).not.toHaveBeenCalled();
});

it('aborts canceled generation and ignores a late result', async () => {
  const pending = deferred<CharacterDraft[]>();
  mocks.generate.mockReturnValueOnce(pending.promise);
  function Example() {
    const [open, setOpen] = useState(true);
    return open ? (
      <CharacterRosterModal onClose={() => setOpen(false)} onSaved={mocks.saved} />
    ) : (
      <p>Closed</p>
    );
  }
  render(<Example />);
  const user = await fill();
  await user.dblClick(screen.getByRole('button', { name: 'Generate roster' }));
  expect(mocks.generate).toHaveBeenCalledOnce();
  const signal = mocks.generate.mock.calls[0][2] as AbortSignal;
  await user.click(screen.getByRole('button', { name: 'Cancel generation' }));
  expect(signal.aborted).toBe(true);
  await act(async () => {
    pending.resolve(roster(2));
    await pending.promise;
  });
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(mocks.save).not.toHaveBeenCalled();
});

it('shows a generation error and preserves the prompt and count for retry', async () => {
  mount();
  const user = await fill(12);
  mocks.generate.mockRejectedValueOnce(
    new Error('The cast is not connected. Please generate again.'),
  );
  await user.click(screen.getByRole('button', { name: 'Generate roster' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('The cast is not connected');
  expect(screen.getByRole('textbox')).toHaveValue(context);
  expect(screen.getByRole('slider')).toHaveValue('12');
  expect(
    within(screen.getByRole('dialog')).getByRole('button', { name: 'Generate roster' }),
  ).toBeEnabled();
});
