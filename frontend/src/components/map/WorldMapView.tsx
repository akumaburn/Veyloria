import { useCallback, useContext, useEffect, useState, lazy, Suspense, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { PanelToolbarContext } from '@/components/layout/PanelToolbarContext';
import { Box, Grid3X3, Pencil, Wand2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/authStore';
import { useWorldMaps, useLocationDiscovered } from './hooks/useMapData';
import WorldMap2D from './WorldMap2D';
import MapEditor from './MapEditor';
import MapGenerator from './MapGenerator';
import type { MapLocation } from '@/graphql/types';

const WorldMap3D = lazy(() => import('./WorldMap3D'));

export type WorldMapViewId = '3d' | '2d' | 'editor' | 'generate';

const VIEWS: { id: WorldMapViewId; label: string; icon: ReactNode; narratorOnly: boolean }[] = [
  { id: '3d', label: '3D View', icon: <Box size={16} aria-hidden="true" />, narratorOnly: false },
  { id: '2d', label: '2D View', icon: <Grid3X3 size={16} aria-hidden="true" />, narratorOnly: false },
  { id: 'editor', label: 'Editor', icon: <Pencil size={16} aria-hidden="true" />, narratorOnly: true },
  { id: 'generate', label: 'Generate', icon: <Wand2 size={16} aria-hidden="true" />, narratorOnly: true },
];

interface WorldMapViewProps {
  conversationId: string;
  narratorMode: boolean;
  /** Controlled view, for hosts that keep the choice while this view is unmounted. */
  view?: WorldMapViewId;
  onViewChange?: (view: WorldMapViewId) => void;
  /** Show the text labels on the view buttons from the sm breakpoint up. */
  labelledViews?: boolean;
  className?: string;
}

/**
 * The story's world map: a view switcher (3D, 2D, and the narrator's editor and
 * generator) above the selected view, which fills the remaining height.
 */
export default function WorldMapView({
  conversationId,
  narratorMode,
  view,
  onViewChange,
  labelledViews = false,
  className,
}: WorldMapViewProps) {
  const panelToolbar = useContext(PanelToolbarContext);
  const [ownView, setOwnView] = useState<WorldMapViewId>('3d');
  const activeView = view ?? ownView;
  const setView = useCallback((next: WorldMapViewId) => {
    setOwnView(next);
    onViewChange?.(next);
  }, [onViewChange]);

  const [selectedMapId, setSelectedMapId] = useState<string | null>(null);
  const [selectedLocation, setSelectedLocation] = useState<MapLocation | null>(null);
  const { maps, loading, error, refetch: refetchMaps } = useWorldMaps(conversationId);
  const userId = useAuthStore((s) => s.user?.id);
  const { discoveredLocation } = useLocationDiscovered(conversationId);

  // Discoveries change isDiscovered flags across every view.
  useEffect(() => {
    if (discoveredLocation) refetchMaps();
  }, [discoveredLocation, refetchMaps]);

  useEffect(() => {
    setSelectedMapId(null);
    setSelectedLocation(null);
  }, [conversationId]);

  // The selected map, or the newest one (maps are ordered by createdAt ascending).
  const currentMap = (selectedMapId && maps?.find((m) => m.mapId === selectedMapId)) || maps?.[maps.length - 1];
  const currentMapId = currentMap?.mapId;
  useEffect(() => {
    setSelectedLocation(null);
  }, [currentMapId]);

  const handleGenerated = useCallback((newMapId: string) => {
    setSelectedMapId(newMapId);
    setView('3d');
  }, [setView]);

  const selectedView = !narratorMode && (activeView === 'editor' || activeView === 'generate') ? '3d' : activeView;
  const visibleViews = VIEWS.filter((v) => narratorMode || !v.narratorOnly);

  let content: ReactNode;
  if (error) {
    content = (
      <div role="alert" className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
        <p className="text-sm text-destructive">Could not load the world map.</p>
        <button type="button" className="min-h-11 rounded-lg border border-border px-4 text-sm hover:bg-accent" onClick={() => void refetchMaps()}>Try again</button>
      </div>
    );
  } else if (loading && !currentMap) {
    content = <p role="status" className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading map…</p>;
  } else if (!currentMap) {
    content = narratorMode
      ? <div className="h-full overflow-y-auto"><MapGenerator conversationId={conversationId} onGenerated={handleGenerated} /></div>
      : <p className="p-6 text-center text-sm text-muted-foreground">No world map yet. Continue your adventure to explore the world.</p>;
  } else if (selectedView === 'generate') {
    content = <div className="h-full overflow-y-auto"><MapGenerator conversationId={conversationId} onGenerated={handleGenerated} /></div>;
  } else if (selectedView === 'editor') {
    content = (
      <div className="h-full overflow-y-auto p-3 sm:p-4">
        <MapEditor key={currentMap.mapId} mapId={currentMap.mapId} conversationId={conversationId} />
      </div>
    );
  } else if (selectedView === '2d') {
    content = <WorldMap2D key={currentMap.mapId} conversationId={conversationId} mapId={currentMap.mapId} userId={userId} selectedLocation={selectedLocation} onSelectLocation={setSelectedLocation} />;
  } else {
    content = (
      <Suspense fallback={<p role="status" className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading 3D viewer…</p>}>
        <WorldMap3D
          key={currentMap.mapId}
          map={currentMap}
          conversationId={conversationId}
          userId={userId || ''}
          selectedLocation={selectedLocation}
          onSelectLocation={setSelectedLocation}
          onDataChanged={refetchMaps}
        />
      </Suspense>
    );
  }

  const viewButtons = currentMap && (
    <div role="group" aria-label="World map view" className="flex shrink-0 gap-0.5">
      {visibleViews.map((v) => (
        <button
          key={v.id}
          type="button"
          aria-pressed={selectedView === v.id}
          onClick={() => setView(v.id)}
          aria-label={v.label}
          title={v.label}
          className={cn(
            'flex min-h-10 min-w-10 items-center justify-center gap-1.5 rounded-md px-2 text-sm font-medium transition-colors md:min-h-8 md:min-w-8',
            labelledViews && 'sm:px-3',
            selectedView === v.id ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-accent hover:text-foreground',
          )}
        >
          {v.icon}
          {labelledViews && <span className="hidden sm:inline">{v.label}</span>}
        </button>
      ))}
    </div>
  );

  return (
    <div className={cn('flex h-full min-h-0 flex-col', className)}>
      {/* Inside a panel the view switch joins the panel's header bar;
          standalone it gets a slim bar of its own with the map's name. */}
      {panelToolbar
        ? viewButtons && createPortal(viewButtons, panelToolbar)
        : currentMap && (
          <div className="flex shrink-0 items-center gap-2 border-b border-border/50 px-2 py-1">
            {viewButtons}
            <p className="ml-auto min-w-0 truncate text-xs text-muted-foreground">
              {currentMap.name}{currentMap.mapScale ? ` · ${currentMap.mapScale}` : ''}
            </p>
          </div>
        )}
      <div className="relative min-h-0 flex-1 overflow-hidden">{content}</div>
    </div>
  );
}
