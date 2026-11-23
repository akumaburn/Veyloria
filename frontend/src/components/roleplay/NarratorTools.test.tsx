import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { useNarratorModeStore } from '@/stores/narratorModeStore';
import SceneIndicator from './SceneIndicator';
import DiceRoller from './DiceRoller';

const state = vi.hoisted(() => ({ roll: vi.fn(), changeScene: vi.fn(), refetch: vi.fn() }));
vi.mock('@/hooks/useRoleplay', () => ({
  useActiveScene: () => ({ scene: { name: 'Inn', location: 'Market', characters: [] }, refetch: state.refetch }),
  useSceneChanged: () => ({}),
  useChangeScene: () => ({ changeScene: state.changeScene }),
  useDiceRolled: () => ({}),
  useDiceRoller: () => ({ rolls: [], roll: state.roll, refetch: state.refetch }),
}));
beforeEach(() => { useNarratorModeStore.setState({ enabled: {} }); });

it('shows the current scene to players but closes manual scene changes when narrator mode ends', () => {
  render(<SceneIndicator conversationId="story" />);
  expect(screen.getByText('Inn')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Change Scene' })).not.toBeInTheDocument();
  act(() => useNarratorModeStore.getState().setEnabled('story', true));
  fireEvent.click(screen.getByRole('button', { name: 'Change Scene' }));
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  act(() => useNarratorModeStore.getState().setEnabled('story', false));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByText('Inn')).toBeInTheDocument();
});

it('keeps public player rolls available and clears the effective hidden roll when leaving narrator mode', () => {
  const props = { conversationId: 'story', characters: [{ id: 'npc', name: 'Mira' }] };
  const { rerender } = render(<DiceRoller {...props} narratorMode />);
  fireEvent.click(screen.getByRole('checkbox', { name: /Hidden Roll/ }));
  rerender(<DiceRoller {...props} narratorMode={false} />);
  expect(screen.queryByRole('checkbox', { name: /Hidden Roll/ })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Rolling as')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Roll' }));
  expect(state.roll).toHaveBeenCalledWith('1d20', undefined, undefined, undefined);
});
