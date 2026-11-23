import { render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { MessageList } from './MessageList';

const state = vi.hoisted(() => ({
  loading: true,
  stream: null as null | {
    id: string;
    streamingContent: string;
    isComplete: boolean;
    role: string;
    createdAt: string;
  },
  messages: [],
  refetch: vi.fn(),
  pending: vi.fn(),
}));
vi.mock('@/hooks/useGraphQL', () => ({
  useMessages: () => ({ messages: state.messages, loading: state.loading, refetch: state.refetch }),
  useMessageStream: () => ({ message: state.stream }),
  useStopGeneration: () => ({ stopGeneration: vi.fn() }),
  usePendingStreamingMessage: () => ({ pendingMessage: null, refetch: state.pending }),
}));
vi.mock('@/stores/messageStore', () => ({ useMessageStore: () => ({}) }));
vi.mock('@/stores/ttsStore', () => ({
  useTTSStore: Object.assign(() => ({ settings: { enabled: false }, playbackStates: new Map(), readProgress: {}, syncConversationMessages: vi.fn(), stopAll: vi.fn() }), { getState: () => ({ readingConversationId: null }) }),
}));
vi.mock('./ReactionPicker', () => ({ ReactionPicker: () => null }));
vi.mock('./TTSControls', () => ({ TTSControls: () => null }));
vi.mock('./TTSPlayingIndicator', () => ({ TTSPlayingIndicator: () => null }));

beforeEach(() => {
  state.loading = true;
  state.stream = null;
  state.messages = [];
  state.refetch.mockResolvedValue({ data: {} });
  state.pending.mockResolvedValue({ data: {} });
  HTMLElement.prototype.scrollIntoView = vi.fn();
  sessionStorage.clear();
});

it('shows a response placeholder rather than the welcome screen or full-page loader during generation', () => {
  render(<MessageList conversationId="chat" isGenerating />);
  expect(screen.getByText('Generating response...')).toBeVisible();
  expect(screen.queryByText('Loading messages...')).not.toBeInTheDocument();
  expect(screen.queryByText('AI Chat')).not.toBeInTheDocument();
});

it('renders the final answer outside the collapsed thinking block as streaming completes', () => {
  state.stream = {
    id: 'reply',
    streamingContent: '<think>Plan first.',
    isComplete: false,
    role: 'ASSISTANT',
    createdAt: new Date().toISOString(),
  };
  const view = render(<MessageList conversationId="chat" isGenerating />);
  expect(screen.getByText('Processing thoughts...')).toBeVisible();
  state.stream = {
    ...state.stream,
    streamingContent: '<think>Plan first.</think>Here is the complete answer.',
    isComplete: true,
  };
  state.loading = false;
  view.rerender(<MessageList conversationId="chat" isGenerating={false} />);
  expect(screen.getByText('Here is the complete answer.')).toBeVisible();
  expect(screen.queryByText('Processing thoughts...')).not.toBeInTheDocument();
  expect(screen.queryByText('Plan first.')).not.toBeInTheDocument();
});
