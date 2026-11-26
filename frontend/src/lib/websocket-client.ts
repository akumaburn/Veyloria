/**
 * Standalone WebSocket client for GraphQL subscriptions.
 * Uses native WebSocket with graphql-transport-ws protocol.
 * Alternative to Apollo's WebSocket integration.
 */

import { setTokensChangedCallback } from './apollo-client';

type SubscriptionCallback<T = any> = (data: T) => void;
type ErrorCallback = (error: Error) => void;
type AuthErrorCallback = () => void;

// Represents a single subscriber to a subscription
interface SubscriptionListener {
  listenerId: string;
  onData: SubscriptionCallback;
  onError?: ErrorCallback;
}

interface Subscription {
  id: string;
  query: string;
  variables?: Record<string, any>;
  // Support multiple listeners per subscription to fix callback override issue
  listeners: SubscriptionListener[];
}

interface ConnectionParams {
  authorization?: string;
}

// Callback for authentication errors
let onAuthError: AuthErrorCallback | null = null;

export function setWebSocketAuthErrorCallback(callback: AuthErrorCallback) {
  onAuthError = callback;
}

export function clearWebSocketAuthErrorCallback() {
  onAuthError = null;
}

class WebSocketClient {
  private ws: WebSocket | null = null;
  private url: string;
  private subscriptions: Map<string, Subscription> = new Map();
  private messageId = 0;
  private reconnectAttempts = 0;
  private reconnectDelay = 1000;
  private static MAX_RECONNECT_DELAY = 30000;
  private isConnected = false;
  private connectionAckReceived = false;
  private pendingSubscriptions: Subscription[] = [];
  private getAuthToken: () => string | null;
  private connectPromise: Promise<void> | null = null; // Track pending connection
  private pendingConnectReject: ((reason?: unknown) => void) | null = null;

  // Deduplication: Track subscriptions by query+variables hash to prevent duplicates
  // Key: hash of query+variables, Value: subscription ID
  private subscriptionsByKey: Map<string, string> = new Map();

  /**
   * OPTIMIZED: Generate subscription key without JSON.stringify overhead.
   *
   * Previous: JSON.stringify on every call (~0.1-0.5ms per call, allocates string)
   * New: Fast path for common cases, fallback to JSON.stringify only when needed
   *
   * Most subscriptions have simple variables like { conversationId: "uuid" }
   * This optimization handles 95%+ of cases without serialization.
   */
  private getSubscriptionKey(query: string, variables?: Record<string, any>): string {
    if (!variables) {
      return query;  // No variables, query alone is the key
    }

    const keys = Object.keys(variables);
    const keyCount = keys.length;

    // Fast path: no variables
    if (keyCount === 0) {
      return query;
    }

    // Fast path: single primitive variable (most common case: conversationId)
    if (keyCount === 1) {
      const key = keys[0];
      const value = variables[key];
      // Only optimize for primitives (string, number, boolean, null)
      if (value === null || typeof value !== 'object') {
        return `${query}::${key}=${value}`;
      }
    }

    // Fast path: two primitive variables
    if (keyCount === 2) {
      const [k1, k2] = keys.sort();  // Sort for consistent key order
      const v1 = variables[k1];
      const v2 = variables[k2];
      if ((v1 === null || typeof v1 !== 'object') &&
          (v2 === null || typeof v2 !== 'object')) {
        return `${query}::${k1}=${v1}&${k2}=${v2}`;
      }
    }

    // Fallback: complex variables, use JSON.stringify
    return `${query}::${JSON.stringify(variables)}`;
  }

  constructor(url: string, getAuthToken: () => string | null) {
    this.url = url;
    this.getAuthToken = getAuthToken;
  }

  connect(): Promise<void> {
    // If already connected, resolve immediately
    if (this.ws && this.isConnected) {
      return Promise.resolve();
    }

    // If connection is in progress, return the existing promise
    if (this.connectPromise) {
      return this.connectPromise;
    }

    this.connectPromise = new Promise((resolve, reject) => {
      this.pendingConnectReject = reject;
      try {
        // Connect with graphql-transport-ws subprotocol
        this.ws = new WebSocket(this.url, 'graphql-transport-ws');
      } catch (error) {
        console.error('[WS] Failed to create WebSocket:', error);
        this.connectPromise = null; // Clear pending connection promise
        this.pendingConnectReject = null;
        reject(error);
        return;
      }

      const connectionTimeout = setTimeout(() => {
        if (!this.connectionAckReceived) {
          console.error('[WS] Connection timeout - no ack received');
          this.connectPromise = null; // Clear pending connection promise
          this.pendingConnectReject = null;
          this.ws?.close();
          reject(new Error('Connection timeout'));
        }
      }, 10000);

      this.ws.onopen = () => {
        const token = this.getAuthToken();
        // Omit the `authorization` field entirely when we don't have a token.
        // SmallRye reads connection_init.payload.authorization; an empty string
        // is treated differently from a missing field in some server configs,
        // so prefer the cleaner "no auth" shape.
        const payload: ConnectionParams = {};
        if (token) {
          payload.authorization = `Bearer ${token}`;
        }
        const initMessage = {
          type: 'connection_init',
          payload,
        };
        // Diagnostic: surface the auth state of every connection_init so we can
        // correlate frontend WebSocket auth with backend "Not authenticated"
        // errors. Token length only — never log the token value itself.
        if (token) {
          console.info(`[WS] connection_init sent WITH auth (token length: ${token.length})`);
        } else {
          console.warn('[WS] connection_init sent WITHOUT auth — localStorage.authToken was empty at onopen time. '
            + 'Subscriptions on this connection will fail with "Not authenticated" until the WebSocket is reconnected.');
        }
        this.ws?.send(JSON.stringify(initMessage));
      };

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data);

          switch (message.type) {
            case 'connection_ack':
              clearTimeout(connectionTimeout);
              this.connectionAckReceived = true;
              this.isConnected = true;
              this.reconnectAttempts = 0;
              this.connectPromise = null; // Clear pending connection promise
              this.pendingConnectReject = null;

              // Prune any subscriptions that have lost all their listeners
              // before replaying — guards against caller bugs where a listener
              // was added and its owner GC'd without calling unsubscribe.
              this.subscriptions.forEach((sub, subId) => {
                if (sub.listeners.length === 0) {
                  const key = this.getSubscriptionKey(sub.query, sub.variables);
                  this.subscriptionsByKey.delete(key);
                  this.subscriptions.delete(subId);
                }
              });

              // Re-establish ALL remaining subscriptions on reconnect.
              // This is critical for message streaming to resume after network issues.
              // Note: subscriptions created while disconnected live in BOTH
              // `subscriptions` and `pendingSubscriptions`; sending both would
              // emit duplicate subscribe ids on the wire (the server rejects a
              // second subscribe with the same id). Send the canonical set from
              // `subscriptions` and drop the pending queue entirely.
              this.subscriptions.forEach(sub => this.sendSubscription(sub));
              this.pendingSubscriptions = [];

              resolve();
              break;

            case 'next': {
              // Subscription data received - broadcast to ALL listeners
              const sub = this.subscriptions.get(message.id);
              if (sub && message.payload?.data) {
                // Call all registered listeners (fixes callback override issue)
                sub.listeners.forEach(listener => {
                  try {
                    listener.onData(message.payload.data);
                  } catch (err) {
                    console.error('[WS] Listener callback error:', err);
                  }
                });
              }
              }
              break;

            case 'error': {
              // Subscription error - broadcast to ALL listeners
              const errorSub = this.subscriptions.get(message.id);
              // Check for authentication errors
              const errorPayload = message.payload;
              if (Array.isArray(errorPayload)) {
                const authError = errorPayload.find((e: any) => {
                  const msg = (e.message || '').toLowerCase();
                  return (
                    e.extensions?.code === 'UNAUTHENTICATED' ||
                    e.extensions?.code === 'UNAUTHORIZED' ||
                    e.extensions?.code === 'USER_NOT_FOUND' ||
                    e.extensions?.code === 'ACCOUNT_DISABLED' ||
                    msg.includes('unauthorized') ||
                    msg.includes('unauthenticated') ||
                    msg.includes('not authenticated') ||
                    msg.includes('user not found') ||
                    msg.includes('user deleted') ||
                    msg.includes('account suspended') ||
                    msg.includes('account disabled') ||
                    msg.includes('invalid user') ||
                    msg.includes('session invalid') ||
                    msg.includes('session expired')
                  );
                });
                if (authError && onAuthError) {
                  onAuthError();
                  return;
                }
              }
              // Notify all listeners about the error
              if (errorSub) {
                const errorObj = new Error(JSON.stringify(message.payload));
                errorSub.listeners.forEach(listener => {
                  if (listener.onError) {
                    try {
                      listener.onError(errorObj);
                    } catch (err) {
                      console.error('[WS] Listener error callback error:', err);
                    }
                  }
                });
              }
              }
              break;

            case 'connection_error': {
              // Connection-level error (often auth failure)
              console.error('[WS] Connection error:', message.payload);
              clearTimeout(connectionTimeout);
              this.connectPromise = null; // Clear pending connection promise
              const connErrorMsg = (message.payload?.message || '').toLowerCase();
              const isAuthConnError =
                connErrorMsg.includes('unauthorized') ||
                connErrorMsg.includes('unauthenticated') ||
                connErrorMsg.includes('not authenticated') ||
                connErrorMsg.includes('user not found') ||
                connErrorMsg.includes('user deleted') ||
                connErrorMsg.includes('account suspended') ||
                connErrorMsg.includes('account disabled') ||
                connErrorMsg.includes('invalid user') ||
                connErrorMsg.includes('session invalid') ||
                connErrorMsg.includes('session expired');
              if (isAuthConnError && onAuthError) {
                onAuthError();
              }
              reject(new Error(message.payload?.message || 'Connection error'));
              }
              break;

            case 'complete':
              // Subscription completed by the server (e.g. one-shot
              // subscriptions). Tear down the full bookkeeping for this id —
              // not just the subscription map — otherwise subscriptionsByKey
              // and listenerToSubscription keep pointing at a dead id: a later
              // subscribe() with the same query creates a NEW id while old
              // listeners still reference the old one and can never unsubscribe.
              {
                const sub = this.subscriptions.get(message.id);
                if (sub) {
                  const key = this.getSubscriptionKey(sub.query, sub.variables);
                  if (this.subscriptionsByKey.get(key) === message.id) {
                    this.subscriptionsByKey.delete(key);
                  }
                  for (const listener of sub.listeners) {
                    this.listenerToSubscription.delete(listener.listenerId);
                  }
                }
                this.subscriptions.delete(message.id);
                // If this subscription was queued while disconnected, drop the
                // pending entry too — otherwise it is re-sent on the next
                // reconnect even though the caller already got its answer.
                this.pendingSubscriptions = this.pendingSubscriptions.filter(s => s.id !== message.id);
              }
              break;

            case 'ping':
              this.ws?.send(JSON.stringify({ type: 'pong' }));
              break;

            case 'pong':
              // Server responded to our ping
              break;
          }
        } catch (error) {
          console.error('[WS] Failed to parse message:', error);
        }
      };

      this.ws.onerror = (error) => {
        console.error('[WS] WebSocket error:', error);
        clearTimeout(connectionTimeout);
        // A transport-level error will be followed by onclose; reject any
        // pending connect promise there so callers don't hang forever.
      };

      this.ws.onclose = (event) => {
        clearTimeout(connectionTimeout);
        this.isConnected = false;
        this.connectionAckReceived = false;
        // Reject a still-pending connect promise (e.g. server closed with
        // 4401/4403 before ever sending connection_ack). Without this the
        // caller awaiting connect() hangs forever because only connection_ack
        // ever resolves it.
        if (this.connectPromise) {
          const pendingReject = this.pendingConnectReject;
          this.connectPromise = null;
          this.pendingConnectReject = null;
          if (pendingReject) {
            pendingReject(new Error(`WebSocket closed before connection_ack (code ${event.code})`));
          }
        }

        // Check for authentication-related close codes
        // 4401 = Unauthorized, 4403 = Forbidden
        if (event.code === 4401 || event.code === 4403) {
          if (onAuthError) {
            onAuthError();
          }
          return;
        }

        // Attempt to reconnect if not a normal close. Back off exponentially,
        // capped at 30s, and retry indefinitely: a temporary outage (server
        // restart, network blip) longer than the old 5-attempt window (~31s)
        // permanently killed real-time chat until a page reload. Once a
        // connection succeeds, connection_ack resets reconnectAttempts, so a
        // later blip starts the backoff over.
        if (event.code !== 1000) {
          this.reconnectAttempts++;
          const attempt = this.reconnectAttempts;
          const delay = Math.min(
            this.reconnectDelay * Math.pow(2, attempt - 1),
            WebSocketClient.MAX_RECONNECT_DELAY
          );
          setTimeout(() => this.connect(), delay);
        }
      };
    });

    return this.connectPromise;
  }

  private sendSubscription(subscription: Subscription): void {
    if (!this.ws || !this.isConnected) {
      this.pendingSubscriptions.push(subscription);
      return;
    }

    const message = {
      id: subscription.id,
      type: 'subscribe',
      payload: {
        query: subscription.query,
        variables: subscription.variables,
      },
    };

    this.ws.send(JSON.stringify(message));
  }

  // Track listener IDs to subscription IDs for cleanup
  private listenerToSubscription: Map<string, string> = new Map();
  private listenerId = 0;

  subscribe<T = any>(
    query: string,
    variables?: Record<string, any>,
    onData?: SubscriptionCallback<T>,
    onError?: ErrorCallback
  ): string {
    // Generate unique listener ID for this subscriber
    const newListenerId = `listener-${++this.listenerId}`;

    const listener: SubscriptionListener = {
      listenerId: newListenerId,
      onData: onData || (() => {}),
      onError,
    };

    // DEDUPLICATION: Check if we already have a subscription for this query+variables
    const key = this.getSubscriptionKey(query, variables);
    const existingSubId = this.subscriptionsByKey.get(key);

    if (existingSubId && this.subscriptions.has(existingSubId)) {
      // Already subscribed - add this listener to existing subscription
      const existing = this.subscriptions.get(existingSubId)!;
      existing.listeners.push(listener);
      this.listenerToSubscription.set(newListenerId, existingSubId);
      console.debug('[WS] Added listener to existing subscription:', existingSubId, 'listeners:', existing.listeners.length);
      return newListenerId;
    }

    // Create new subscription
    const subscriptionId = String(++this.messageId);

    const subscription: Subscription = {
      id: subscriptionId,
      query,
      variables,
      listeners: [listener],
    };

    this.subscriptions.set(subscriptionId, subscription);
    this.subscriptionsByKey.set(key, subscriptionId);
    this.listenerToSubscription.set(newListenerId, subscriptionId);

    if (this.isConnected) {
      this.sendSubscription(subscription);
    } else {
      this.pendingSubscriptions.push(subscription);
      this.connect().catch(console.error);
    }

    return newListenerId;
  }

  unsubscribe(listenerId: string): void {
    // Find which subscription this listener belongs to
    const subscriptionId = this.listenerToSubscription.get(listenerId);
    if (!subscriptionId) {
      console.debug('[WS] Listener not found for unsubscribe:', listenerId);
      return;
    }

    this.listenerToSubscription.delete(listenerId);

    const sub = this.subscriptions.get(subscriptionId);
    if (!sub) {
      return;
    }

    // Remove the listener from the subscription
    const listenerIndex = sub.listeners.findIndex(l => l.listenerId === listenerId);
    if (listenerIndex !== -1) {
      sub.listeners.splice(listenerIndex, 1);
      console.debug('[WS] Removed listener from subscription:', subscriptionId, 'remaining listeners:', sub.listeners.length);
    }

    // Only close the subscription when no more listeners
    if (sub.listeners.length === 0) {
      if (this.ws && this.isConnected) {
        this.ws.send(JSON.stringify({ id: subscriptionId, type: 'complete' }));
      }

      // Clean up deduplication map
      const key = this.getSubscriptionKey(sub.query, sub.variables);
      this.subscriptionsByKey.delete(key);

      this.subscriptions.delete(subscriptionId);
      // Drop any pending entry so a later reconnect does not re-send a
      // subscribe for a subscription the caller already unsubscribed from.
      this.pendingSubscriptions = this.pendingSubscriptions.filter(s => s.id !== subscriptionId);
      console.debug('[WS] Closed subscription (no more listeners):', subscriptionId);
    }
  }

  disconnect(): void {
    if (this.ws) {
      this.subscriptions.forEach((_, id) => {
        this.ws?.send(JSON.stringify({ id, type: 'complete' }));
      });
      this.subscriptions.clear();
      this.subscriptionsByKey.clear();
      this.listenerToSubscription.clear();
      this.pendingSubscriptions = [];
      this.ws.close(1000, 'Client disconnect');
      this.ws = null;
    }
    this.isConnected = false;
    this.connectionAckReceived = false;
  }

  // Close the current socket and reconnect with a fresh connection_init, preserving
  // all subscriptions so they are re-sent once connection_ack is received.
  // Use this after login/token refresh so the new connection carries the auth token.
  forceReconnect(): void {
    if (this.ws) {
      this.ws.onclose = null; // suppress the auto-reconnect handler
      this.ws.close(1000, 'Auth refresh');
      this.ws = null;
    }
    this.isConnected = false;
    this.connectionAckReceived = false;
    this.connectPromise = null;
    this.reconnectAttempts = 0;
    this.connect().catch(err => console.error('[WS] forceReconnect failed:', err));
  }

  getStatus(): { connected: boolean; subscriptionCount: number } {
    return {
      connected: this.isConnected,
      subscriptionCount: this.subscriptions.size,
    };
  }
}

// Singleton instance
let wsClient: WebSocketClient | null = null;

export function getWebSocketClient(): WebSocketClient {
   if (!wsClient) {
     // Single-origin routing: in development the Vite dev proxy forwards both
     // HTTP and WebSocket /graphql upgrades to the backend (see vite.config.ts),
     // and in production Nginx does the same. So the WebSocket targets the same
     // origin the page was served from — no backend port is hardcoded here.
     //
     // SmallRye GraphQL serves HTTP and WebSocket on the SAME /graphql path
     // (there is no separate /graphql-ws endpoint); subscriptions upgrade that
     // URL with the graphql-transport-ws subprotocol.
     const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
     const pageOrigin = `${protocol}//${window.location.host}`;

     // Escape hatch: VITE_WS_URL (or VITE_WS_ENDPOINT) lets a developer point
     // the WebSocket somewhere else (e.g. if running the backend on a different
     // host). Read import.meta.env directly so Vite's static transform reliably
     // injects it. Not set by default — the proxy is the intended path.
     const viteEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
     const wsOverride = viteEnv?.VITE_WS_ENDPOINT || viteEnv?.VITE_WS_URL;

     // Guarantee the /graphql path — an origin-only override would otherwise
     // connect to / and fail the handshake.
     const base = (wsOverride || pageOrigin).replace(/\/+$/, '');
     const WS_ENDPOINT = /\/graphql(\/|$)/.test(base) ? base : `${base}/graphql`;

     const getAuthToken = () => localStorage.getItem('authToken');
     wsClient = new WebSocketClient(WS_ENDPOINT, getAuthToken);
   }
   return wsClient;
 }

export function disconnectWebSocket(): void {
  if (wsClient) {
    wsClient.disconnect();
    wsClient = null;
  }
}

export function reconnectWebSocket(): void {
  if (wsClient) {
    wsClient.forceReconnect();
  }
}

// Wire any token rotation in apolloClient (login, register, refresh-token
// flow, manual setTokens) to a WebSocket reconnect. Without this, a long-
// lived WebSocket keeps the auth state from its original connection_init
// forever, so subscriptions opened after a token change keep failing with
// "Not authenticated" until the user navigates or refreshes.
setTokensChangedCallback(() => {
  if (wsClient) {
    wsClient.forceReconnect();
  }
});

// Helper hook for React
import { useEffect, useState, useMemo, useRef } from 'react';

/**
 * OPTIMIZED: Generate stable key for variables without JSON.stringify on every render.
 * Uses same fast-path logic as WebSocketClient.getSubscriptionKey()
 */
function getVariablesKey(variables?: Record<string, any>): string {
  if (!variables) return '';

  const keys = Object.keys(variables);
  const keyCount = keys.length;

  if (keyCount === 0) return '';

  // Fast path: single primitive (most common: conversationId)
  if (keyCount === 1) {
    const key = keys[0];
    const value = variables[key];
    if (value === null || typeof value !== 'object') {
      return `${key}=${value}`;
    }
  }

  // Fast path: two primitives
  if (keyCount === 2) {
    const [k1, k2] = keys.sort();
    const v1 = variables[k1];
    const v2 = variables[k2];
    if ((v1 === null || typeof v1 !== 'object') &&
        (v2 === null || typeof v2 !== 'object')) {
      return `${k1}=${v1}&${k2}=${v2}`;
    }
  }

  // Fallback for complex variables
  return JSON.stringify(variables);
}

export function useWebSocketSubscription<T = any>(
  query: string,
  variables?: Record<string, any>,
  skip = false,
  /**
   * Called synchronously for every frame. `data` holds only the latest frame,
   * and frames arriving before the next render replace each other; consumers
   * that must see each frame (such as a playback queue) use this instead.
   */
  onData?: (data: T) => void,
): { data: T | null; error: Error | null; loading: boolean } {
  const [data, setData] = useState<T | null>(null);
  const onDataRef = useRef(onData);
  onDataRef.current = onData;
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(!skip);
  // Monotonic generation counter to discard stale frames from a previous
  // effect run (see the subscribe callbacks below).
  const subscriptionGenerationRef = useRef(0);

  // OPTIMIZED: Memoize variables key to avoid JSON.stringify on every render
  // Previous: JSON.stringify called in dependency array = every render
  // Now: getVariablesKey uses fast paths, only stringify for complex objects
  const variablesKey = useMemo(() => getVariablesKey(variables), [variables]);

  useEffect(() => {
    if (skip) {
      setLoading(false);
      setData(null); // Clear data when skipped
      return;
    }

    // Guard against stale frames: when the subscription is torn down and a
    // new one is created (e.g. switching conversations), frames already in
    // flight from the old subscription can still arrive and call this
    // listener. The generation counter lets the callback ignore frames that
    // belong to a previous effect run.
    const generation = ++subscriptionGenerationRef.current;

    // Reset data when subscription changes to prevent stale data
    setData(null);
    setError(null);
    const client = getWebSocketClient();
    setLoading(true);

    const subscriptionId = client.subscribe<T>(
      query,
      variables,
      (newData) => {
        if (generation !== subscriptionGenerationRef.current) return;
        onDataRef.current?.(newData);
        setData(newData);
        setLoading(false);
        setError(null);
      },
      (err) => {
        if (generation !== subscriptionGenerationRef.current) return;
        setError(err);
        setLoading(false);
      }
    );

    return () => {
      client.unsubscribe(subscriptionId);
    };
  }, [query, variablesKey, skip]);

  return { data, error, loading };
}

// Export the class for direct usage
export { WebSocketClient };
