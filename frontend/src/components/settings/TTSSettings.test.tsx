import userEvent from '@testing-library/user-event';
import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { TTSSettings } from './TTSSettings';

const actions = vi.hoisted(() => ({ updateSettings: vi.fn() }));

vi.mock('@/stores/ttsStore', () => ({
  useTTSStore: () => ({
    settings: { enabled: true, autoPlay: true, speed: 1, serverUrl: '/tts' },
    voices: [], voicesLoaded: false, isInitialized: false,
    initError: 'TTS server returned 500',
    checkConnection: vi.fn(async () => {}), updateSettings: actions.updateSettings,
    setEnabled: vi.fn(), setAutoPlay: vi.fn(), setSpeed: vi.fn(),
    play: vi.fn(), stop: vi.fn(), playbackStates: new Map(),
  }),
}));

it('shows a connection failure without an endless voice-loading spinner', () => {
  render(<TTSSettings />);
  expect(screen.getByText('TTS Server Not Available')).toBeVisible();
  expect(screen.getByText('Connection failed')).toBeVisible();
  expect(screen.queryByText('Loading available voices...')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Reconnect' })).toBeEnabled();
});


it('lets users choose the language of the text before voice generation', async () => {
  const user = userEvent.setup();
  render(<TTSSettings />);
  HTMLElement.prototype.scrollIntoView = vi.fn();
  screen.getByRole('combobox', { name: 'Text language' }).focus();
  await user.keyboard('{ArrowDown}');
  await user.keyboard('French{Enter}');
  expect(actions.updateSettings).toHaveBeenCalledWith({ languageId: 'fr' });
});
