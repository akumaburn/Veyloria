import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { MockedProvider } from '@apollo/client/testing';
import { ReactNode } from 'react';
import {
  useLogin,
  useConversations,
  useTypingIndicator,
  usePagination,
  useDebounce,
} from './useGraphQL';
import { LOGIN_MUTATION, CONVERSATIONS_QUERY } from '@/graphql/operations';

// Mock auth store
vi.mock('@/stores/authStore', () => ({
  useAuthStore: () => ({
    setAuth: vi.fn(),
    logout: vi.fn(),
  }),
}));

describe('useLogin Hook', () => {
  const mockLoginResponse = {
    data: {
      login: {
        __typename: 'AuthPayload',
        user: {
          __typename: 'User',
          id: '1',
          username: 'testuser',
          email: 'test@example.com',
          role: 'user',
          createdAt: '2024-01-01',
          preferences: {
            __typename: 'UserPreferences',
            theme: 'dark',
            language: 'en',
            fontSize: 'medium',
            soundEnabled: true,
            notificationsEnabled: true,
          },
        },
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
      },
    },
  };

  const mocks = [
    {
      request: {
        query: LOGIN_MUTATION,
        variables: {
          username: 'testuser',
          password: 'password123',
        },
      },
      result: mockLoginResponse,
    },
  ];

  const wrapper = ({ children }: { children: ReactNode }) => (
    <MockedProvider mocks={mocks}>
      {children}
    </MockedProvider>
  );

  it('successfully logs in user', async () => {
    const { result } = renderHook(() => useLogin(), { wrapper });

    expect(result.current.loading).toBe(false);
    
    result.current.login('testuser', 'password123');

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });
  });

  it('handles login error', async () => {
    const errorMocks = [
      {
        request: {
          query: LOGIN_MUTATION,
          variables: {
            username: 'testuser',
            password: 'wrong',
          },
        },
        error: new Error('Invalid credentials'),
      },
    ];

    const errorWrapper = ({ children }: { children: ReactNode }) => (
      <MockedProvider mocks={errorMocks}>
        {children}
      </MockedProvider>
    );

    const { result } = renderHook(() => useLogin(), { wrapper: errorWrapper });

    await result.current.login('testuser', 'wrong').catch(() => {});

    await waitFor(() => {
      expect(result.current.error).toBeDefined();
    });
  });
});

describe('useConversations Hook', () => {
  const mockConversationsResponse = {
    data: {
      conversations: {
        __typename: 'ConversationConnection',
        items: [
          {
            __typename: 'Conversation',
            id: '1',
            title: 'Test Conversation',
            description: 'Test description',
            createdAt: '2024-01-01',
            updatedAt: '2024-01-01',
            messageCount: 5,
            isPinned: false,
            isArchived: false,
            tags: [],
            owner: { __typename: 'User', id: '1', username: 'testuser' },
          },
        ],
        totalCount: 1,
        hasMore: false,
      },
    },
  };

  const mocks = [
    {
      request: {
        query: CONVERSATIONS_QUERY,
        variables: {
          filter: undefined,
          limit: 20,
          offset: 0,
        },
      },
      result: mockConversationsResponse,
    },
  ];

  const wrapper = ({ children }: { children: ReactNode }) => (
    <MockedProvider mocks={mocks}>
      {children}
    </MockedProvider>
  );

  it('fetches conversations successfully', async () => {
    const { result } = renderHook(() => useConversations(), { wrapper });

    expect(result.current.loading).toBe(true);

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
      expect(result.current.conversations).toHaveLength(1);
      expect(result.current.conversations[0].title).toBe('Test Conversation');
      expect(result.current.totalCount).toBe(1);
      expect(result.current.hasMore).toBe(false);
    });
  });

  it('handles pagination', async () => {
    const paginationMocks = [
      {
        request: {
          query: CONVERSATIONS_QUERY,
          variables: { filter: undefined, limit: 10, offset: 0 },
        },
        result: {
          data: {
            conversations: {
              items: Array(10).fill(null).map((_, i) => ({
                __typename: 'Conversation',
                id: `${i}`,
                title: `Conversation ${i}`,
                createdAt: '2024-01-01',
                updatedAt: '2024-01-01',
                messageCount: 0,
                isPinned: false,
                isArchived: false,
                tags: [],
                owner: { __typename: 'User', id: '1', username: 'user' },
              })),
              totalCount: 20,
              hasMore: true,
            },
          },
        },
      },
    ];

    const paginationWrapper = ({ children }: { children: ReactNode }) => (
      <MockedProvider mocks={paginationMocks}>
        {children}
      </MockedProvider>
    );

    const { result } = renderHook(
      () => useConversations(undefined, 10, 0),
      { wrapper: paginationWrapper }
    );

    await waitFor(() => {
      expect(result.current.conversations).toHaveLength(10);
      expect(result.current.hasMore).toBe(true);
      expect(result.current.totalCount).toBe(20);
    });
  });
});

describe('useTypingIndicator Hook', () => {
  it('initializes with no typing users', () => {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <MockedProvider mocks={[]}>
        {children}
      </MockedProvider>
    );

    const { result } = renderHook(
      () => useTypingIndicator('conversation-1'),
      { wrapper }
    );

    expect(result.current.typingUsers).toHaveLength(0);
    expect(result.current.isAnyoneTyping).toBe(false);
  });
});

describe('usePagination Hook', () => {
  it('initializes with default values', () => {
    const { result } = renderHook(() => usePagination(20));

    expect(result.current.limit).toBe(20);
    expect(result.current.offset).toBe(0);
    expect(result.current.hasMore).toBe(true);
  });

  it('increments offset on nextPage', () => {
    const { result } = renderHook(() => usePagination(20));

    act(() => {
      result.current.nextPage();
    });
    expect(result.current.offset).toBe(20);

    act(() => {
      result.current.nextPage();
    });
    expect(result.current.offset).toBe(40);
  });

  it('decrements offset on previousPage', () => {
    const { result } = renderHook(() => usePagination(20));

    // First go to page 2
    act(() => {
      result.current.nextPage();
      result.current.nextPage();
    });
    expect(result.current.offset).toBe(40);

    // Then go back
    act(() => {
      result.current.previousPage();
    });
    expect(result.current.offset).toBe(20);
  });

  it('does not go below 0 offset', () => {
    const { result } = renderHook(() => usePagination(20));

    act(() => {
      result.current.previousPage();
    });
    expect(result.current.offset).toBe(0);
  });

  it('resets pagination', () => {
    const { result } = renderHook(() => usePagination(20));

    act(() => {
      result.current.nextPage();
      result.current.nextPage();
      result.current.setHasMore(false);
    });

    act(() => {
      result.current.reset();
    });

    expect(result.current.offset).toBe(0);
    expect(result.current.hasMore).toBe(true);
  });
});

describe('useDebounce Hook', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('debounces value changes', () => {
    const { result, rerender } = renderHook(
      ({ value, delay }) => useDebounce(value, delay),
      {
        initialProps: { value: 'initial', delay: 500 },
      }
    );

    expect(result.current).toBe('initial');

    // Change value
    rerender({ value: 'updated', delay: 500 });

    // Value should not change immediately
    expect(result.current).toBe('initial');

    // Fast-forward time
    act(() => {
      vi.advanceTimersByTime(500);
    });

    // Now value should be updated
    expect(result.current).toBe('updated');
  });

  it('cancels previous debounce on new value', () => {
    const { result, rerender } = renderHook(
      ({ value, delay }) => useDebounce(value, delay),
      {
        initialProps: { value: 'initial', delay: 500 },
      }
    );

    // Change value multiple times
    rerender({ value: 'first', delay: 500 });
    act(() => {
      vi.advanceTimersByTime(200);
    });

    rerender({ value: 'second', delay: 500 });
    act(() => {
      vi.advanceTimersByTime(200);
    });

    rerender({ value: 'final', delay: 500 });

    // Complete the debounce
    act(() => {
      vi.advanceTimersByTime(500);
    });

    // Should only have the final value
    expect(result.current).toBe('final');
  });

  it('uses custom delay', () => {
    const { result, rerender } = renderHook(
      ({ value, delay }) => useDebounce(value, delay),
      {
        initialProps: { value: 'initial', delay: 1000 },
      }
    );

    rerender({ value: 'updated', delay: 1000 });

    // After 500ms, value should still be initial
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(result.current).toBe('initial');

    // After 1000ms total, value should be updated
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(result.current).toBe('updated');
  });
});