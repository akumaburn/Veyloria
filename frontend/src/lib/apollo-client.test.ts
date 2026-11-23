import { afterEach, expect, it, vi } from 'vitest';
import { gql } from '@apollo/client';

// Exercise the real error link and refresh request, including a session switch
// while the refresh response is still outstanding.
it('keeps a newer login when an earlier session refresh completes', async () => {
  vi.resetModules();
  const tokens = new Map<string, string>();
  vi.mocked(localStorage.getItem).mockImplementation(key => tokens.get(key) ?? null);
  vi.mocked(localStorage.setItem).mockImplementation((key, value) => { tokens.set(key, value); });
  vi.mocked(localStorage.removeItem).mockImplementation(key => { tokens.delete(key); });
  let finishRefresh!: (response: Response) => void;
  const response = (body: unknown) => new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  });
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(response({ errors: [{ message: 'Token expired', extensions: { code: 'UNAUTHENTICATED' } }] }))
    .mockImplementationOnce(() => new Promise<Response>(resolve => { finishRefresh = resolve; }));
  vi.stubGlobal('fetch', fetchMock);
  const { apolloClient, setTokens, setAuthLogoutCallback } = await import('./apollo-client');
  const logout = vi.fn();
  setAuthLogoutCallback(logout);
  setTokens('old-access', 'old-refresh');
  const request = apolloClient.query({ query: gql`query SessionRace { me { id } }`, errorPolicy: 'none' })
    .catch(error => error);
  await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  setTokens('new-access', 'new-refresh');
  finishRefresh(response({ data: { refreshToken: { accessToken: 'stale-access', refreshToken: 'stale-refresh' } } }));
  expect(await request).toBeInstanceOf(Error);
  expect(tokens.get('authToken')).toBe('new-access');
  expect(tokens.get('refreshToken')).toBe('new-refresh');
  expect(logout).not.toHaveBeenCalled();
  apolloClient.stop();
});

afterEach(() => vi.unstubAllGlobals());
