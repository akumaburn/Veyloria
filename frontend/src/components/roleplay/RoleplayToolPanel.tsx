import { useState, useEffect, useMemo, lazy, Suspense } from 'react';
import { cn } from '@/lib/utils';
import type { LucideIcon } from 'lucide-react';
import {
  Globe,
  Users,
  Dices,
  Package,
  Bookmark,
  BookOpen,
  ImageIcon,
  Brain,
  Send,
  Map as MapIcon,
  Swords,
  X,
} from 'lucide-react';
import WorldStatePanel from './WorldStatePanel';
import WorldStateEditor from './WorldStateEditor';
import MapEditor from '@/components/map/MapEditor';
import LocalMapPanel from '@/components/map/LocalMapPanel';
import WorldMap2D from '@/components/map/WorldMap2D';
import InventoryList from './InventoryList';
import CharacterSheet from './CharacterSheet';
import DiceRoller from './DiceRoller';
import BookmarkList from './BookmarkList';
import BranchSelector from './BranchSelector';
import KnowledgeView from './KnowledgeView';
import ImageGallery from './ImageGallery';
import AgentInsights from './AgentInsights';
import AgentModelSettings from './AgentModelSettings';
import CharacterProposalList from './CharacterProposalList';
import BattleOverlay from './BattleOverlay';
import { useWorldMaps } from '@/components/map/hooks/useMapData';

const StoryPublisher = lazy(() => import('./StoryPublisher'));

type TabId = 'world' | 'map' | 'characters' | 'dice' | 'inventory' | 'bookmarks' | 'knowledge' | 'images' | 'ai' | 'battle';

interface Tab {
  id: TabId;
  label: string;
  icon: LucideIcon;
}

const TABS: Tab[] = [
  { id: 'world', label: 'World', icon: Globe },
  { id: 'map', label: 'Map', icon: MapIcon },
  { id: 'characters', label: 'Characters', icon: Users },
  { id: 'dice', label: 'Dice', icon: Dices },
  { id: 'inventory', label: 'Inventory', icon: Package },
  { id: 'bookmarks', label: 'Bookmarks', icon: Bookmark },
  { id: 'knowledge', label: 'Knowledge', icon: BookOpen },
  { id: 'images', label: 'Images', icon: ImageIcon },
  { id: 'battle', label: 'Battle', icon: Swords },
  { id: 'ai', label: 'AI', icon: Brain },
];

interface RoleplayToolPanelProps {
  battleId?: string;
  conversationId: string;
  onClose?: () => void;
  participants: Array<{
    character: { id: string; name: string; avatar?: string };
    isActive: boolean;
  }>;
  currentUserId?: string;
  currentUsername?: string;
  narratorMode?: boolean;
}

export default function RoleplayToolPanel({
  conversationId,
  battleId,
  participants,
  currentUserId,
  currentUsername,
  narratorMode = false,
  onClose,
}: RoleplayToolPanelProps) {
  const [activeTab, setActiveTab] = useState<TabId>(battleId ? 'battle' : 'world');
  useEffect(() => { if (battleId) setActiveTab('battle'); }, [battleId]);
  const visibleTabs = TABS.filter(tab => narratorMode || !['ai', 'knowledge'].includes(tab.id));
  const selectedTab = visibleTabs.some(tab => tab.id === activeTab) ? activeTab : 'world';
  const [showPublisher, setShowPublisher] = useState(false);
  const { maps } = useWorldMaps(conversationId);
  const effectiveMapId = maps?.[0]?.mapId;

  const activeCharacters = useMemo(
    () => participants.filter((p) => p.isActive),
    [participants]
  );

  return (
    <div className="absolute inset-0 z-20 flex h-full w-full flex-shrink-0 border-l border-border/50 bg-background md:static md:z-auto md:w-96 md:bg-background-secondary/30">
      {/* Tab icons - vertical strip */}
      <div className="flex w-10 flex-shrink-0 flex-col items-center gap-0.5 border-r border-border/50 bg-background-secondary/50 py-2">
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            className="mb-1 flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground md:hidden"
            aria-label="Close roleplay tools"
            title="Close roleplay tools"
          >
            <X size={15} />
          </button>
        )}
        {visibleTabs.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setActiveTab(id)}
            className={cn(
              'flex h-8 w-8 items-center justify-center rounded-md transition-colors duration-150',
              selectedTab === id
                ? 'bg-primary/15 text-primary'
                : 'text-muted-foreground hover:bg-accent hover:text-foreground'
            )}
            title={label}
          >
            <Icon size={15} />
          </button>
        ))}

        {/* Spacer */}
        <div className="flex-1" />

        {/* Publish Story button at bottom */}
        <button
          onClick={() => setShowPublisher(true)}
          className={cn(
            'flex h-8 w-8 items-center justify-center rounded-md',
            'text-muted-foreground hover:bg-accent hover:text-foreground',
            'transition-colors duration-150'
          )}
          title="Publish Story"
        >
          <Send size={15} />
        </button>
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-y-auto custom-scrollbar">
        {/* Tab header */}
        <div className="sticky top-0 z-10 border-b border-border/50 bg-background-secondary/80 backdrop-blur-sm px-3 py-2">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {visibleTabs.find((t) => t.id === selectedTab)?.label}
          </h3>
        </div>

        <div className="p-3">
          {selectedTab === 'world' && (
            <div className="space-y-4">
              <WorldStatePanel conversationId={conversationId} />
              {narratorMode && <WorldStateEditor conversationId={conversationId} />}
            </div>
          )}

          {selectedTab === 'map' && (
            <div className="space-y-4">
              {/* Always visible: the party's trail with fog of war. In
                  narrator mode the GM sees the whole layout instead. */}
              <LocalMapPanel conversationId={conversationId} narratorMode={narratorMode} />
              {narratorMode && (
                <>
                  <div className="space-y-2">
                    <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                      <MapIcon size={12} />
                      Map Preview
                    </h4>
                    <WorldMap2D conversationId={conversationId} userId={currentUserId} />
                  </div>
                  {effectiveMapId && (
                    <MapEditor mapId={effectiveMapId} conversationId={conversationId} />
                  )}
                </>
              )}
            </div>
          )}

          {selectedTab === 'characters' && (
            <div className="space-y-3">
              {activeCharacters.length === 0 ? (
                <p className="text-xs text-muted-foreground text-center py-4">
                  No active characters
                </p>
              ) : (
                activeCharacters.map((p) => (
                  <CharacterSheet
                    key={p.character.id}
                    conversationId={conversationId}
                    characterId={p.character.id}
                    characterName={p.character.name}
                    characterAvatar={p.character.avatar}
                    narratorMode={narratorMode}
                  />
                ))
              )}
            </div>
          )}

          {selectedTab === 'dice' && (
            <DiceRoller
              conversationId={conversationId}
              narratorMode={narratorMode}
              characters={narratorMode ? activeCharacters.map((p) => p.character) : []}
            />
          )}

          {selectedTab === 'inventory' && (
            <div className="space-y-3">
              {/* User inventory */}
              {currentUserId && (
                <div className="rounded-lg border border-border/50 bg-card/50 p-3">
                  <h4 className="mb-2 text-xs font-medium text-foreground">
                    {currentUsername || 'You'}
                  </h4>
                  <InventoryList
                    conversationId={conversationId}
                    userId={currentUserId}
                    narratorMode={narratorMode}
                  />
                </div>
              )}
              {/* Character inventories */}
              {activeCharacters.map((p) => (
                <div
                  key={p.character.id}
                  className="rounded-lg border border-border/50 bg-card/50 p-3"
                >
                  <div className="mb-2 flex items-center gap-2">
                    {p.character.avatar ? (
                      <img
                        src={p.character.avatar}
                        alt={p.character.name}
                        className="h-5 w-5 rounded-full object-cover"
                      />
                    ) : (
                      <div className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-[10px] font-bold uppercase text-muted-foreground">
                        {p.character.name.charAt(0)}
                      </div>
                    )}
                    <h4 className="text-xs font-medium text-foreground">
                      {p.character.name}
                    </h4>
                  </div>
                  <InventoryList
                    conversationId={conversationId}
                    characterId={p.character.id}
                    narratorMode={narratorMode}
                  />
                </div>
              ))}
            </div>
          )}

          {selectedTab === 'bookmarks' && (
            <div className="space-y-4">
              <BookmarkList conversationId={conversationId} />
              {narratorMode && <div className="border-t border-border/50 pt-3">
                <h4 className="mb-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  Branches
                </h4>
                <BranchSelector conversationId={conversationId} />
              </div>}
            </div>
          )}

          {selectedTab === 'knowledge' && (
            <KnowledgeView conversationId={conversationId} />
          )}

          {selectedTab === 'images' && (
            <ImageGallery conversationId={conversationId} />
          )}

          {selectedTab === 'battle' && (
            <BattleOverlay
              battleId={battleId}
              conversationId={conversationId}
              currentUserId={currentUserId}
              participants={participants}
              narratorMode={narratorMode}
            />
          )}

          {selectedTab === 'ai' && (
            <div className="space-y-4">
              <AgentInsights conversationId={conversationId} />
              <div className="border-t border-border/50 pt-3">
                <h4 className="mb-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  Character Proposals
                </h4>
                <CharacterProposalList conversationId={conversationId} />
              </div>
              {narratorMode && (
                <div className="border-t border-border/50 pt-3">
                  <AgentModelSettings conversationId={conversationId} />
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Story Publisher modal */}
      {showPublisher && (
        <Suspense fallback={null}>
          <StoryPublisher
            conversationId={conversationId}
            isOpen={showPublisher}
            onClose={() => setShowPublisher(false)}
          />
        </Suspense>
      )}
    </div>
  );
}
