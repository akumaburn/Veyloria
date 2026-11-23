import { useNarratorMode, useNarratorModeStore } from '@/stores/narratorModeStore';
import JourneyNotice from '@/components/roleplay/JourneyNotice';
import EncounterNotice from '@/components/roleplay/EncounterNotice';
import React, { useState, useCallback, useEffect, lazy, Suspense } from 'react';
import { Link, useParams } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { ArrowLeft, Map, Box, Grid3X3, Pencil, Wand2 } from 'lucide-react';
import { useWorldMaps, useLocationDiscovered } from '@/components/map/hooks/useMapData';
import { PageHeader } from '@/components/layout/PageHeader';
import { useAuthStore } from '@/stores/authStore';
import WorldMap2D from '@/components/map/WorldMap2D';
import LocalMapPanel from '@/components/map/LocalMapPanel';
import MapEditor from '@/components/map/MapEditor';
import MapGenerator from '@/components/map/MapGenerator';
import type { MapLocation } from '@/graphql/types';

const WorldMap3D = lazy(() => import('@/components/map/WorldMap3D'));

type TabId = '3d' | '2d' | 'editor' | 'generate';

const MapPage: React.FC = () => {
  const { conversationId } = useParams<{ conversationId?: string }>();
  const narratorMode = useNarratorMode(conversationId);
  const [activeTab, setActiveTab] = useState<TabId>('3d');
  const [scope, setScope] = useState<'local' | 'global'>('local');
  const [selectedMapId, setSelectedMapId] = useState<string | null>(null);
  const [selectedLocation, setSelectedLocation] = useState<MapLocation | null>(null);
  const { maps, loading, error, refetch: refetchMaps } = useWorldMaps(conversationId || '');
  const user = useAuthStore((s) => s.user);
  const { discoveredLocation } = useLocationDiscovered(conversationId || '');

  // Refetch maps when locations are discovered (updates isDiscovered flags for all tabs)
  useEffect(() => {
    if (discoveredLocation) {
      refetchMaps();
    }
  }, [discoveredLocation, refetchMaps]);

  // Reset selected map when conversation changes
  useEffect(() => {
    setSelectedMapId(null);
    setSelectedLocation(null);
    setActiveTab('3d');
    setScope('local');
  }, [conversationId]);

  // Use selected map, or fall back to the newest map (last in list, ordered by createdAt ASC)
  const currentMap = selectedMapId
    ? maps?.find((m) => m.mapId === selectedMapId) ?? maps?.[maps.length - 1]
    : maps?.[maps.length - 1];

  // Reset selection when the active map changes (e.g. regeneration)
  const currentMapId = currentMap?.mapId;
  useEffect(() => {
    setSelectedLocation(null);
  }, [currentMapId]);

  const handleGenerated = useCallback((newMapId: string) => {
    setSelectedMapId(newMapId);
    setScope('global');
    setActiveTab('3d');
  }, []);

  const selectedTab = !narratorMode && (activeTab === 'editor' || activeTab === 'generate') ? '3d' : activeTab;
  const tabs: { id: TabId; label: string; icon: React.ReactNode }[] = [
    { id: '3d', label: '3D View', icon: <Box size={14} /> },
    { id: '2d', label: '2D View', icon: <Grid3X3 size={14} /> },
    { id: 'editor', label: 'Editor', icon: <Pencil size={14} /> },
    { id: 'generate', label: 'Generate', icon: <Wand2 size={14} /> },
  ];
  const visibleTabs = tabs.filter(tab => narratorMode || tab.id === '3d' || tab.id === '2d');

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Map}
        title={scope === 'local' ? 'Local map' : currentMap?.name || 'World Map'}
        description={
          scope === 'local' ? 'Your current location and explored paths' : currentMap?.mapScale ? `Scale: ${currentMap.mapScale}` : 'Explore your story world'
        }
        actions={
          conversationId ? (
            <div className="flex flex-wrap items-center gap-3">
              <Link to={`/chat/${conversationId}`} className="inline-flex min-h-11 items-center gap-2 rounded-md px-3 text-sm font-medium text-foreground hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <ArrowLeft size={16} aria-hidden="true" />
                Back to conversation
              </Link>
              <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-md px-3 text-sm text-muted-foreground">
                <input type="checkbox" checked={narratorMode} onChange={event => useNarratorModeStore.getState().setEnabled(conversationId, event.target.checked)} />
                Narrator mode
              </label>
              <div role="group" aria-label="Map scope" className="flex gap-1 rounded-lg border border-border p-1">
                {(['local','global'] as const).map(view => <button key={view} type="button" aria-pressed={scope === view}
                  onClick={() => setScope(view)} className={cn('min-h-11 rounded-md px-3 text-sm font-medium',scope === view ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground')}>
                  {view === 'local' ? 'Local' : 'Global'}
                </button>)}
              </div>
              {scope === 'global' && currentMap && <div role="group" aria-label="Global map view" className="flex flex-wrap gap-1">
                {visibleTabs.map(tab => <button key={tab.id} type="button" aria-pressed={selectedTab === tab.id} onClick={() => setActiveTab(tab.id)}
                  className={cn('flex min-h-11 items-center gap-1.5 rounded-md px-3 text-sm font-medium',selectedTab === tab.id ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground')}>
                  {tab.icon}{tab.label}
                </button>)}
              </div>}
            </div>
          ) : null
        }
      />

      {conversationId && <><EncounterNotice conversationId={conversationId} /><JourneyNotice conversationId={conversationId} /></>}

      {/* Content */}
      <div className="flex-1 overflow-hidden">
        {!conversationId ? (
          <div className="flex h-full items-center justify-center">
            <div className="text-center">
              <div className="bg-primary/10 text-primary mx-auto mb-4 flex h-10 w-10 items-center justify-center rounded-2xl">
                <Map size={20} aria-hidden="true" />
              </div>
              <h2 className="text-foreground text-base font-semibold">No Adventure Selected</h2>
              <p className="text-muted-foreground mt-1 text-sm">
                Select a roleplay conversation to view its world map.
              </p>
            </div>
          </div>
        ) : scope === 'local' ? (
          <div className="h-full overflow-y-auto"><LocalMapPanel conversationId={conversationId} narratorMode={narratorMode} expanded /></div>
        ) : error ? (
          <div role="alert" className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
            <p className="text-sm text-destructive">Could not load the world map.</p>
            <button type="button" className="rounded-lg border border-border px-4 py-2 text-sm" onClick={() => void refetchMaps()}>Try again</button>
          </div>
        ) : loading && !currentMap ? (
          <div className="flex h-full items-center justify-center">
            <div className="text-sm text-muted-foreground">Loading map...</div>
          </div>
        ) : !currentMap ? (
          narratorMode ? <MapGenerator conversationId={conversationId} onGenerated={handleGenerated} /> :
            <p className="p-6 text-center text-sm text-muted-foreground">No world map available yet. Continue your adventure to explore the world.</p>
        ) : selectedTab === 'generate' ? (
          <MapGenerator conversationId={conversationId} onGenerated={handleGenerated} />
        ) : (
          <>
            {selectedTab === '3d' && (
              <Suspense fallback={
                <div className="flex h-full items-center justify-center">
                  <div className="text-sm text-muted-foreground">Loading 3D viewer...</div>
                </div>
              }>
                <WorldMap3D
                  key={currentMap.mapId}
                  map={currentMap}
                  conversationId={conversationId}
                  userId={user?.id || ''}
                  selectedLocation={selectedLocation}
                  onSelectLocation={setSelectedLocation}
                  onDataChanged={refetchMaps}
                />
              </Suspense>
            )}
            {selectedTab === '2d' && (
              <WorldMap2D key={currentMap.mapId} conversationId={conversationId} mapId={currentMap.mapId} userId={user?.id} selectedLocation={selectedLocation} onSelectLocation={setSelectedLocation} />
            )}
            {selectedTab === 'editor' && (
              <div className="h-full overflow-y-auto p-6">
                <MapEditor key={currentMap.mapId} mapId={currentMap.mapId} conversationId={conversationId} />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default MapPage;
