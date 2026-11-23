import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import type { AlignmentData, CharacterAlignment } from './alignment';
import { CharacterAlignmentModal } from './CharacterAlignmentModal';

const mocks = vi.hoisted(() => ({
  data: undefined as AlignmentData | undefined,
  error: undefined as Error | undefined,
  refetch: vi.fn(),
  set: vi.fn(),
  adjust: vi.fn(),
}));

vi.mock('@apollo/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@apollo/client')>()),
  useQuery: () => ({
    data: mocks.data,
    loading: false,
    error: mocks.error,
    refetch: mocks.refetch,
  }),
  useMutation: (document: { definitions?: Array<{ name?: { value?: string } }> }) => {
    const name = document?.definitions?.[0]?.name?.value;
    return name === 'SetCharacterAlignment'
      ? [mocks.set, { loading: false }]
      : [mocks.adjust, { loading: false }];
  },
}));

const mayaId = '11111111-1111-4111-8111-111111111111';
const record: CharacterAlignment = {
  alignmentId: 'alignment-1',
  characterId: mayaId,
  name: 'Maya',
  lawfulness: 0,
  goodness: 0,
  alignment: 'TRUE_NEUTRAL',
  updatedAt: '2026-11-13T12:00:00',
  events: [],
};
const data: AlignmentData = {
  relationshipActors: [{ characterId: mayaId, name: 'Maya' }],
  characterAlignments: [record],
};

beforeEach(() => {
  mocks.data = data;
  mocks.error = undefined;
  mocks.refetch.mockReset();
  mocks.set.mockReset();
  mocks.adjust.mockReset();
});

it('sets a chart alignment with a stable request id and reports the saved result', async () => {
  const user = userEvent.setup();
  mocks.set.mockImplementation(async ({ variables }: { variables: Record<string, unknown> }) => ({
    data: {
      setCharacterAlignment: {
        ...record,
        lawfulness: variables.lawfulness,
        goodness: variables.goodness,
        alignment: 'LAWFUL_GOOD',
      },
    },
  }));
  render(<CharacterAlignmentModal characterId={mayaId} onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: /Set alignment to Lawful Good/ }));
  await waitFor(() => expect(mocks.set).toHaveBeenCalledOnce());
  const variables = mocks.set.mock.calls[0][0].variables;
  expect(variables).toMatchObject({
    characterId: mayaId,
    lawfulness: 70,
    goodness: 70,
    reason: null,
  });
  expect(variables.requestId).toMatch(/^[0-9a-f-]{36}$/);
  expect(
    await screen.findByText(/Maya: Lawful Good, order \+70, morality \+70\. Saved\./),
  ).toBeInTheDocument();
});

it('adjusts the user by ten points when no character is selected', async () => {
  const user = userEvent.setup();
  mocks.adjust.mockImplementation(
    async ({ variables }: { variables: Record<string, unknown> }) => ({
      data: {
        adjustCharacterAlignment: {
          ...record,
          alignmentId: 'alignment-user',
          characterId: null,
          name: 'You',
          goodness: variables.goodnessDelta,
          alignment: 'TRUE_NEUTRAL',
        },
      },
    }),
  );
  render(<CharacterAlignmentModal onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: 'Move morality 10 points toward Good' }));
  await waitFor(() => expect(mocks.adjust).toHaveBeenCalledOnce());
  expect(mocks.adjust.mock.calls[0][0].variables).toMatchObject({
    characterId: null,
    lawfulnessDelta: 0,
    goodnessDelta: 10,
    reason: null,
  });
});

it('confirms before resetting an alignment to zero', async () => {
  const user = userEvent.setup();
  mocks.data = {
    ...data,
    characterAlignments: [
      {
        ...record,
        lawfulness: 70,
        goodness: 70,
        alignment: 'LAWFUL_GOOD',
        events: [
          {
            eventId: 'event-1',
            action: 'SET',
            lawDelta: 70,
            goodDelta: 70,
            previousLawfulness: 0,
            previousGoodness: 0,
            lawfulness: 70,
            goodness: 70,
            alignment: 'LAWFUL_GOOD',
            reason: 'A promise kept',
            createdAt: '2026-11-13T12:00:00',
          },
        ],
      },
    ],
  };
  mocks.set.mockResolvedValue({ data: { setCharacterAlignment: record } });
  render(<CharacterAlignmentModal characterId={mayaId} onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: /Reset to True Neutral/ }));
  expect(screen.getByText(/Reset this alignment to 0 order and 0 morality/)).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Reset alignment' }));
  await waitFor(() => expect(mocks.set).toHaveBeenCalledOnce());
  expect(mocks.set.mock.calls[0][0].variables).toMatchObject({
    characterId: mayaId,
    lawfulness: 0,
    goodness: 0,
  });
});

it('keeps the current chart visible when the saved change fails', async () => {
  const user = userEvent.setup();
  mocks.set.mockRejectedValue(new Error('Unable to save this alignment change.'));
  render(<CharacterAlignmentModal characterId={mayaId} onClose={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: /Set alignment to Lawful Good/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Unable to save this alignment change.',
  );
  expect(screen.getByRole('button', { name: /Set alignment to Chaotic Evil/ })).toBeEnabled();
});
