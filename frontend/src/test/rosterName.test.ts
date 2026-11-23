import { expect, it } from 'vitest';
import { suggestedGroupName } from '@/components/characters/CharacterRosterModal';

it('keeps a short setting sentence as the roster name', () => {
  expect(
    suggestedGroupName('A harbor city rebuilding after a siege.', ['Maya Chen'])
  ).toBe('A harbor city rebuilding after a siege');
});

it('names a long setting after its cast instead of a truncated sentence', () => {
  const setting =
    'Decades ago, hundreds of portals appeared across the planet, connecting human worlds.';
  const members = ['Sylvanis Moonwhisper', 'Kaelen Drift', 'Mira Sol'];

  expect(suggestedGroupName(setting, members)).toBe('Sylvanis Moonwhisper and 2 others');
});

it('uses the member name for a one-member roster', () => {
  expect(suggestedGroupName('A very long setting that will not fit in a name.', ['Sable Quinn']))
    .toBe('Sable Quinn');
});
