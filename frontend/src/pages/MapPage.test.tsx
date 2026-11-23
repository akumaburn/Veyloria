import { act } from '@testing-library/react';
import { useNarratorModeStore } from '@/stores/narratorModeStore';
import { render, screen, fireEvent } from '@testing-library/react';
import { beforeEach, describe, it, expect, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MapPage from './MapPage';
vi.mock('@/components/map/hooks/useMapData', () => ({
  useWorldMaps: () => ({ maps: [{ mapId: 'world', name: 'Story world' }], loading: false, refetch: vi.fn() }),
  useLocationDiscovered: () => ({ discoveredLocation: null }),
}));
vi.mock('@/stores/authStore', () => ({ useAuthStore: (selector: any) => selector({ user: { id: 'player' } }) }));
vi.mock('@/components/roleplay/JourneyNotice', () => ({ default: () => null }));
vi.mock('@/components/roleplay/EncounterNotice', () => ({ default: () => null }));
vi.mock('@/components/map/LocalMapPanel', () => ({ default: ({ expanded }: any) => <div>Local layout {expanded ? 'expanded' : 'compact'}</div> }));
vi.mock('@/components/map/WorldMap2D', () => ({ default: () => <div>Global flat map</div> }));
vi.mock('@/components/map/WorldMap3D', () => ({ default: () => <div>Global terrain</div> }));
vi.mock('@/components/map/MapEditor', () => ({ default: () => <p>World editing</p> }));
vi.mock('@/components/map/MapGenerator', () => ({ default: () => null }));
beforeEach(() => useNarratorModeStore.setState({ enabled: {} }));
describe('map scopes', () => {
  it('opens the local 2D map and preserves the selected global view across scope changes', async () => {
    render(<MemoryRouter initialEntries={['/map/conversation']}><Routes><Route path="/map/:conversationId" element={<MapPage />} /><Route path="/chat/:conversationId" element={<p>Conversation opened</p>} /></Routes></MemoryRouter>);
    expect(screen.getByText('Local layout expanded')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to conversation' })).toHaveAttribute('href', '/chat/conversation');
    expect(screen.queryByRole('button', { name: '3D View' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Global' }));
    expect(await screen.findByText('Global terrain')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Editor' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Generate' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '2D View' }));
    expect(screen.getByText('Global flat map')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Local' }));
    expect(screen.getByText('Local layout expanded')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Global' }));
    expect(screen.getByText('Global flat map')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '2D View' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('link', { name: 'Back to conversation' }));
    expect(screen.getByText('Conversation opened')).toBeInTheDocument();
  });
});

it('shares narrator mode per conversation and removes an open editor immediately when disabled', async () => {
  useNarratorModeStore.getState().setEnabled('other-story', true);
  render(<MemoryRouter initialEntries={['/map/conversation']}><Routes><Route path="/map/:conversationId" element={<MapPage />} /></Routes></MemoryRouter>);
  expect(screen.getByRole('checkbox', { name: 'Narrator mode' })).not.toBeChecked();
  act(() => useNarratorModeStore.getState().setEnabled('conversation', true));
  expect(screen.getByRole('checkbox', { name: 'Narrator mode' })).toBeChecked();
  fireEvent.click(screen.getByRole('button', { name: 'Global' }));
  fireEvent.click(screen.getByRole('button', { name: 'Editor' }));
  expect(screen.getByText('World editing')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('checkbox', { name: 'Narrator mode' }));
  expect(screen.queryByText('World editing')).not.toBeInTheDocument();
  expect(await screen.findByText('Global terrain')).toBeInTheDocument();
  expect(useNarratorModeStore.getState().enabled.conversation).toBe(false);
});
