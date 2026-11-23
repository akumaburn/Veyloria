import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { RosterPicker } from '@/components/characters/RosterPicker';
import type { CharacterGroup } from '@/graphql/types';

const harbor: CharacterGroup = {
  id: 'roster-harbor',
  name: 'Harbor Guild',
  description: 'A harbor city rebuilding after a siege.',
  characterIds: ['maya', 'rook', 'sable'],
  characterCount: 3,
};

const dungeon: CharacterGroup = {
  id: 'roster-dungeon',
  name: 'Dungeon Diving',
  description: 'First-year divers.',
  characterIds: ['nia', 'jinx'],
  characterCount: 2,
};

const names = {
  maya: 'Maya Chen',
  rook: 'Captain Rook',
  sable: 'Sable Quinn',
  nia: 'Nia Firetail',
  jinx: 'Jinx',
};

const noop = () => {};

it('adds every member of a roster in one click', async () => {
  const user = userEvent.setup();
  const onAdd = vi.fn();

  render(
    <RosterPicker
      rosters={[harbor, dungeon]}
      selectedCharacterIds={[]}
      onAdd={onAdd}
      onRemove={noop}
      characterNames={names}
    />
  );

  expect(screen.getByText('Maya Chen, Captain Rook, Sable Quinn')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Add 3 members of Harbor Guild' }));

  expect(onAdd).toHaveBeenCalledTimes(1);
  expect(onAdd).toHaveBeenCalledWith(['maya', 'rook', 'sable']);
});

it('removes a roster whose members are all selected', async () => {
  const user = userEvent.setup();
  const onRemove = vi.fn();

  render(
    <RosterPicker
      rosters={[harbor]}
      selectedCharacterIds={['maya', 'rook', 'sable']}
      onAdd={noop}
      onRemove={onRemove}
      characterNames={names}
    />
  );

  const chip = screen.getByRole('button', { name: 'Remove all 3 members of Harbor Guild' });
  expect(chip).toHaveAttribute('aria-pressed', 'true');

  await user.click(chip);

  expect(onRemove).toHaveBeenCalledWith(['maya', 'rook', 'sable']);
});

it('completes a partly selected roster and reports the count', async () => {
  const user = userEvent.setup();
  const onAdd = vi.fn();

  render(
    <RosterPicker
      rosters={[harbor]}
      selectedCharacterIds={['maya']}
      onAdd={onAdd}
      onRemove={noop}
      characterNames={names}
    />
  );

  expect(screen.getByText('1/3')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Add 2 members of Harbor Guild' }));

  expect(onAdd).toHaveBeenCalledWith(['rook', 'sable']);
});

it('adds only the members that fit under the limit', async () => {
  const user = userEvent.setup();
  const onAdd = vi.fn();

  render(
    <RosterPicker
      rosters={[harbor]}
      selectedCharacterIds={['nia']}
      onAdd={onAdd}
      onRemove={noop}
      characterNames={names}
      maxSelectable={2}
    />
  );

  await user.click(screen.getByRole('button', { name: 'Add 3 members of Harbor Guild' }));

  expect(onAdd).toHaveBeenCalledWith(['maya']);
});

it('disables an empty roster and renders nothing without rosters', async () => {
  const { rerender } = render(
    <RosterPicker
      rosters={[{ ...harbor, characterIds: [], characterCount: 0 }]}
      selectedCharacterIds={[]}
      onAdd={noop}
      onRemove={noop}
    />
  );

  expect(screen.getByRole('button', { name: 'Harbor Guild has no characters' })).toBeDisabled();

  rerender(
    <RosterPicker rosters={[]} selectedCharacterIds={[]} onAdd={noop} onRemove={noop} />
  );

  expect(screen.queryByText('Start from a saved roster')).not.toBeInTheDocument();
});
