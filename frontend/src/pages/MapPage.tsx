import { useNarratorMode, useNarratorModeStore } from '@/stores/narratorModeStore';
import JourneyNotice from '@/components/roleplay/JourneyNotice';
import EncounterNotice from '@/components/roleplay/EncounterNotice';
import React, { useState, useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { SwitchTrack } from '@/components/ui/SwitchTrack';
import { ArrowLeft, Map, Wand2 } from 'lucide-react';
import { useWorldMaps } from '@/components/map/hooks/useMapData';
import { PageHeader } from '@/components/layout/PageHeader';
import LocalMapPanel from '@/components/map/LocalMapPanel';
import WorldMapView, { type WorldMapViewId } from '@/components/map/WorldMapView';

const MapPage: React.FC = () => {
  const { conversationId } = useParams<{ conversationId?: string }>();
  const narratorMode = useNarratorMode(conversationId);
  const [scope, setScope] = useState<'local' | 'world'>('local');
  const [worldView, setWorldView] = useState<WorldMapViewId>('3d');
  const { maps } = useWorldMaps(conversationId || '');
  const newestMap = maps?.[maps.length - 1];

  useEffect(() => {
    setScope('local');
    setWorldView('3d');
  }, [conversationId]);

  const title = scope === 'local' ? 'Local map' : newestMap?.name || 'World map';
  const description = scope === 'local'
    ? 'Your current location and explored paths'
    : newestMap?.mapScale ? `Scale: ${newestMap.mapScale}` : 'Explore your story world';

  if (!conversationId) {
    return (
      <div className="flex h-full min-h-0 min-w-0 flex-col">
        <PageHeader icon={Map} title={title} description={description} />
        <div className="flex flex-1 items-center justify-center p-6 text-center">
          <div>
            <div className="bg-primary/10 text-primary mx-auto mb-4 flex h-10 w-10 items-center justify-center rounded-2xl">
              <Map size={20} aria-hidden="true" />
            </div>
            <h2 className="text-foreground text-base font-semibold">No Adventure Selected</h2>
            <p className="text-muted-foreground mt-1 text-sm">
              Select a roleplay conversation to view its world map.
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <header className="shrink-0 border-b border-border bg-background-secondary">
        {/* One toolbar row; the title is visible from sm up and kept for
            screen readers on phones. */}
        <div className="flex items-center gap-2 px-2 py-1.5 sm:gap-3 sm:px-6 sm:py-3">
          <Link to={`/chat/${conversationId}`} aria-label="Back to conversation" title="Back to conversation" className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:-ml-2">
            <ArrowLeft size={20} aria-hidden="true" />
          </Link>
          <div className="sr-only sm:not-sr-only sm:min-w-0 sm:flex-1">
            <h1 className="font-display text-foreground truncate text-2xl leading-tight font-normal">{title}</h1>
            <p className="text-muted-foreground mt-0.5 truncate text-sm">{description}</p>
          </div>
          <div role="group" aria-label="Map scope" className="flex shrink-0 gap-0.5 rounded-lg border border-border p-0.5">
            {(['local', 'world'] as const).map(view => (
              <button key={view} type="button" aria-pressed={scope === view} onClick={() => setScope(view)}
                className={cn('min-h-9 rounded-md px-3 text-sm font-medium transition-colors', scope === view ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground')}>
                {view === 'local' ? 'Local' : 'World'}
              </button>
            ))}
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={narratorMode}
            aria-label="Narrator mode"
            title="Narrator mode"
            onClick={() => useNarratorModeStore.getState().setEnabled(conversationId, !narratorMode)}
            className={cn('ml-auto inline-flex min-h-10 shrink-0 items-center gap-2 rounded-lg px-2 text-sm font-medium transition-colors hover:bg-accent sm:ml-0 sm:-mr-2 sm:px-3', narratorMode ? 'text-foreground' : 'text-muted-foreground hover:text-foreground')}
          >
            <Wand2 size={16} aria-hidden="true" />
            <span className="hidden sm:inline">Narrator</span>
            <SwitchTrack checked={narratorMode} />
          </button>
        </div>
      </header>

      <EncounterNotice conversationId={conversationId} />
      <JourneyNotice conversationId={conversationId} />

      <div className="min-h-0 flex-1 overflow-hidden">
        {scope === 'local' ? (
          <div className="h-full overflow-y-auto">
            <LocalMapPanel conversationId={conversationId} narratorMode={narratorMode} />
          </div>
        ) : (
          <WorldMapView
            conversationId={conversationId}
            narratorMode={narratorMode}
            view={worldView}
            onViewChange={setWorldView}
            labelledViews
          />
        )}
      </div>
    </div>
  );
};

export default MapPage;
