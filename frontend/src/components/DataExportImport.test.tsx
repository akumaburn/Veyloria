import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MockedProvider } from '@apollo/client/testing';
import { it, expect, vi, afterEach } from 'vitest';
import { DataExportImport } from './DataExportImport';
import { EXPORT_DATA_QUERY } from '@/graphql/operations';

afterEach(() => vi.unstubAllGlobals());

it('exports selected sections using only the supported GraphQL filter fields', async () => {
  const createObjectURL = vi.fn(() => 'blob:export');
  vi.stubGlobal(
    'URL',
    Object.assign(class extends URL {}, { createObjectURL, revokeObjectURL: vi.fn() }),
  );
  const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  render(
    <MockedProvider
      mocks={[
        {
          request: {
            query: EXPORT_DATA_QUERY,
            variables: {
              filter: { conversations: true, messages: false, characters: true, settings: true },
            },
          },
          result: {
            data: {
              exportData: {
                conversations: [],
                messages: [],
                characters: [],
                settings: null,
                exportDate: '2026-11-23T00:00:00Z',
                version: '1.0',
              },
            },
          },
        },
      ]}
    >
      <DataExportImport />
    </MockedProvider>,
  );
  fireEvent.click(screen.getByRole('checkbox', { name: 'Messages' }));
  fireEvent.click(screen.getByRole('button', { name: 'Export Selected Data' }));
  await waitFor(() => expect(download).toHaveBeenCalledOnce());
  expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
});
