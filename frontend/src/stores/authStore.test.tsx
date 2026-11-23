import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { enableMapSet } from 'immer';

// logout() now resets the in-memory user-data stores (messageStore, etc.),
// which use Immer Maps — the MapSet plugin must be loaded before those resets.
enableMapSet();

// Mock dependencies before importing the store
// vi.hoisted ensures these are available before vi.mock factories run
const {
  mockSetTokens,
  mockClearTokens,
  mockMutate,
  mockClearStore,
  mockResetStore,
  mockQuery,
  mockResetApolloCache,
} = vi.hoisted(() => ({
  mockSetTokens: vi.fn(),
  mockClearTokens: vi.fn(),
  mockMutate: vi.fn().mockResolvedValue({}),
  mockClearStore: vi.fn().mockResolvedValue(undefined),
  mockResetStore: vi.fn().mockResolvedValue(undefined),
  mockQuery: vi.fn().mockResolvedValue({ data: { me: null } }),
  mockResetApolloCache: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@apollo/client', () => ({
  gql: vi.fn((strings: TemplateStringsArray) => strings[0]),
  useMutation: vi.fn(() => [vi.fn(), { loading: false, error: null }]),
  useQuery: vi.fn(() => ({ data: null, loading: false, error: null })),
}));

vi.mock('@/lib/apollo-client', () => ({
  apolloClient: {
    mutate: mockMutate,
    clearStore: mockClearStore,
    resetStore: mockResetStore,
    query: mockQuery,
  },
  setTokens: mockSetTokens,
  clearTokens: mockClearTokens,
  setAuthLogoutCallback: vi.fn(),
  resetApolloCache: mockResetApolloCache,
  setTokensSyncCallback: vi.fn(),
}));

vi.mock('@/lib/websocket-client', () => ({
  disconnectWebSocket: vi.fn(),
  setWebSocketAuthErrorCallback: vi.fn(),
  clearWebSocketAuthErrorCallback: vi.fn(),
  useWebSocketSubscription: vi.fn(() => ({ data: null, loading: false, error: null })),
}));

vi.mock('@/lib/storageHelper', () => ({
  removeStoredItem: vi.fn(),
}));

import { useAuthStore } from './authStore';

describe('AuthStore', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset store before each test
    const { result } = renderHook(() => useAuthStore());
    act(() => {
      result.current.setUser(null);
      result.current.setTokens(null, null);
    });
    vi.clearAllMocks();
  });

  it('initializes with default state', () => {
    const { result } = renderHook(() => useAuthStore());

    expect(result.current.user).toBeNull();
    expect(result.current.accessToken).toBeNull();
    expect(result.current.refreshToken).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('sets auth data correctly', () => {
    const { result } = renderHook(() => useAuthStore());

    const mockUser = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      displayName: 'Test User',
      role: 'user' as const,
      createdAt: '2024-01-01',
      lastLogin: '2024-01-01',
      emailVerified: true,
    };

    act(() => {
      result.current.setUser(mockUser);
      result.current.setTokens('access-token', 'refresh-token');
    });

    expect(result.current.user).toEqual(mockUser);
    expect(result.current.accessToken).toBe('access-token');
    expect(result.current.refreshToken).toBe('refresh-token');
    expect(result.current.isAuthenticated).toBe(true);
  });

  it('clears auth data on logout', async () => {
    const { result } = renderHook(() => useAuthStore());

    // Set auth first
    act(() => {
      result.current.setUser({
        id: '1',
        username: 'test',
        email: 'test@example.com',
        displayName: 'Test',
        role: 'user' as const,
        createdAt: '2024-01-01',
        lastLogin: '2024-01-01',
        emailVerified: true,
      });
      result.current.setTokens('token', 'refresh');
    });

    expect(result.current.isAuthenticated).toBe(true);

    // Then logout
    await act(async () => {
      await result.current.logout();
    });

    expect(result.current.user).toBeNull();
    expect(result.current.accessToken).toBeNull();
    expect(result.current.refreshToken).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('updates access token', () => {
    const { result } = renderHook(() => useAuthStore());

    act(() => {
      result.current.setTokens('new-access-token', null);
    });

    expect(result.current.accessToken).toBe('new-access-token');
  });

  it('updates user data', () => {
    const { result } = renderHook(() => useAuthStore());

    const initialUser = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      displayName: 'Test User',
      role: 'user' as const,
      createdAt: '2024-01-01',
      lastLogin: '2024-01-01',
      emailVerified: true,
    };

    act(() => {
      result.current.setUser(initialUser);
      result.current.setTokens('token', 'refresh');
    });

    const updates = {
      username: 'newusername',
      email: 'newemail@example.com',
    };

    act(() => {
      result.current.setUser({ ...result.current.user!, ...updates });
    });

    expect(result.current.user?.username).toBe('newusername');
    expect(result.current.user?.email).toBe('newemail@example.com');
    expect(result.current.user?.id).toBe('1'); // Unchanged
  });

  it('persists tokens via apollo-client setTokens', () => {
    const { result } = renderHook(() => useAuthStore());

    const mockUser = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      displayName: 'Test User',
      role: 'user' as const,
      createdAt: '2024-01-01',
      lastLogin: '2024-01-01',
      emailVerified: true,
    };

    act(() => {
      result.current.setUser(mockUser);
      result.current.setTokens('access-token', 'refresh-token');
    });

    // Verify tokens were passed to apollo-client for storage
    expect(mockSetTokens).toHaveBeenCalledWith('access-token', 'refresh-token');
  });

  it('clears tokens on logout', async () => {
    const { result } = renderHook(() => useAuthStore());

    act(() => {
      result.current.setUser({
        id: '1',
        username: 'test',
        email: 'test@example.com',
        displayName: 'Test',
        role: 'user' as const,
        createdAt: '2024-01-01',
        lastLogin: '2024-01-01',
        emailVerified: true,
      });
      result.current.setTokens('token', 'refresh');
    });

    await act(async () => {
      await result.current.logout();
    });

    // Verify tokens were cleared via apollo-client
    expect(mockClearTokens).toHaveBeenCalled();
  });

  it('checks if user has admin role', () => {
    const { result } = renderHook(() => useAuthStore());

    // Test with regular user
    act(() => {
      result.current.setUser({
        id: '1',
        username: 'user',
        email: 'user@example.com',
        displayName: 'User',
        role: 'user' as const,
        createdAt: '2024-01-01',
        lastLogin: '2024-01-01',
        emailVerified: true,
      });
      result.current.setTokens('token', 'refresh');
    });

    expect((result.current.user?.role === 'admin')).toBe(false);

    // Test with admin user
    act(() => {
      result.current.setUser({ ...result.current.user!, role: 'admin' });
    });

    expect((result.current.user?.role === 'admin')).toBe(true);
  });
});
