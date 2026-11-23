import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { Sidebar } from '@/components/layout/Sidebar';

const mocks = vi.hoisted(() => ({ remove: vi.fn(), stop: vi.fn() }));
vi.mock('@/stores/conversationStore', () => ({
  useConversationStore: () => ({
    conversations: ['current', 'other'].map((id) => ({
      id,
      title: id,
      updatedAt: new Date().toISOString(),
      messageCount: 0,
    })),
    isLoading: false,
    deleteConversation: mocks.remove,
    fetchConversations: vi.fn(),
    setSelectedConversation: vi.fn(),
  }),
}));
vi.mock('@/stores/ttsStore', () => ({ useTTSStore: () => ({ stopAll: mocks.stop }) }));
vi.mock('@/components/chat/MessageSearchModal', () => ({ MessageSearchModal: () => null }));
vi.mock('react-hot-toast', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function View() {
  const location = useLocation();
  return (
    <>
      <output data-testid="route">{location.pathname}</output>
      {location.pathname === '/chat/current' && (
        <div data-testid="active-chat">Current conversation queries</div>
      )}
    </>
  );
}
function renderView() {
  render(
    <MemoryRouter initialEntries={['/chat/current']}>
      <Sidebar isOpen={false} onClose={vi.fn()} />
      <View />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
    matches: true,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
  mocks.remove.mockResolvedValue(undefined);
});

it('leaves the active conversation before deleting it or evicting its cache', async () => {
  const user = userEvent.setup();
  mocks.remove.mockImplementation(async () => {
    expect(screen.queryByTestId('active-chat')).not.toBeInTheDocument();
    expect(screen.getByTestId('route')).toHaveTextContent('/chat');
  });
  renderView();
  await user.click(screen.getByRole('button', { name: 'Delete current' }));
  await user.click(screen.getByRole('button', { name: 'Confirm deletion of current' }));
  expect(mocks.remove).toHaveBeenCalledWith('current');
  expect(mocks.stop).toHaveBeenCalledOnce();
  expect(screen.queryByTestId('active-chat')).not.toBeInTheDocument();
});

it('restores the active route when deletion fails', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const user = userEvent.setup();
  mocks.remove.mockRejectedValue(new Error('Request failed'));
  renderView();
  await user.click(screen.getByRole('button', { name: 'Delete current' }));
  await user.click(screen.getByRole('button', { name: 'Confirm deletion of current' }));
  await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('/chat/current'));
  expect(screen.getByTestId('active-chat')).toBeInTheDocument();
});

it('keeps the current view when deleting another conversation', async () => {
  const user = userEvent.setup();
  renderView();
  await user.click(screen.getByRole('button', { name: 'Delete other' }));
  await user.click(screen.getByRole('button', { name: 'Confirm deletion of other' }));
  expect(mocks.remove).toHaveBeenCalledWith('other');
  expect(screen.getByTestId('route')).toHaveTextContent('/chat/current');
  expect(mocks.stop).not.toHaveBeenCalled();
});
