import { act } from '@testing-library/react';
import { useNarratorModeStore } from '@/stores/narratorModeStore';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { JourneyState, JourneyStepResult, MapLocation } from '@/graphql/types';
import LocationInfoPanel from './LocationInfoPanel';

const hooks = vi.hoisted(() => ({
  active: null as JourneyState | null,
  step: undefined as JourneyStepResult | undefined,
  begin: vi.fn(), changed: vi.fn(),
}));
vi.mock('../hooks/useMapData', () => ({
  useRemoveMapLocation: () => ({ removeMapLocation: vi.fn() }),
  useBeginJourney: () => ({ beginJourney: hooks.begin, loading: false }),
  useCancelJourney: () => ({ cancelJourney: vi.fn() }),
  useActiveJourney: () => ({ activeJourney: hooks.active }),
  useTravelJourneyStep: () => ({ journeyStep: hooks.step }),
  useTravelPreview: () => ({ fetchTravelPreview: vi.fn() }),
}));
const location: MapLocation = { locationId: 'target', mapId: 'map', name: 'Castle', isDiscovered: true, x: 0, y: 0, z: 0 };
function Panel() {
  return <LocationInfoPanel selectedLocation={location} onSelectLocation={() => {}} mapId="map"
    conversationId="conversation" userId="user" entityPositions={[]} onDataChanged={hooks.changed} />;
}
const step = (n: number, overrides = {}): JourneyStepResult => ({ journeyId: 'current', stepNumber: n, totalSteps: 5,
  x: 0, y: 0, z: 0, journeyComplete: false, journeyInterrupted: false, narration: `Step ${n}`, ...overrides });
beforeEach(() => {
  useNarratorModeStore.setState({ enabled: {} });
  hooks.active = { journeyId: 'current', mapId: 'map', conversationId: 'conversation', userId: 'user',
    fromLocationId: 'start', toLocationId: 'target', toLocationName: 'Castle', status: 'TRAVELING', totalSteps: 5, currentStep: 3,
    stepResults: [step(2)] };
  hooks.step = undefined;
});
describe('journey state', () => {
  it('ignores events from another journey and uses server step numbers for progress', () => {
    const { rerender } = render(<Panel />);
    expect(screen.getByText('Step 3/5')).toBeInTheDocument();
    hooks.step = step(5, { journeyId: 'old', journeyComplete: true, narration: 'Stale arrival' });
    rerender(<Panel />);
    expect(screen.queryByText('Stale arrival')).not.toBeInTheDocument();
    expect(hooks.changed).not.toHaveBeenCalled();
    hooks.step = step(2, { journeyInterrupted: true, interruptReason: 'Cancelled', narration: 'Cancelled here' });
    rerender(<Panel />);
    expect(screen.getByText('Cancelled here')).toBeInTheDocument();
    expect(screen.queryByText('Step 2')).not.toBeInTheDocument();
    expect(hooks.changed).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByTitle('Dismiss'));
    expect(screen.queryByText('Cancelled here')).not.toBeInTheDocument();
  });

  it('does not restore a journey for another map', () => {
    hooks.active!.mapId = 'another-map';
    render(<Panel />);
    expect(screen.queryByText('Step 3/5')).not.toBeInTheDocument();
  });
});

it('keeps location deletion behind narrator mode and updates an already open panel', () => {
  render(<Panel />);
  expect(screen.queryByRole('button', { name: 'Delete location' })).not.toBeInTheDocument();
  act(() => useNarratorModeStore.getState().setEnabled('conversation', true));
  expect(screen.getByRole('button', { name: 'Delete location' })).toBeInTheDocument();
  act(() => useNarratorModeStore.getState().setEnabled('conversation', false));
  expect(screen.queryByRole('button', { name: 'Delete location' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Close location details' })).toBeInTheDocument();
});
