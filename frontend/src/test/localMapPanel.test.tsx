import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import LocalMapPanel from '@/components/map/LocalMapPanel';

const localMap = {
  mapId: 'map-1',
  locationId: 'loc-1',
  locationName: 'Blackmoor Castle',
  locationType: 'castle',
  currentAreaId: 'hall',
  currentAreaName: 'Great Hall',
  visitedAreaIds: ['hall', 'kitchen'],
  areas: [
    {
      localAreaId: 'hall',
      mapId: 'map-1',
      locationId: 'loc-1',
      name: 'Great Hall',
      description: 'A long hall with a cold hearth.',
      areaType: 'hall',
      x: 50,
      y: 52,
      isGateway: false,
      isEntry: true,
    },
    {
      localAreaId: 'kitchen',
      mapId: 'map-1',
      locationId: 'loc-1',
      name: 'Kitchen',
      description: 'A warm kitchen.',
      areaType: 'kitchen',
      x: 20,
      y: 54,
      isGateway: false,
      isEntry: false,
    },
    {
      localAreaId: 'gate',
      mapId: 'map-1',
      locationId: 'loc-1',
      name: 'Gatehouse',
      description: 'A cold passage under the portcullis.',
      areaType: 'gate',
      x: 50,
      y: 92,
      isGateway: true,
      isEntry: false,
    },
    {
      localAreaId: 'cellar',
      mapId: 'map-1',
      locationId: 'loc-1',
      name: 'Cellar',
      description: 'A cellar nobody has found yet.',
      areaType: 'chamber',
      x: 20,
      y: 90,
      isGateway: false,
      isEntry: false,
    },
  ],
  connections: [
    {
      connectionId: 'c1',
      mapId: 'map-1',
      locationId: 'loc-1',
      fromAreaId: 'hall',
      toAreaId: 'kitchen',
      label: 'service door',
    },
    {
      connectionId: 'c2',
      mapId: 'map-1',
      locationId: 'loc-1',
      fromAreaId: 'hall',
      toAreaId: 'gate',
      label: 'main doors',
    },
  ],
};

vi.mock('@/components/map/hooks/useMapData', () => ({
  useLocalMap: () => ({ localMap, loading: false, error: undefined, refetch: vi.fn() }),
}));

vi.mock('@/hooks/useRoleplay', () => ({
  useWorldStateChanged: () => ({ worldState: null }),
}));

it('shows the current room, its exits, and the gateway', () => {
  render(<LocalMapPanel conversationId="conv-1" />);

  expect(screen.getByText('Blackmoor Castle')).toBeInTheDocument();
  expect(screen.getAllByText('Great Hall').length).toBeGreaterThan(0);
  expect(screen.getAllByText('Kitchen').length).toBeGreaterThan(0);
  expect(screen.getAllByText('service door').length).toBeGreaterThan(0);
  expect(screen.getAllByText('Gatehouse').length).toBeGreaterThan(0);
  // The gatehouse has not been visited, so its passage is unexplored.
  expect(screen.getAllByText('unexplored').length).toBeGreaterThan(0);
  expect(screen.queryByText('main doors')).not.toBeInTheDocument();
  expect(screen.getAllByText('A long hall with a cold hearth.').length).toBeGreaterThan(0);
});

it('hides unvisited areas from the map (fog of war)', () => {
  render(<LocalMapPanel conversationId="conv-1" />);

  expect(screen.queryByText('Cellar')).not.toBeInTheDocument();
});

it('reveals the full layout in narrator mode (no fog)', () => {
  render(<LocalMapPanel conversationId="conv-1" narratorMode />);

  // The cellar has no connection to the current room, so it can only appear
  // as a map label — exactly what the revealed layout must add.
  expect(screen.getByText('Cellar')).toBeInTheDocument();
  expect(screen.getAllByText('Gatehouse').length).toBeGreaterThan(0);
});
