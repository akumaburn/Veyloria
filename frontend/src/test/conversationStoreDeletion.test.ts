import { InMemoryCache, gql } from '@apollo/client';
import { beforeEach, expect, it, vi } from 'vitest';
import type { Conversation } from '@/graphql/types';

const client = vi.hoisted(() => ({
  mutate: vi.fn(),
  query: vi.fn(),
  cache: null as unknown as InMemoryCache,
}));
vi.mock('@/lib/apollo-client', () => ({ apolloClient: client }));
import { useConversationStore } from '@/stores/conversationStore';

const query = gql`
  query TestLists($filter: String) {
    conversations(filter: $filter) {
      items {
        id
        title
      }
      totalCount
    }
  }
`;
const detail = gql`
  query TestDetail($id: ID!) {
    conversation(id: $id) {
      id
      title
    }
  }
`;
const current = { __typename: 'Conversation', id: 'current', title: 'Current' };
const other = { __typename: 'Conversation', id: 'other', title: 'Other' };

beforeEach(() => {
  client.cache = new InMemoryCache({ typePolicies: { Conversation: { keyFields: ['id'] } } });
  client.cache.writeQuery({
    query,
    variables: {},
    data: { conversations: { items: [current, other], totalCount: 2 } },
  });
  client.cache.writeQuery({
    query,
    variables: { filter: 'other' },
    data: { conversations: { items: [other], totalCount: 1 } },
  });
  client.cache.writeQuery({
    query: detail,
    variables: { id: 'current' },
    data: { conversation: current },
  });
  client.mutate.mockResolvedValue({ data: { deleteConversation: true } });
  client.query.mockResolvedValue({
    data: { conversations: { items: [current, other], hasMore: false } },
  });
  useConversationStore.setState({
    conversations: [current, other] as unknown as Conversation[],
    selectedConversationId: 'current',
    hasLoaded: true,
    deletions: {},
  });
});

it('removes references before eviction and preserves counts for unaffected list variants', async () => {
  await useConversationStore.getState().deleteConversation('current');
  expect(client.cache.readQuery({ query, variables: {} })).toEqual({
    conversations: { items: [other], totalCount: 1 },
  });
  expect(client.cache.readQuery({ query, variables: { filter: 'other' } })).toEqual({
    conversations: { items: [other], totalCount: 1 },
  });
  expect(client.cache.readQuery({ query: detail, variables: { id: 'current' } })).toBeNull();
  expect(useConversationStore.getState().selectedConversationId).toBeNull();
});

it('restores the list and selection when the server rejects deletion', async () => {
  client.mutate.mockResolvedValue({ data: { deleteConversation: false } });
  await expect(useConversationStore.getState().deleteConversation('current')).rejects.toThrow();
  expect(useConversationStore.getState().conversations.map((item) => item.id)).toEqual([
    'current',
    'other',
  ]);
  expect(useConversationStore.getState().selectedConversationId).toBe('current');
});

it('keeps a newly selected conversation when a pending deletion fails', async () => {
  let reject!: (error: Error) => void;
  client.mutate.mockImplementation(
    () =>
      new Promise((_, fail) => {
        reject = fail;
      }),
  );
  const pending = useConversationStore.getState().deleteConversation('current');
  useConversationStore.getState().setSelectedConversation('other');
  reject(new Error('Request failed'));
  await expect(pending).rejects.toThrow();
  expect(useConversationStore.getState().selectedConversationId).toBe('other');
});

it('does not put a deleted row back when a list fetch overlaps the delete', async () => {
  let finishDelete!: (value: unknown) => void;
  client.mutate.mockImplementation(
    () => new Promise((resolve) => { finishDelete = resolve; }),
  );

  const deletion = useConversationStore.getState().deleteConversation('current');
  // The server answers the list before it commits the delete, so the row is
  // still there. Applying that page is what left the row on screen.
  client.query.mockResolvedValue({
    data: { conversations: { items: [current, other], hasMore: false } },
  });
  await useConversationStore.getState().fetchConversations();

  expect(useConversationStore.getState().conversations.map((item) => item.id)).toEqual(['other']);

  finishDelete({ data: { deleteConversation: true } });
  await deletion;
  expect(useConversationStore.getState().conversations.map((item) => item.id)).toEqual(['other']);
});

it('rejects a list result that was already in flight when the delete was issued', async () => {
  let finishList!: (value: unknown) => void;
  client.query.mockImplementation(
    () => new Promise((resolve) => { finishList = resolve; }),
  );

  const listing = useConversationStore.getState().fetchConversations();
  // The delete is issued after the fetch began but answers first.
  await useConversationStore.getState().deleteConversation('current');
  finishList({ data: { conversations: { items: [current, other], hasMore: false } } });
  await listing;

  expect(useConversationStore.getState().conversations.map((item) => item.id)).toEqual(['other']);
});

it('accepts a later fetch once the delete has settled', async () => {
  await useConversationStore.getState().deleteConversation('current');
  client.query.mockResolvedValue({
    data: { conversations: { items: [current, other], hasMore: false } },
  });

  await useConversationStore.getState().fetchConversations();

  // A fetch that starts after the delete settles is reporting server truth,
  // and must not be filtered forever.
  expect(useConversationStore.getState().conversations.map((item) => item.id)).toEqual([
    'current',
    'other',
  ]);
});

it('does not restore a server-deleted row if local cache maintenance fails', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(client.cache, 'batch').mockImplementation(() => {
    throw new Error('Cache failure');
  });
  await expect(
    useConversationStore.getState().deleteConversation('current'),
  ).resolves.toBeUndefined();
  expect(useConversationStore.getState().conversations.map((item) => item.id)).toEqual(['other']);
});
