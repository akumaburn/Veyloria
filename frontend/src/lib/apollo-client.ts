import {
  ApolloClient,
  InMemoryCache,
  createHttpLink,
  ApolloLink,
  Observable,
} from '@apollo/client';
import { setContext } from '@apollo/client/link/context';
import { onError } from '@apollo/client/link/error';
import { toast } from 'react-hot-toast';
import { apiUrl as GRAPHQL_ENDPOINT } from './env';
import { mergeTerrainData } from './apolloCachePolicies';

// Helper to get auth token
const getAuthToken = (): string | null => {
  return localStorage.getItem('authToken');
};

// Helper to get refresh token
const getRefreshToken = (): string | null => {
  return localStorage.getItem('refreshToken');
};

// Optional callback fired whenever the auth tokens change. Used to reconnect
// the WebSocket so subscriptions pick up the new auth context. Registered
// from websocket-client.ts to avoid an import cycle (apollo-client must not
// depend on websocket-client at module load).
let onTokensChanged: (() => void) | null = null;
export const setTokensChangedCallback = (cb: (() => void) | null) => {
  onTokensChanged = cb;
};

// Optional callback fired with the raw new tokens whenever they change (login,
// register, silent refresh). The auth store registers this so its in-memory
// accessToken/refreshToken stay in sync with localStorage after a silent
// refresh, instead of going stale until the next full login.
let onTokensSynced: ((authToken: string, refreshToken: string) => void) | null = null;
export const setTokensSyncCallback = (cb: ((authToken: string, refreshToken: string) => void) | null) => {
  onTokensSynced = cb;
};

// Helper to set tokens
const setTokens = (authToken: string, refreshToken: string) => {
  localStorage.setItem('authToken', authToken);
  localStorage.setItem('refreshToken', refreshToken);
  // Notify the auth store so its in-memory copies match localStorage.
  if (onTokensSynced) {
    try {
      onTokensSynced(authToken, refreshToken);
    } catch (e) {
      console.warn('[Apollo] tokens-sync callback threw:', e);
    }
  }
  // Notify subscribers (the WebSocket client) so they can re-handshake with
  // the new auth. Without this, a long-lived WebSocket keeps the old
  // connection_init.payload and every subscription on it fails with
  // "Not authenticated" until the user navigates or refreshes.
  if (onTokensChanged) {
    try {
      onTokensChanged();
    } catch (e) {
      console.warn('[Apollo] tokens-changed callback threw:', e);
    }
  }
};

// Helper to clear tokens
const clearTokens = () => {
  localStorage.removeItem('authToken');
  localStorage.removeItem('refreshToken');
};

// Callback to notify auth store of logout (set by auth store)
let onAuthLogout: (() => void) | null = null;

export const setAuthLogoutCallback = (callback: () => void) => {
  onAuthLogout = callback;
};

// Force logout - clears tokens and notifies auth store
const forceLogout = () => {
  sessionEpoch++;
  clearTokens();
  if (onAuthLogout) {
    onAuthLogout();
  } else {
    console.warn('[Apollo] No auth logout callback registered, redirecting manually');
    if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
      window.location.href = '/login';
    }
  }
};

// Token refresh logic
let isRefreshing = false;
let refreshSubscribers: { resolve: (token: string) => void; reject: (err: Error) => void }[] = [];

// Session epoch: bumped on every logout. A token refresh that started before a
// logout but completes after must be discarded — otherwise the stale response
// re-writes fresh tokens and silently re-authenticates the next user.
let sessionEpoch = 0;

const subscribeTokenRefresh = (cb: { resolve: (token: string) => void; reject: (err: Error) => void }) => {
  refreshSubscribers.push(cb);
};

const onTokenRefreshed = (token: string) => {
  refreshSubscribers.forEach((cb) => cb.resolve(token));
  refreshSubscribers = [];
};

const onTokenRefreshFailed = (err: Error) => {
  refreshSubscribers.forEach((cb) => cb.reject(err));
  refreshSubscribers = [];
};

const refreshAuthToken = async (): Promise<string> => {
  if (isRefreshing) {
    // Park this caller until the in-flight refresh settles. The waiter MUST
    // have a reject path: if the refresh fails, failing to reject leaves the
    // caller's promise pending forever (the UI operation hangs), and the
    // stale subscriber could otherwise be resolved with a *future* token
    // from a different session.
    return new Promise((resolve, reject) => {
      subscribeTokenRefresh({ resolve, reject });
    });
  }

  isRefreshing = true;
  const refreshToken = getRefreshToken();
  const epochAtStart = sessionEpoch;

  if (!refreshToken) {
    isRefreshing = false;
    forceLogout();
    throw new Error('No refresh token available');
  }

  try {
    const response = await fetch(GRAPHQL_ENDPOINT, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Pragma': 'no-cache',
        'Expires': '0',
      },
      cache: 'no-store',
      body: JSON.stringify({
        query: `
          mutation RefreshToken($refreshToken: String!) {
            refreshToken(refreshToken: $refreshToken) {
              accessToken
              refreshToken
              expiresIn
            }
          }
        `,
        variables: { refreshToken },
      }),
    });

    const data = await response.json();
    
    if (data.errors || !data.data?.refreshToken) {
      throw new Error('Failed to refresh token');
    }

    // A logout (or re-login) while the refresh was in flight invalidates the
    // result. Without this guard, a stale refresh response could re-write
    // fresh tokens AFTER logout, silently re-authenticating the next user.
    if (sessionEpoch !== epochAtStart || getRefreshToken() !== refreshToken) {
      throw new Error('Session changed during token refresh');
    }

    const { accessToken, refreshToken: newRefreshToken } = data.data.refreshToken;
    setTokens(accessToken, newRefreshToken);
    onTokenRefreshed(accessToken);
    
    return accessToken;
  } catch (error) {
    onTokenRefreshFailed(error instanceof Error ? error : new Error('Failed to refresh token'));
    // A failed refresh from an earlier session must not clear a newer login.
    if (sessionEpoch === epochAtStart && getRefreshToken() === refreshToken) {
      forceLogout();
    }
    throw error;
  } finally {
    isRefreshing = false;
  }
};

// HTTP Link with cache control
const httpLink = createHttpLink({
  uri: GRAPHQL_ENDPOINT,
  credentials: 'include',
  fetchOptions: {
    cache: 'no-store',  // Prevent browser HTTP caching
  },
});

// Auth Link with cache-control headers
const authLink = setContext(async (_, { headers }) => {
  const token = getAuthToken();

  return {
    headers: {
      ...headers,
      authorization: token ? `Bearer ${token}` : '',
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      'Pragma': 'no-cache',
      'Expires': '0',
    },
  };
});

// Error Link with token refresh and auth failure handling
const errorLink = onError(({ graphQLErrors, networkError, operation, forward }) => {
  if (graphQLErrors) {
    for (const err of graphQLErrors) {
      const errorMessage = err.message.toLowerCase();

      // Fatal auth errors that should trigger immediate logout (no refresh attempt)
      // These errors indicate the user/session is invalid and cannot be recovered
      const isFatalAuthError =
        errorMessage.includes('user not found') ||
        errorMessage.includes('user deleted') ||
        errorMessage.includes('account suspended') ||
        errorMessage.includes('account disabled') ||
        errorMessage.includes('invalid user') ||
        errorMessage.includes('session invalid') ||
        errorMessage.includes('session expired') ||
        err.extensions?.code === 'USER_NOT_FOUND' ||
        err.extensions?.code === 'ACCOUNT_DISABLED';

      if (isFatalAuthError) {
        forceLogout();
        return;
      }

      // Recoverable auth errors that should trigger token refresh
      const isRecoverableAuthError =
        err.extensions?.code === 'UNAUTHENTICATED' ||
        err.extensions?.code === 'UNAUTHORIZED' ||
        errorMessage.includes('unauthorized') ||
        errorMessage.includes('authentication required') ||
        errorMessage.includes('not authenticated') ||
        errorMessage.includes('token expired') ||
        errorMessage.includes('invalid token') ||
        errorMessage.includes('jwt expired');

      if (isRecoverableAuthError) {
        // Try to refresh the token
        return new Observable((observer) => {
          refreshAuthToken()
            .then((newToken) => {
              // Retry the request with new token
              const oldHeaders = operation.getContext().headers;
              operation.setContext({
                headers: {
                  ...oldHeaders,
                  authorization: `Bearer ${newToken}`,
                },
              });

              const subscriber = {
                next: observer.next.bind(observer),
                error: observer.error.bind(observer),
                complete: observer.complete.bind(observer),
              };

              forward(operation).subscribe(subscriber);
            })
            .catch((error) => {
              observer.error(error);
            });
        });
      }

      // Show error toast for other errors (but not auth errors we already handled)
      toast.error(err.message);
      console.error(`GraphQL error: ${err.message}`);
    }
  }

  if (networkError) {
    console.error(`Network error: ${networkError.message}`);

    // Check if network error is auth-related (e.g., 401 response)
    if ('statusCode' in networkError && (networkError as any).statusCode === 401) {
      forceLogout();
      return;
    }

    // Don't show toast for abort errors
    if (!networkError.message.includes('abort')) {
      toast.error('Network error. Please check your connection.');
    }
  }

  // Return undefined for all other cases
  return undefined;
});

// Combine all links - HTTP only (subscriptions use standalone WebSocket client)
const link = ApolloLink.from([errorLink, authLink, httpLink]);

// Cache configuration
const cache = new InMemoryCache({
  typePolicies: {
    Query: {
      fields: {
        // Two views select different halves of this object; see the policy.
        terrainData: {
          merge: mergeTerrainData,
        },
        conversations: {
          // Use offset as keyArg to differentiate pagination requests
          keyArgs: ['filter'],
          merge(existing, incoming, { args }) {
            // If offset is 0 or not provided, this is a fresh fetch - replace all
            if (!args?.offset || args.offset === 0) {
              return incoming;
            }
            // Otherwise this is load-more, merge with existing
            const existingItems = existing?.items || [];
            const incomingItems = incoming?.items || [];
            // Deduplicate by id
            const existingIds = new Set(existingItems.map((item: any) => item.__ref || item.id));
            const newItems = incomingItems.filter(
              (item: any) => !existingIds.has(item.__ref || item.id)
            );
            return {
              ...incoming,
              items: [...existingItems, ...newItems],
            };
          },
        },
        messages: {
          keyArgs: ['conversationId'],
          merge(existing, incoming, { args }) {
            // If before is not provided, this is a fresh fetch - replace all
            if (!args?.before) {
              return incoming;
            }
            // Otherwise this is load-more (older messages), prepend
            const existingItems = existing?.items || [];
            const incomingItems = incoming?.items || [];
            // Deduplicate by id
            const existingIds = new Set(existingItems.map((item: any) => item.__ref || item.id));
            const newItems = incomingItems.filter(
              (item: any) => !existingIds.has(item.__ref || item.id)
            );
            return {
              ...incoming,
              items: [...newItems, ...existingItems],
            };
          },
        },
        characters: {
          keyArgs: ['filter'],
          merge(existing, incoming, { args }) {
            if (!args?.offset || args.offset === 0) {
              return incoming;
            }
            const existingItems = existing?.items || [];
            const incomingItems = incoming?.items || [];
            const existingIds = new Set(existingItems.map((item: any) => item.__ref || item.id));
            const newItems = incomingItems.filter(
              (item: any) => !existingIds.has(item.__ref || item.id)
            );
            return {
              ...incoming,
              items: [...existingItems, ...newItems],
            };
          },
        },
        myInvites: {
          keyArgs: ['filter'],
          merge(existing, incoming, { args }) {
            if (!args?.offset || args.offset === 0) {
              return incoming;
            }
            const existingItems = existing?.items || [];
            const incomingItems = incoming?.items || [];
            const existingIds = new Set(existingItems.map((item: any) => item.__ref || item.id));
            const newItems = incomingItems.filter(
              (item: any) => !existingIds.has(item.__ref || item.id)
            );
            return {
              ...incoming,
              items: [...existingItems, ...newItems],
            };
          },
        },
      },
    },
    Conversation: {
      keyFields: ['id'],
      fields: {
        messages: {
          merge(_existing, incoming) {
            return incoming;
          },
        },
      },
    },
    User: {
      keyFields: ['id'],
    },
    Message: {
      keyFields: ['id'],
    },
    Character: {
      keyFields: ['id'],
    },
  },
});

// Create Apollo Client with aggressive no-cache defaults to prevent stale data
export const apolloClient = new ApolloClient({
  link,
  cache,
  defaultOptions: {
    watchQuery: {
      fetchPolicy: 'no-cache',  // Never read from cache
      errorPolicy: 'all',
    },
    query: {
      fetchPolicy: 'no-cache',  // Never read from cache
      errorPolicy: 'all',
    },
    mutate: {
      fetchPolicy: 'no-cache',  // Don't cache mutation results
      errorPolicy: 'all',
    },
  },
  // Query deduplication merges identical concurrent in-flight queries into a
  // single network request. With no-cache fetch policies this is what prevents
  // rapid double-clicks / tab switches from firing overlapping requests whose
  // later response clobbers a fresher one.
  queryDeduplication: true,
});

// Function to completely reset Apollo cache
export const resetApolloCache = async () => {
  await apolloClient.clearStore();
  await apolloClient.resetStore();
};

// Export helper functions (setAuthLogoutCallback is already exported inline above)
export { getAuthToken, setTokens, clearTokens };