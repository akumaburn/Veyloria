import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { TTSControls, TTSToggle } from './TTSControls';

const state = vi.hoisted(() => ({
  settings: { enabled: true }, playbackStates: new Map(),
  play: vi.fn(), pause: vi.fn(), resume: vi.fn(), restart: vi.fn(), setEnabled: vi.fn(),
}));
vi.mock('@/stores/ttsStore', () => ({ useTTSStore: () => state }));
beforeEach(() => { state.playbackStates.clear(); state.play.mockResolvedValue(undefined); });

it('centers compact controls and keeps restart available after completion', async () => {
  state.playbackStates.set('reply', { progress: 100 });
  render(<TTSControls compact messageId="reply" content="Hello." />);
  const restart = screen.getByRole('button', { name: 'Restart voice playback' });
  expect(restart).toHaveClass('inline-flex', 'items-center', 'justify-center', 'p-1.5');
  expect(restart).not.toHaveClass('h-11', 'w-11');
  expect(screen.getByRole('button', { name: 'Play voice playback' })).toHaveClass('p-1.5');
  await userEvent.click(restart);
  expect(state.restart).toHaveBeenCalledWith('reply');
});

it('shows a recoverable playback failure instead of only logging it', async () => {
  state.play.mockRejectedValueOnce(new Error('Voice service unavailable. Try again.'));
  render(<TTSControls compact messageId="reply" content="Hello." />);
  await userEvent.click(screen.getByRole('button', { name: 'Play voice playback' }));
  expect(screen.getByRole('alert')).toHaveTextContent('Voice service unavailable. Try again.');
  expect(screen.getByRole('button', { name: 'Play voice playback' })).toBeEnabled();
});

it('labels the global voice toggle for keyboard and screen reader users', async () => {
  render(<TTSToggle />);
  await userEvent.click(screen.getByRole('button', { name: 'Disable voice playback', pressed: true }));
  expect(state.setEnabled).toHaveBeenCalledWith(false);
});
