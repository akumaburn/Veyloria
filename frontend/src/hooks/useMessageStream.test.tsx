import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';

const subscription = vi.hoisted(() => ({ onData: undefined as undefined | ((data: unknown) => void) }));
vi.mock('@/lib/websocket-client', () => ({
  useWebSocketSubscription: (_query: string, _variables: unknown, _skip: boolean, onData?: (data: unknown) => void) => {
    subscription.onData = onData;
    return { data: null, loading: false, error: null };
  },
}));
vi.mock('@/stores/authStore', () => ({ useAuthStore: () => ({}) }));

import { useMessageStream } from './useGraphQL';

const frame = (fields: Record<string, unknown>) => ({ messageStream: { conversationId: 'c1', ...fields } });

beforeEach(() => { subscription.onData = undefined; });

it('hands every frame to onFrame in order, even when they arrive before a render', () => {
  const onFrame = vi.fn();
  const { result } = renderHook(() => useMessageStream('c1', onFrame));
  act(() => {
    subscription.onData?.(frame({ id: 'opening', streamingContent: 'Rain falls.', isComplete: true, messageType: 'NARRATION' }));
    subscription.onData?.(frame({ id: 'turn', content: 'Hold', isComplete: false }));
    subscription.onData?.(frame({ id: 'turn', content: ' still', isComplete: false }));
  });
  expect(onFrame.mock.calls.map(([m]) => [m.id, m.streamingContent, m.isComplete])).toEqual([
    ['opening', 'Rain falls.', true],
    ['turn', 'Hold', false],
    ['turn', 'Hold still', false],
  ]);
  expect(result.current.message).toMatchObject({ id: 'turn', streamingContent: 'Hold still' });
});

it('ignores frames from another conversation and stale generating frames after completion', () => {
  const onFrame = vi.fn();
  renderHook(() => useMessageStream('c1', onFrame));
  act(() => {
    subscription.onData?.({ messageStream: { conversationId: 'other', id: 'x', content: 'leak', isComplete: false } });
    subscription.onData?.(frame({ id: 'turn', streamingContent: 'Done.', isComplete: true }));
    subscription.onData?.(frame({ id: 'turn', content: ' late', isComplete: false }));
  });
  expect(onFrame).toHaveBeenCalledTimes(1);
  expect(onFrame.mock.calls[0][0]).toMatchObject({ id: 'turn', streamingContent: 'Done.', isComplete: true });
});
