import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { enableMapSet } from 'immer';

enableMapSet();

vi.mock('@/lib/apollo-client', () => ({
  apolloClient: {
    query: vi.fn(),
    mutate: vi.fn(),
  },
}));

vi.mock('@/lib/storeHelpers', () => ({
  devtoolsInDev: vi.fn((f) => f),
  setLoading: vi.fn(),
  setError: vi.fn(),
  clearLoadingAndError: vi.fn(),
  handleStoreError: vi.fn(() => 'An error occurred'),
}));

vi.mock('@/graphql/operations', () => ({
  MESSAGES_QUERY: { kind: 'Document' },
  SEND_MESSAGE_MUTATION: { kind: 'Document' },
  EDIT_MESSAGE_MUTATION: { kind: 'Document' },
  DELETE_MESSAGE_MUTATION: { kind: 'Document' },
  REGENERATE_MESSAGE_MUTATION: { kind: 'Document' },
  STOP_GENERATION_MUTATION: { kind: 'Document' },
}));

vi.mock('js-base64', () => ({
  Base64: {
    encode: vi.fn((s: string) => btoa(s)),
    decode: vi.fn((s: string) => atob(s)),
  },
}));

import { useMessageStore } from '@/stores/messageStore';
import type { StoreMessage } from '@/graphql/types';

describe('MessageStore', () => {
  beforeEach(() => {
    const { result } = renderHook(() => useMessageStore());
    act(() => {
      result.current.reset();
    });
  });

  it('initializes with default state', () => {
    const { result } = renderHook(() => useMessageStore());

    expect(result.current.messages).toBeInstanceOf(Map);
    expect(result.current.messages.size).toBe(0);
    expect(result.current.streamingMessage).toBeNull();
    expect(result.current.isLoading).toBe(false);
    expect(result.current.isSending).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.hasMore).toBeInstanceOf(Map);
    expect(result.current.hasMore.size).toBe(0);
    expect(result.current.cursors).toBeInstanceOf(Map);
    expect(result.current.cursors.size).toBe(0);
    expect(result.current.typingUsers).toBeInstanceOf(Map);
    expect(result.current.typingUsers.size).toBe(0);
  });

  it('sets and clears streaming message', () => {
    const { result } = renderHook(() => useMessageStore());

    const streaming = {
      conversationId: 'conv-1',
      content: 'Hello',
      role: 'assistant' as const,
      isComplete: false,
    };

    act(() => {
      result.current.setStreamingMessage(streaming);
    });

    expect(result.current.streamingMessage).toEqual(streaming);

    act(() => {
      result.current.setStreamingMessage(null);
    });

    expect(result.current.streamingMessage).toBeNull();
  });

  it('appends content to streaming message', () => {
    const { result } = renderHook(() => useMessageStore());

    act(() => {
      result.current.setStreamingMessage({
        conversationId: 'conv-1',
        content: 'Hello',
        role: 'assistant',
        isComplete: false,
      });
    });

    act(() => {
      result.current.appendToStreamingMessage(' world');
    });

    expect(result.current.streamingMessage?.content).toBe('Hello world');

    act(() => {
      result.current.appendToStreamingMessage('!');
    });

    expect(result.current.streamingMessage?.content).toBe('Hello world!');
  });

  it('does not append when no streaming message exists', () => {
    const { result } = renderHook(() => useMessageStore());

    expect(result.current.streamingMessage).toBeNull();

    act(() => {
      result.current.appendToStreamingMessage('should not appear');
    });

    expect(result.current.streamingMessage).toBeNull();
  });

  it('finalizes streaming message and adds to messages map', () => {
    const { result } = renderHook(() => useMessageStore());

    act(() => {
      result.current.setStreamingMessage({
        conversationId: 'conv-1',
        content: 'Hello',
        role: 'assistant',
        isComplete: false,
      });
    });

    const finalizedMessage: StoreMessage = {
      id: 'msg-1',
      conversationId: 'conv-1',
      content: 'Hello',
      role: 'assistant',
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0,
      isEdited: false,
      isDeleted: false,
      status: 'sent',
    };

    act(() => {
      result.current.finalizeStreamingMessage(finalizedMessage);
    });

    expect(result.current.streamingMessage).toBeNull();

    const messages = result.current.messages.get('conv-1');
    expect(messages).toBeDefined();
    expect(messages).toHaveLength(1);
    expect(messages![0].id).toBe('msg-1');
    expect(messages![0].content).toBe('Hello');
    expect(messages![0].status).toBe('sent');
  });

  it('prevents duplicate messages on finalize by updating existing', () => {
    const { result } = renderHook(() => useMessageStore());

    const message: StoreMessage = {
      id: 'msg-1',
      conversationId: 'conv-1',
      content: 'Hello',
      role: 'assistant',
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0,
      isEdited: false,
      isDeleted: false,
      status: 'sent',
    };

    act(() => {
      result.current.setStreamingMessage({
        conversationId: 'conv-1',
        content: 'Hello',
        role: 'assistant',
        isComplete: false,
      });
      result.current.finalizeStreamingMessage(message);
    });

    act(() => {
      result.current.setStreamingMessage({
        conversationId: 'conv-1',
        content: 'Hello updated',
        role: 'assistant',
        isComplete: false,
      });
      result.current.finalizeStreamingMessage({
        ...message,
        content: 'Hello updated',
      });
    });

    const messages = result.current.messages.get('conv-1');
    expect(messages).toHaveLength(1);
    expect(messages![0].content).toBe('Hello updated');
  });

  it('clears messages and associated state for a conversation', () => {
    const { result } = renderHook(() => useMessageStore());

    const msg1: StoreMessage = {
      id: 'msg-1', conversationId: 'conv-1', content: 'A', role: 'user',
      createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0, isEdited: false, isDeleted: false,
    };
    const msg2: StoreMessage = {
      id: 'msg-2', conversationId: 'conv-1', content: 'B', role: 'assistant',
      createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0, isEdited: false, isDeleted: false,
    };
    const msg3: StoreMessage = {
      id: 'msg-3', conversationId: 'conv-2', content: 'C', role: 'user',
      createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0, isEdited: false, isDeleted: false,
    };

    act(() => {
      result.current.setStreamingMessage({
        conversationId: msg1.conversationId, content: msg1.content, role: 'assistant', isComplete: false,
      });
      result.current.finalizeStreamingMessage(msg1);
      result.current.setStreamingMessage({
        conversationId: msg2.conversationId, content: msg2.content, role: 'assistant', isComplete: false,
      });
      result.current.finalizeStreamingMessage(msg2);
      result.current.setStreamingMessage({
        conversationId: msg3.conversationId, content: msg3.content, role: 'assistant', isComplete: false,
      });
      result.current.finalizeStreamingMessage(msg3);
      result.current.setTypingUsers('conv-1', ['user-1']);
      result.current.setTypingUsers('conv-2', ['user-2']);
    });

    expect(result.current.messages.get('conv-1')).toHaveLength(2);
    expect(result.current.messages.get('conv-2')).toHaveLength(1);

    act(() => {
      result.current.clearMessages('conv-1');
    });

    expect(result.current.messages.get('conv-1')).toBeUndefined();
    expect(result.current.messages.get('conv-2')).toHaveLength(1);
    expect(result.current.typingUsers.has('conv-1')).toBe(false);
    expect(result.current.typingUsers.get('conv-2')).toEqual(['user-2']);
  });

  it('sets and clears typing users', () => {
    const { result } = renderHook(() => useMessageStore());

    act(() => {
      result.current.setTypingUsers('conv-1', ['user-1', 'user-2']);
    });

    expect(result.current.typingUsers.get('conv-1')).toEqual(['user-1', 'user-2']);

    act(() => {
      result.current.setTypingUsers('conv-1', []);
    });

    expect(result.current.typingUsers.has('conv-1')).toBe(false);

    act(() => {
      result.current.setTypingUsers('conv-2', ['user-3']);
    });

    expect(result.current.typingUsers.get('conv-2')).toEqual(['user-3']);
  });

  it('resets all state to initial values', () => {
    const { result } = renderHook(() => useMessageStore());

    const msg: StoreMessage = {
      id: 'msg-1', conversationId: 'conv-1', content: 'X', role: 'user',
      createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0, isEdited: false, isDeleted: false,
    };

    act(() => {
      result.current.setStreamingMessage({
        conversationId: 'conv-1', content: 'X', role: 'assistant', isComplete: false,
      });
      result.current.finalizeStreamingMessage(msg);
      result.current.setTypingUsers('conv-1', ['user-1']);
    });

    expect(result.current.messages.size).toBeGreaterThan(0);
    expect(result.current.streamingMessage).toBeNull();
    expect(result.current.typingUsers.size).toBeGreaterThan(0);

    act(() => {
      result.current.reset();
    });

    expect(result.current.messages).toBeInstanceOf(Map);
    expect(result.current.messages.size).toBe(0);
    expect(result.current.streamingMessage).toBeNull();
    expect(result.current.isLoading).toBe(false);
    expect(result.current.isSending).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.hasMore.size).toBe(0);
    expect(result.current.cursors.size).toBe(0);
    expect(result.current.typingUsers.size).toBe(0);
  });

  it('validates StoreMessage structure extends Message with status and error', () => {
    const sendingMsg: StoreMessage = {
      id: 'msg-1',
      conversationId: 'conv-1',
      content: 'test',
      role: 'user',
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
      editCount: 0,
      isEdited: false,
      isDeleted: false,
      status: 'sending',
    };

    expect(sendingMsg.id).toBe('msg-1');
    expect(sendingMsg.conversationId).toBe('conv-1');
    expect(sendingMsg.status).toBe('sending');

    const errorMsg: StoreMessage = {
      ...sendingMsg,
      status: 'error',
      error: 'Something went wrong',
    };

    expect(errorMsg.status).toBe('error');
    expect(errorMsg.error).toBe('Something went wrong');

    const statuses: Array<'sending' | 'sent' | 'error' | 'generating'> = [
      'sending', 'sent', 'error', 'generating',
    ];

    for (const status of statuses) {
      const m: StoreMessage = { ...sendingMsg, status };
      expect(m.status).toBe(status);
    }
  });
});
