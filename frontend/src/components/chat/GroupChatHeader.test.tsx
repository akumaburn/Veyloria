import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import type { Character } from '@/graphql/types';
import { useCharacterCardStore } from '@/stores/characterCardStore';
import { GroupChatHeader } from './GroupChatHeader';

const elara = {
  id: '830a5b95-4620-41a9-bb55-c15dff692d55',
  name: 'Elara Vane',
  avatar: 'https://example.test/elara.png',
} as Character;

const rin = {
  id: '0900c52c-3ef6-4ba6-a2d8-8e3fdeacc868',
  name: 'Rin Neko',
  avatar: 'https://example.test/rin.png',
} as Character;

const props = {
  title: 'Dungeon Diving',
  participants: [
    { id: 'cc-1', character: elara, turnOrder: 0, responseCount: 3, isActive: true },
    { id: 'cc-2', character: rin, turnOrder: 1, responseCount: 1, isActive: true },
  ],
  currentSpeaker: null,
  groupState: 'WAITING_FOR_USER',
  turnMode: 'ROUND_ROBIN',
  onPause: vi.fn(),
  onResume: vi.fn(),
  onRequestInterjection: vi.fn(),
  onTriggerNextCharacter: vi.fn(),
  onStop: vi.fn(),
  onOpenSettings: vi.fn(),
};

beforeEach(() => {
  useCharacterCardStore.setState({ characterId: null });
});

it('opens a character card from a cast avatar in the header', async () => {
  const user = userEvent.setup();
  render(<GroupChatHeader {...props} />);

  await user.click(screen.getByRole('button', { name: /Open Elara Vane's character card/ }));

  expect(useCharacterCardStore.getState().characterId).toBe(elara.id);
});

it('opens a character card from the participants panel', async () => {
  const user = userEvent.setup();
  render(<GroupChatHeader {...props} />);

  await user.click(screen.getByRole('button', { name: 'Show participants' }));
  const rinCards = screen.getAllByRole('button', { name: /Open Rin Neko's character card/ });
  await user.click(rinCards[rinCards.length - 1]);

  expect(useCharacterCardStore.getState().characterId).toBe(rin.id);
});
