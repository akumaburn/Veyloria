import { useNarratorMode } from '@/stores/narratorModeStore';
import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';
import {
  useRemoveMapLocation,
  useBeginJourney,
  useCancelJourney,
  useActiveJourney,
  useTravelJourneyStep,
  useTravelPreview,
} from '../hooks/useMapData';
import { LOCATION_COLORS } from '../constants';
import TravelJourneyOverlay from './TravelJourneyOverlay';
import type { MapLocation, MapCharacterPosition, JourneyStepResult } from '@/graphql/types';
import { X, Trash2, Info, Navigation, Loader2 } from 'lucide-react';
import { toast } from 'react-hot-toast';

interface LocationInfoPanelProps {
  selectedLocation: MapLocation | null;
  onSelectLocation: (loc: MapLocation | null) => void;
  mapId: string;
  conversationId: string;
  userId?: string;
  entityPositions: MapCharacterPosition[];
  /** Called when map data may have changed (journey complete, discovery, etc.) */
  onDataChanged?: () => void;
}

/**
 * Self-contained location info panel with travel/journey support.
 * Renders two absolutely-positioned overlays:
 *  - Bottom-left: selected location details + travel controls
 *  - Bottom-right: journey progress overlay (when traveling)
 *
 * Must be placed inside a position:relative container.
 */
export default function LocationInfoPanel({
  selectedLocation,
  onSelectLocation,
  mapId,
  conversationId,
  userId,
  entityPositions,
  onDataChanged,
}: LocationInfoPanelProps) {
  const narratorMode = useNarratorMode(conversationId);
  /* ─── travel hooks ─── */
  const { removeMapLocation, loading: removing } = useRemoveMapLocation();
  const { beginJourney, loading: beginningJourney } = useBeginJourney();
  const { cancelJourney, loading: cancellingJourney } = useCancelJourney();
  const { activeJourney, loading: loadingJourney } = useActiveJourney(conversationId);
  const { journeyStep } = useTravelJourneyStep(conversationId);
  const { fetchTravelPreview, travelPreview, loading: loadingPreview, error: previewError } = useTravelPreview();

  /* ─── journey state ─── */
  const [journeySteps, setJourneySteps] = useState<JourneyStepResult[]>([]);
  const [journeyMeta, setJourneyMeta] = useState<{
    destinationName: string;
    totalSteps: number;
    journeyId: string;
  } | null>(null);

  // Use a ref for onDataChanged so the step effect doesn't re-trigger when the
  // callback identity changes (it only needs the latest reference when calling).
  const onDataChangedRef = useRef(onDataChanged);
  onDataChangedRef.current = onDataChanged;

  const dismissedJourneys = useRef(new Set<string>());
  const processedSteps = useRef(new Set<JourneyStepResult>());

  useEffect(() => {
    setJourneyMeta(null);
    setJourneySteps([]);
    dismissedJourneys.current.clear();
    processedSteps.current.clear();
  }, [conversationId, mapId]);

  // Restore only the displayed map's journey, including steps missed offline.
  useEffect(() => {
    if (!activeJourney || activeJourney.mapId !== mapId ||
        !['TRAVELING', 'PAUSED'].includes(activeJourney.status) ||
        dismissedJourneys.current.has(activeJourney.journeyId)) return;
    if (journeyMeta?.journeyId !== activeJourney.journeyId) {
      setJourneyMeta({
        destinationName: activeJourney.toLocationName ?? 'Destination',
        totalSteps: activeJourney.totalSteps ?? 1,
        journeyId: activeJourney.journeyId,
      });
    }
    setJourneySteps(activeJourney.stepResults ?? []);
  }, [activeJourney, journeyMeta?.journeyId, mapId]);

  useEffect(() => {
    if (!journeyStep || journeyStep.journeyId !== journeyMeta?.journeyId ||
        processedSteps.current.has(journeyStep)) return;
    processedSteps.current.add(journeyStep);
    setJourneySteps(prev => [
      ...prev.filter(step => step.stepNumber !== journeyStep.stepNumber),
      journeyStep,
    ].sort((a, b) => a.stepNumber - b.stepNumber));
    if (journeyStep.journeyComplete) {
      toast.success(`Arrived at ${journeyMeta.destinationName}!`);
      onDataChangedRef.current?.();
    }
    if (journeyStep.journeyInterrupted) {
      toast.error(`Journey interrupted: ${journeyStep.interruptReason || 'unknown reason'}`);
      onDataChangedRef.current?.();
    }
  }, [journeyStep, journeyMeta]);

  /* ─── derived values ─── */
  const userPos = useMemo(
    () => entityPositions.find(p => !!userId && p.userId === userId),
    [entityPositions, userId],
  );

  const distanceFromUser = useMemo(() => {
    if (!selectedLocation || !userPos || userPos.x == null || userPos.y == null) return null;
    const dx = selectedLocation.x - userPos.x;
    const dy = selectedLocation.y - userPos.y;
    const dz = selectedLocation.z - (userPos.z ?? 0);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }, [selectedLocation, userPos]);

  const isAtLocation = selectedLocation ? userPos?.locationId === selectedLocation.locationId : false;
  const journeyFinished = journeySteps.some(s => s.journeyComplete || s.journeyInterrupted);
  const isTraveling = !!journeyMeta && !journeyFinished;
  const isDiscovered = selectedLocation?.isDiscovered;

  /* ─── handlers ─── */
  const handleBeginJourney = useCallback(async () => {
    if (!mapId || !selectedLocation || beginningJourney || loadingJourney || isTraveling || activeJourney) return;
    try {
      const result = await beginJourney(mapId, selectedLocation.locationId);
      const journey = result.data?.beginJourney;
      if (journey) {
        setJourneyMeta({
          destinationName: journey.toLocationName ?? selectedLocation.name,
          totalSteps: journey.totalSteps ?? 1,
          journeyId: journey.journeyId,
        });
        setJourneySteps([]);
      }
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : 'Failed to begin journey');
    }
  }, [mapId, selectedLocation, beginJourney, beginningJourney, loadingJourney, isTraveling]);

  const handleCancelJourney = useCallback(async () => {
    try {
      await cancelJourney(conversationId);
      if (journeyMeta) dismissedJourneys.current.add(journeyMeta.journeyId);
      setJourneyMeta(null);
      setJourneySteps([]);
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : 'Failed to cancel journey');
    }
  }, [conversationId, cancelJourney, journeyMeta]);

  const handleDismissJourney = useCallback(() => {
    if (journeyMeta) dismissedJourneys.current.add(journeyMeta.journeyId);
    setJourneyMeta(null);
    setJourneySteps([]);
  }, [journeyMeta]);

  const handleDelete = useCallback(async () => {
    if (!narratorMode || !selectedLocation) return;
    try {
      await removeMapLocation(selectedLocation.locationId);
      onSelectLocation(null);
    } catch (err: unknown) {
      console.error('Failed to delete location:', err);
      toast.error(err instanceof Error ? err.message : 'Failed to delete location');
    }
  }, [narratorMode, selectedLocation, removeMapLocation, onSelectLocation]);

  /* ─── render ─── */
  return (
    <>
      {/* Journey overlay — bottom right */}
      {journeyMeta && (
        <div className="absolute bottom-4 right-4 z-20 max-w-[calc(100%-2rem)]">
          <TravelJourneyOverlay
            conversationId={conversationId}
            destinationName={journeyMeta.destinationName}
            totalSteps={journeyMeta.totalSteps}
            steps={journeySteps}
            isComplete={journeySteps.some(s => s.journeyComplete)}
            isInterrupted={journeySteps.some(s => s.journeyInterrupted)}
            interruptReason={journeySteps.find(s => s.journeyInterrupted)?.interruptReason}
            onCancel={handleCancelJourney}
            onDismiss={handleDismissJourney}
            cancelling={cancellingJourney}
          />
        </div>
      )}

      {/* Selected location info — bottom left */}
      {selectedLocation && (
        <div className="absolute bottom-4 left-4 max-h-[60%] w-[calc(100%-2rem)] max-w-xs overflow-y-auto rounded-lg bg-muted/95 p-4 backdrop-blur-sm border border-border/60 z-10">
          <div className="flex items-start justify-between gap-2">
            <div>
              <h4 className="text-sm font-semibold text-foreground">
                {isDiscovered ? selectedLocation.name : 'Unknown Location'}
              </h4>
              {isDiscovered && selectedLocation.locationType && (
                <span
                  className="inline-block mt-1 rounded-full px-2 py-0.5 text-xs capitalize"
                  style={{
                    backgroundColor: `${LOCATION_COLORS[selectedLocation.locationType] || '#9ca3af'}20`,
                    color: LOCATION_COLORS[selectedLocation.locationType] || '#9ca3af',
                  }}
                >
                  {selectedLocation.locationType}
                </span>
              )}
            </div>
            <div className="flex items-center gap-1">
              {narratorMode && <button
                aria-label="Delete location"
                onClick={handleDelete}
                disabled={removing}
                className={cn(
                  'rounded p-1 text-destructive hover:bg-destructive/10 transition-colors',
                  removing && 'opacity-50 pointer-events-none',
                )}
              >
                {removing ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />}
              </button>}
              <button
                aria-label="Close location details"
                onClick={() => onSelectLocation(null)}
                className="rounded p-1 text-muted-foreground hover:text-foreground hover:bg-accent/50 transition-colors"
              >
                <X size={14} />
              </button>
            </div>
          </div>

          {isDiscovered && selectedLocation.description && (
            <p className="mt-2 text-xs text-muted-foreground leading-relaxed">{selectedLocation.description}</p>
          )}

          <div className="mt-2 flex items-center gap-3 text-xs text-muted-foreground">
            <span>({selectedLocation.x.toFixed(1)}, {selectedLocation.y.toFixed(1)}, {selectedLocation.z.toFixed(1)})</span>
            {distanceFromUser !== null && (
              <span className="flex items-center gap-1">
                <Info size={10} />
                {distanceFromUser.toFixed(1)} units away
              </span>
            )}
          </div>

          {travelPreview && travelPreview.toLocationId === selectedLocation.locationId && (
            <div className="mt-2 rounded bg-accent/50 px-2 py-1.5 text-xs text-foreground space-y-0.5">
              <div>Travel time: <span className="font-medium">{travelPreview.timePeriodsRequired} period{travelPreview.timePeriodsRequired > 1 ? 's' : ''}</span></div>
              {travelPreview.usesRoad && <div>Road: <span className="text-success">{travelPreview.roadType || 'trail'}</span></div>}
              {travelPreview.dominantBiome && <div>Terrain: <span className="capitalize">{travelPreview.dominantBiome.toLowerCase().replace('_', ' ')}</span></div>}
            </div>
          )}

          {previewError && <p role="alert" className="mt-2 text-xs text-destructive">Could not load the travel preview. Try again.</p>}

          {!isAtLocation && (
            <div className="mt-3 flex gap-2">
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium',
                  'bg-primary text-primary-foreground hover:bg-primary-hover transition-colors',
                  (beginningJourney || isTraveling) && 'opacity-60 pointer-events-none',
                )}
                disabled={beginningJourney || isTraveling || loadingJourney || !userPos || !!activeJourney}
                onClick={handleBeginJourney}
              >
                {(beginningJourney || isTraveling) ? <Loader2 size={12} className="animate-spin" /> : <Navigation size={12} />}
                {isTraveling ? 'Traveling...' : 'Travel Here'}
              </button>
              {mapId && userPos?.locationId && !isTraveling && (
                <button
                  className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs bg-accent text-foreground hover:bg-border transition-colors"
                  disabled={loadingPreview}
                  onClick={() => void fetchTravelPreview(mapId, userPos.locationId!, selectedLocation.locationId).catch(() => {})}
                >
                  <Info size={10} />
                  {loadingPreview ? 'Loading…' : 'Preview'}
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </>
  );
}
