import React, { useState, useCallback, useEffect, lazy, Suspense } from 'react';
import { cn } from '@/lib/utils';
import { Map, Box, Grid3X3, Wand2, RotateCcw } from 'lucide-react';
import { useWorldMaps } from '@/components/map/hooks/useMapData';
import { useCreateConversation } from '@/hooks/useGraphQL';
import { useAuthStore } from '@/stores/authStore';
import WorldMap2D from '@/components/map/WorldMap2D';
import MapGenerator from '@/components/map/MapGenerator';
import { PageHeader } from '@/components/layout/PageHeader';
import { Button } from '@/components/ui/Button';
import { toast } from 'react-hot-toast';
import type { MapLocation, WorldMap } from '@/graphql/types';

const WorldMap3D = lazy(() => import('@/components/map/WorldMap3D'));

const DEBUG_USER_ID = '00000000-0000-0000-0000-000000000001';

type TabId = '3d' | '2d' | 'generate';

const TestMapPage: React.FC = () => {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<TabId>('generate');
  const [selectedMapId, setSelectedMapId] = useState<string | null>(null);
  const [selectedLocation, setSelectedLocation] = useState<MapLocation | null>(null);

  const { createConversation, loading: creatingConversation } = useCreateConversation();
  const { maps } = useWorldMaps(conversationId || '');
  const user = useAuthStore((s) => s.user);

  const currentMap = selectedMapId
    ? maps?.find((m: WorldMap) => m.mapId === selectedMapId) ?? maps?.[maps.length - 1]
    : maps?.[maps.length - 1];

  // Reset selection when the active map changes
  const currentMapId = currentMap?.mapId;
  useEffect(() => {
    setSelectedLocation(null);
  }, [currentMapId]);

  /** Ensure a throwaway conversation exists, then return its id. */
  const ensureConversation = useCallback(async (): Promise<string> => {
    if (conversationId) return conversationId;

    const result = await createConversation({
      title: '[Test] Map Preview',
    });
    const newId = result.data?.createConversation?.id;
    if (!newId) throw new Error('Failed to create test conversation');
    setConversationId(newId);
    return newId;
  }, [conversationId, createConversation]);

  const handleGenerated = useCallback((newMapId: string) => {
    setSelectedMapId(newMapId);
    setActiveTab('3d');
  }, []);

  const handleReset = useCallback(() => {
    setConversationId(null);
    setSelectedMapId(null);
    setSelectedLocation(null);
    setActiveTab('generate');
  }, []);

  const tabs: { id: TabId; label: string; icon: React.ReactNode }[] = [
    { id: '3d', label: '3D View', icon: <Box size={14} aria-hidden="true" /> },
    { id: '2d', label: '2D View', icon: <Grid3X3 size={14} aria-hidden="true" /> },
    { id: 'generate', label: 'Generate', icon: <Wand2 size={14} aria-hidden="true" /> },
  ];

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col">
      <PageHeader
        icon={Map}
        title={currentMap?.name || 'Test Map'}
        description={
          currentMap?.mapScale ? `Scale: ${currentMap.mapScale}` : 'Quick map generation & preview'
        }
        actions={
          <>
            {conversationId && (
              <Button
                variant="outline"
                size="sm"
                leftIcon={<RotateCcw size={16} aria-hidden="true" />}
                onClick={handleReset}
                aria-label="New session"
                title="New session"
                className="h-10 w-10 px-0 sm:h-8 sm:w-auto sm:px-3"
              >
                <span className="hidden sm:inline">New Session</span>
              </Button>
            )}
            {currentMap && (
              <div className="border-border bg-muted/40 flex flex-wrap items-center gap-1 rounded-lg border p-1">
                {tabs.map((tab) => (
                  <button
                    key={tab.id}
                    type="button"
                    aria-pressed={activeTab === tab.id}
                    aria-label={tab.label}
                    title={tab.label}
                    onClick={() => setActiveTab(tab.id)}
                    className={cn(
                      'flex h-10 min-w-10 items-center justify-center gap-1.5 rounded-md px-2.5 text-xs font-medium transition-colors sm:h-8',
                      activeTab === tab.id
                        ? 'bg-card text-foreground shadow-sm'
                        : 'text-muted-foreground hover:text-foreground'
                    )}
                  >
                    {tab.icon}
                    <span className="hidden sm:inline">{tab.label}</span>
                  </button>
                ))}
              </div>
            )}
          </>
        }
      />

      {/* Content */}
      <div className="flex-1 overflow-hidden">
        {activeTab === 'generate' || !currentMap || !conversationId ? (
          <ConversationGatedGenerator
            conversationId={conversationId}
            ensureConversation={ensureConversation}
            creatingConversation={creatingConversation}
            onGenerated={handleGenerated}
          />
        ) : activeTab === '3d' ? (
          <Suspense fallback={
            <div className="flex h-full items-center justify-center">
              <div className="text-sm text-muted-foreground">Loading 3D viewer...</div>
            </div>
          }>
            <WorldMap3D
              key={currentMap.mapId}
              map={currentMap}
              conversationId={conversationId}
              userId={user?.id || DEBUG_USER_ID}
              selectedLocation={selectedLocation}
              onSelectLocation={setSelectedLocation}
            />
          </Suspense>
        ) : activeTab === '2d' ? (
          <WorldMap2D
            key={currentMap.mapId}
            conversationId={conversationId}
            mapId={currentMap.mapId}
            userId={user?.id || DEBUG_USER_ID}
            selectedLocation={selectedLocation}
            onSelectLocation={setSelectedLocation}
          />
        ) : null}
      </div>
    </div>
  );
};

/**
 * Wrapper that creates a conversation on-demand before showing MapGenerator.
 * While the conversation is being created, shows a loading state inside MapGenerator's
 * generate button area.
 */
function ConversationGatedGenerator({
  conversationId,
  ensureConversation,
  creatingConversation,
  onGenerated,
}: {
  conversationId: string | null;
  ensureConversation: () => Promise<string>;
  creatingConversation: boolean;
  onGenerated: (mapId: string) => void;
}) {
  const [resolvedId, setResolvedId] = useState<string | null>(conversationId);
  const [resolving, setResolving] = useState(false);

  // Keep in sync if parent resets or sets a new conversation
  useEffect(() => {
    setResolvedId(conversationId);
  }, [conversationId]);

  if (resolvedId) {
    return <MapGenerator conversationId={resolvedId} onGenerated={onGenerated} />;
  }

  // Show a "create & generate" interstitial
  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-lg space-y-6 px-4 py-8 sm:p-6 sm:pt-12">
        <div className="text-center">
          <Wand2 className="mx-auto h-10 w-10 text-primary mb-3" />
          <h2 className="text-lg font-semibold text-foreground">Test Map Generator</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            A throwaway conversation will be created automatically to host the map data.
          </p>
        </div>
        <button
          onClick={async () => {
            setResolving(true);
            try {
              const id = await ensureConversation();
              setResolvedId(id);
            } catch {
              toast.error('Failed to set up test session');
            } finally {
              setResolving(false);
            }
          }}
          disabled={resolving || creatingConversation}
          className={cn(
            'flex w-full items-center justify-center gap-2 rounded-lg px-4 py-3',
            'text-sm font-medium transition-colors',
            resolving || creatingConversation
              ? 'bg-accent text-muted-foreground cursor-not-allowed'
              : 'bg-primary text-primary-foreground hover:bg-primary-hover'
          )}
        >
          {resolving || creatingConversation ? (
            <>
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-border-hover border-t-foreground" />
              Setting up...
            </>
          ) : (
            <>
              <Wand2 className="h-4 w-4" />
              Start Generating
            </>
          )}
        </button>
      </div>
    </div>
  );
}

export default TestMapPage;
