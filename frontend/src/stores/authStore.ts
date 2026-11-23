/**
 * Authentication Store
 *
 * Manages user authentication state including login, logout, and session management.
 * Uses centralized GraphQL operations from @graphql module.
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { apolloClient, setTokens, clearTokens, setAuthLogoutCallback, resetApolloCache, setTokensSyncCallback } from '@/lib/apollo-client';
import { disconnectWebSocket, reconnectWebSocket, setWebSocketAuthErrorCallback, clearWebSocketAuthErrorCallback } from '@/lib/websocket-client';
import { devtoolsInDev, setLoading, setError, clearLoadingAndError, handleStoreError } from '@/lib/storeHelpers';
import {
  LOGIN_MUTATION,
  REGISTER_MUTATION,
  LOGOUT_MUTATION,
  ME_QUERY,
} from '@/graphql/operations';
import type { User } from '@/graphql/types';
import { removeStoredItem } from '@/lib/storageHelper';
import { useMessageStore } from './messageStore';
import { useBookmarkStore } from './bookmarkStore';
import { useConversationStore } from './conversationStore';
import { useCharacterSheetStore } from './characterSheetStore';
import { useCharacterStore } from './characterStore';
import { useWorldStateStore } from './worldStateStore';
import { useCommunityStore } from './communityStore';
import { useAchievementStore } from './achievementStore';
import { useTTSStore } from './ttsStore';

/** Normalize backend role values (uppercase) to frontend convention (lowercase) */
const normalizeRole = (role: string): 'user' | 'admin' =>
  (role || 'user').toLowerCase() as 'user' | 'admin';

// Extended User type for auth store (includes optional fields from backend)
interface AuthUser extends Partial<User> {
  id: string;
  username: string;
  email?: string;
  displayName?: string;
  bio?: string;
  role: 'user' | 'admin';
  createdAt: string;
  lastLogin?: string;
  emailVerified?: boolean;
  profilePicture?: string;
}

interface AuthState {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  accessToken: string | null;
  refreshToken: string | null;

  // Actions
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshAuth: () => Promise<void>;
  setUser: (user: AuthUser | null) => void;
  setTokens: (accessToken: string | null, refreshToken: string | null) => void;
  setAuth: (user: AuthUser, accessToken: string, refreshToken: string) => void;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>()(
  devtoolsInDev(
    // NOTE: Persist middleware REMOVED to prevent stale auth state caching issues
    // Auth state should always be verified against the backend on app load
    immer((set) => ({
      user: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
      accessToken: null,
      refreshToken: null,

      login: async (username: string, password: string) => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: LOGIN_MUTATION,
              variables: { username, password },
            });

            if (!data?.login) {
              throw new Error('Login failed: empty response from server');
            }

            const { user, accessToken, refreshToken } = data.login;

            if (!accessToken || !refreshToken || !user) {
              throw new Error('Login failed: incomplete response from server');
            }

            // setTokens writes localStorage AND fires the tokens-changed
            // callback, which causes the WebSocket client (if any) to
            // forceReconnect with the new auth in connection_init. Existing
            // subscriptions are preserved and replayed on the new connection.
            setTokens(accessToken, refreshToken);

            set((state) => {
              state.user = { ...user, role: normalizeRole(user.role) };
              state.accessToken = accessToken;
              state.refreshToken = refreshToken;
              state.isAuthenticated = true;
              clearLoadingAndError(state);
            });
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Login failed'));
              state.isAuthenticated = false;
              state.user = null;
            });
            throw error;
          }
        },

        register: async (username: string, email: string, password: string) => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: REGISTER_MUTATION,
              variables: { username, email, password },
            });

            if (!data?.register) {
              throw new Error('Registration failed: empty response from server');
            }

            const { user, accessToken, refreshToken } = data.register;

            if (!accessToken || !refreshToken || !user) {
              throw new Error('Registration failed: incomplete response from server');
            }

            // setTokens fires the tokens-changed callback which forces the
            // WebSocket to reconnect with the new auth in connection_init.
            setTokens(accessToken, refreshToken);

            set((state) => {
              state.user = { ...user, role: normalizeRole(user.role) };
              state.accessToken = accessToken;
              state.refreshToken = refreshToken;
              state.isAuthenticated = true;
              clearLoadingAndError(state);
            });
          } catch (error) {
            set((state) => {
              setError(state, handleStoreError(error, 'Registration failed'));
              state.isAuthenticated = false;
              state.user = null;
            });
            throw error;
          }
        },

        logout: async () => {
          set((state) => {
            setLoading(state);
          });

          try {
            await apolloClient.mutate({
              mutation: LOGOUT_MUTATION,
            });
          } catch (error) {
            console.error('Logout error:', error);
          } finally {
            // Clear all auth tokens and user-specific storage
            clearAllAuthStorage();

            // Disconnect WebSocket
            disconnectWebSocket();

            // Reset Apollo cache completely
            try {
              await apolloClient.clearStore();
              await apolloClient.resetStore();
            } catch (e) {
              console.warn('Failed to reset Apollo cache:', e);
            }

            set((state) => {
              state.user = null;
              state.accessToken = null;
              state.refreshToken = null;
              state.isAuthenticated = false;
              clearLoadingAndError(state);
            });

            // Reset in-memory user-data stores so the next login starts clean.
            resetAllUserStores();
          }
        },

        refreshAuth: async () => {
          const token = localStorage.getItem('authToken');
          if (!token) {
            set((state) => {
              state.isAuthenticated = false;
              state.user = null;
            });
            return;
          }

          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.query({
              query: ME_QUERY,
              fetchPolicy: 'network-only',
            });

            // Check if user was not found (null response) - user may have been deleted or DB reset
            if (!data.me) {
              // Disconnect WebSocket
              disconnectWebSocket();

              // Clear tokens and storage
              clearTokens();

              // Reset state and redirect to login
              set((state) => {
                state.user = null;
                state.accessToken = null;
                state.refreshToken = null;
                state.isAuthenticated = false;
                clearLoadingAndError(state);
              });

              // Force redirect to login
              if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
                window.location.href = '/login';
              }
              return;
            }

            set((state) => {
              state.user = { ...data.me, role: normalizeRole(data.me.role) };
              state.isAuthenticated = true;
              clearLoadingAndError(state);
            });
          } catch {
            // Token might be expired or user not found
            // The error link will handle "user not found" errors and trigger logout
            clearTokens();
            set((state) => {
              state.user = null;
              state.isAuthenticated = false;
              clearLoadingAndError(state);
            });
          }
        },

        setUser: (user: AuthUser | null) => {
          set((state) => {
            state.user = user;
            state.isAuthenticated = !!user;
          });
        },

        clearError: () => {
          set((state) => {
            state.error = null;
          });
        },

        setTokens: (accessToken: string | null, refreshToken: string | null) => {
          set((state) => {
            state.accessToken = accessToken;
            state.refreshToken = refreshToken;
          });
          if (accessToken && refreshToken) {
            setTokens(accessToken, refreshToken);
          } else {
            clearTokens();
          }
        },

        setAuth: (user: AuthUser, accessToken: string, refreshToken: string) => {
          set((state) => {
            state.user = user;
            state.accessToken = accessToken;
            state.refreshToken = refreshToken;
            state.isAuthenticated = true;
            state.error = null;
          });
          setTokens(accessToken, refreshToken);
        },
      })),
    { name: 'authStore' }
  )
);

// Clear all auth-related and user-specific localStorage data
const clearAllAuthStorage = () => {
  // Clear auth tokens
  clearTokens();

  // Clear all Zustand persist storage (user-specific data)
  removeStoredItem('auth-storage');
  removeStoredItem('conversation-storage');
  removeStoredItem('character-storage');
  removeStoredItem('settings-storage');

  // Clear Zustand persist storage (matches the persist config name in each store)
  removeStoredItem('authStore');
  removeStoredItem('user');

  // Clear session storage as well
  sessionStorage.clear();

};

/**
 * Reset every in-memory user-data store on logout/auth-failure. Without this,
 * user A's messages/bookmarks/inventory/achievements survive in memory after
 * logout and user B (logging in on the same tab) can see A's stale state when
 * a conversation id lines up. localStorage persistence was already cleared;
 * the Zustand Maps/arrays need explicit reset.
 */
const resetAllUserStores = () => {
  useMessageStore.getState().reset();
  useBookmarkStore.getState().reset();
  useConversationStore.getState().reset();
  useCharacterSheetStore.getState().reset();
  useCharacterStore.getState().reset();
  useWorldStateStore.getState().reset();
  useCommunityStore.getState().reset();
  useAchievementStore.getState().reset();
  useTTSStore.getState().reset();
};

// Shared logout handler for both Apollo and WebSocket auth failures.
// Re-entry guard: if both Apollo and WebSocket raise auth errors in the same
// tick, only run the teardown once.
let authFailureInProgress = false;
const handleAuthFailure = async () => {
  if (authFailureInProgress) return;
  authFailureInProgress = true;

  // Disconnect WebSocket first
  disconnectWebSocket();

  // Clear all auth storage
  clearAllAuthStorage();

  // Reset Apollo cache completely
  try {
    await resetApolloCache();
  } catch (e) {
    console.warn('[Auth] Failed to reset Apollo cache:', e);
  }

  // Reset Zustand state
  useAuthStore.setState({
    user: null,
    accessToken: null,
    refreshToken: null,
    isAuthenticated: false,
    isLoading: false,
    error: null,
  });

  // Reset in-memory user-data stores so the next login starts clean.
  resetAllUserStores();

  // Force redirect to login if not already there
  if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
    window.location.href = '/login';
  }

  // Reset the re-entry guard so a subsequent auth failure (e.g. after the user
  // logs back in and hits a fresh error) runs the full teardown again.
  // Previously this was never reset, so only the FIRST failure in a page
  // session was handled.
  authFailureInProgress = false;
};

// Register callback so Apollo client can trigger logout on auth failures.
// Clear any previous registration first so HMR doesn't stack stale closures.
clearWebSocketAuthErrorCallback();
setAuthLogoutCallback(handleAuthFailure);
// Keep the store's in-memory tokens in sync after a silent refresh (the
// refresh path in apollo-client writes localStorage via setTokens but never
// touched the zustand fields, so they went stale until the next full login).
setTokensSyncCallback((authToken, refreshToken) => {
  useAuthStore.setState({ accessToken: authToken, refreshToken });
});
// Smart handler: if the user still has a valid token, the WebSocket just lost its
// auth context (e.g. stale connection from before login). Reconnect with the token
// rather than logging the user out. Only log out when there is no token.
setWebSocketAuthErrorCallback(() => {
  const token = localStorage.getItem('authToken');
  if (token) {
    reconnectWebSocket();
  } else {
    handleAuthFailure();
  }
});

import { hot } from '@/lib/env';

// In Vite HMR, clear the callback before the module is replaced so the stale
// closure from the old module instance can't fire against the new store.
if (hot) {
  hot.dispose(() => {
    clearWebSocketAuthErrorCallback();
  });
}
