import { devtools } from 'zustand/middleware';
import { isDev } from './env';

interface LoadingState {
  isLoading: boolean;
  error: string | null;
}

type DevToolsMiddleware = typeof devtools;

export const devtoolsInDev: DevToolsMiddleware = (
  isDev
    ? devtools
    : ((creator: unknown) => creator) as DevToolsMiddleware
);

export function setLoading<T extends LoadingState>(state: T): void {
  state.isLoading = true;
  state.error = null;
}

export function setError<T extends LoadingState>(state: T, error: string | null): void {
  state.error = error;
  state.isLoading = false;
}

export function clearLoadingAndError<T extends LoadingState>(state: T): void {
  state.isLoading = false;
  state.error = null;
}

interface Identifiable {
  id: string | number;
}

export function findAndUpdate<T extends Identifiable>(
  items: T[],
  id: string | number,
  updates: Partial<T>
): boolean {
  const index = items.findIndex((item) => item.id === id);
  if (index >= 0) {
    items[index] = { ...items[index], ...updates };
    return true;
  }
  return false;
}

export function findAndRemove<T extends Identifiable>(
  items: T[],
  id: string | number
): T | null {
  const index = items.findIndex((item) => item.id === id);
  if (index >= 0) {
    const [removed] = items.splice(index, 1);
    return removed;
  }
  return null;
}

interface GraphQLErrorLike {
  message?: string;
  networkError?: { message?: string };
  graphQLErrors?: Array<{ message?: string }>;
}

export function handleStoreError(error: unknown, fallbackMessage: string): string {
  if (typeof error === 'string') {
    return error;
  }
  if (error instanceof Error) {
    return error.message;
  }
  const err = error as GraphQLErrorLike;
  if (err.message) {
    return err.message;
  }
  if (err.networkError?.message) {
    return err.networkError.message;
  }
  if (err.graphQLErrors?.length && err.graphQLErrors[0].message) {
    return err.graphQLErrors[0].message;
  }
  return fallbackMessage;
}
