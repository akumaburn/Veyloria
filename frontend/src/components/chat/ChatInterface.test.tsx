import { forwardRef } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  group: false,
  send: vi.fn(),
  groupSend: vi.fn(),
  stopAll: vi.fn(),
  toastError: vi.fn(),
}));
vi.mock('@apollo/client', async importOriginal => ({
  ...await importOriginal<typeof import('@apollo/client')>(),
  useQuery: () => ({ data: { conversation: { id: 'chat', isGroupChat: mocks.group,
    groupState: 'WAITING_FOR_USER', characters: [] } }, refetch: vi.fn() }),
  useMutation: () => [vi.fn(), { loading: false }],
}));
vi.mock('@/hooks/useGraphQL', () => ({
  useSendMessage: () => ({ sendMessage: mocks.send }),
  useSendGroupMessage: () => ({ sendGroupMessage: mocks.groupSend }),
  useSendTypingIndicator: () => ({ sendTypingIndicator: vi.fn().mockResolvedValue({}) }),
  useSwitchCharacter: () => ({ switchCharacter: vi.fn() }),
  useStopGeneration: () => ({ stopGeneration: vi.fn() }),
  useConversationParticipants: () => ({ participants: [], loading: false }),
}));
vi.mock('@/hooks/useRoleplay', () => ({ useAchievementUnlocked: () => ({ achievement: null }) }));
vi.mock('@/lib/websocket-client', () => ({ useWebSocketSubscription: () => ({ data: null }) }));
vi.mock('@/stores/conversationStore', () => ({ useConversationStore: () => ({ createConversation: vi.fn() }) }));
vi.mock('@/stores/authStore', () => ({ useAuthStore: Object.assign(() => ({ user: { id: 'me' } }), {
  getState: () => ({ accessToken: null }),
}) }));
vi.mock('@/stores/ttsStore', () => ({ useTTSStore: () => ({ stopAll: mocks.stopAll, settings: {} }) }));
vi.mock('react-hot-toast', () => ({ toast: { error: mocks.toastError } }));
vi.mock('./MessageList', () => ({ MessageList: () => null }));
vi.mock('./GroupChatHeader', () => ({ GroupChatHeader: () => null }));
vi.mock('./ParticipantList', () => ({ ParticipantList: () => null }));
vi.mock('./TypingIndicator', () => ({ TypingIndicator: () => null }));
vi.mock('./InviteNotifications', () => ({ InviteNotificationBadge: () => null }));
vi.mock('./ChatInput', () => ({
  ChatInput: forwardRef<HTMLTextAreaElement, any>((props, ref) => <>
    <textarea ref={ref} aria-label="message" value={props.value} onChange={e => props.onChange(e.target.value)} />
    <button onClick={props.onSend}>Send</button>
  </>),
}));

import { ChatInterface } from './ChatInterface';

afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

it.each([false, true])('does not replay a failed send automatically (group=%s)', async group => {
  vi.useFakeTimers();
  mocks.group = group;
  mocks.send.mockRejectedValue(new Error('Response lost after commit'));
  mocks.groupSend.mockRejectedValue(new Error('Response lost after commit'));
  render(<MemoryRouter initialEntries={['/chat/chat']}><Routes>
    <Route path="/chat/:conversationId" element={<ChatInterface />} />
  </Routes></MemoryRouter>);
  fireEvent.change(screen.getByLabelText('message'), { target: { value: 'Send exactly once' } });
  fireEvent.click(screen.getByText('Send'));
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(group ? mocks.groupSend : mocks.send).toHaveBeenCalledTimes(1);
  expect(group ? mocks.send : mocks.groupSend).not.toHaveBeenCalled();
  expect(mocks.toastError).toHaveBeenCalled();
  expect(screen.getByLabelText('message')).toHaveValue('Send exactly once');
});

it('suppresses a second submission while the first send is still pending', async () => {
  mocks.group = false;
  let finish!: (value: unknown) => void;
  mocks.send.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  render(<MemoryRouter initialEntries={['/chat/chat']}><Routes>
    <Route path="/chat/:conversationId" element={<ChatInterface />} />
  </Routes></MemoryRouter>);
  fireEvent.change(screen.getByLabelText('message'), { target: { value: 'First message' } });
  fireEvent.click(screen.getByText('Send'));
  fireEvent.change(screen.getByLabelText('message'), { target: { value: 'Another submission' } });
  fireEvent.click(screen.getByText('Send'));
  expect(mocks.send).toHaveBeenCalledTimes(1);
  await act(async () => finish({}));
});
