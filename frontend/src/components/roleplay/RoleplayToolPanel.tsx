import { useState, useEffect, useMemo, lazy, Suspense } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { PanelToolbarContext } from '@/components/layout/PanelToolbarContext';
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
  MapPin,
  Swords,
  ArrowLeft,
  Check,
  ChevronDown,
  Maximize2,
  Minimize2,
} from 'lucide-react';
import WorldStatePanel from './WorldStatePanel';
import WorldStateEditor from './WorldStateEditor';
import LocalMapPanel from '@/components/map/LocalMapPanel';
import WorldMapView from '@/components/map/WorldMapView';
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
import { useCharacterInventory, useUserInventory } from '@/hooks/useRoleplay';
import BattleOverlay from './BattleOverlay';

const StoryPublisher = lazy(() => import('./StoryPublisher'));

type TabId = 'world' | 'localMap' | 'worldMap' | 'characters' | 'dice' | 'inventory' | 'bookmarks' | 'knowledge' | 'images' | 'ai' | 'battle';

interface Tab {
  id: TabId;
  label: string;
  icon: LucideIcon;
}

const TABS: Tab[] = [
  { id: 'world', label: 'World state', icon: Globe },
  { id: 'localMap', label: 'Local map', icon: MapPin },
  { id: 'worldMap', label: 'World map', icon: MapIcon },
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
  const [toolbarSlot, setToolbarSlot] = useState<HTMLDivElement | null>(null);
  const activeTool = visibleTabs.find((t) => t.id === selectedTab) ?? visibleTabs[0];
  const ActiveIcon = activeTool.icon;

  // From md up a map can take over the transcript area; the composer stays below.
  const [expanded, setExpanded] = useState(false);
  const mapTool = selectedTab === 'worldMap' || selectedTab === 'localMap';
  const mapExpanded = expanded && mapTool;
  useEffect(() => { setExpanded(false); }, [conversationId]);
  useEffect(() => {
    if (!mapExpanded) return undefined;
    const collapseOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) setExpanded(false);
    };
    window.addEventListener('keydown', collapseOnEscape);
    return () => window.removeEventListener('keydown', collapseOnEscape);
  }, [mapExpanded]);

  const activeCharacters = useMemo(
    () => participants.filter((p) => p.isActive),
    [participants]
  );

  return (
    <div
      className={cn(
        'absolute inset-0 z-20 flex h-full w-full flex-shrink-0 flex-row border-l border-border/50 bg-background',
        !mapExpanded && 'md:static md:z-auto md:w-96 md:bg-background-secondary/30',
      )}
    >
      {/* From md up the tools sit in a 40px rail beside the content. Phones use
          the single header bar below instead, with a tool picker. */}
      <div
        role="toolbar"
        aria-label="Roleplay tools"
        className="hidden w-10 shrink-0 flex-col items-center gap-0.5 border-r border-border/50 bg-background-secondary/50 py-2 md:flex"
      >
        {visibleTabs.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            type="button"
            aria-pressed={selectedTab === id}
            onClick={() => setActiveTab(id)}
            className={cn(
              'flex h-8 w-8 shrink-0 items-center justify-center rounded-md transition-colors duration-150',
              selectedTab === id
                ? 'bg-primary/15 text-primary'
                : 'text-muted-foreground hover:bg-accent hover:text-foreground'
            )}
            aria-label={label}
            title={label}
          >
            <Icon size={15} />
          </button>
        ))}

        <div className="flex-1" />

        <button
          type="button"
          onClick={() => setShowPublisher(true)}
          className={cn(
            'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
            'text-muted-foreground hover:bg-accent hover:text-foreground',
            'transition-colors duration-150'
          )}
          aria-label="Publish story"
          title="Publish story"
        >
          <Send size={15} />
        </button>
      </div>

      {/* Tab content */}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {/* One header bar: back to the chat and a tool picker on phones, the
            tool's name from md up, and the active tool's own controls. */}
        <div className="flex min-h-12 shrink-0 items-center gap-1 border-b border-border/50 bg-background-secondary px-1.5 md:min-h-10 md:px-3">
          {onClose && (
            <button
              type="button"
              onClick={onClose}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground md:hidden"
              aria-label="Back to conversation"
              title="Back to conversation"
            >
              <ArrowLeft size={20} />
            </button>
          )}
          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
              <button
                type="button"
                className="flex min-h-10 min-w-0 items-center gap-2 rounded-lg px-2 text-sm font-medium text-foreground transition-colors hover:bg-accent md:hidden"
                aria-label={`${activeTool.label}. Choose another roleplay tool`}
              >
                <ActiveIcon size={16} className="shrink-0 text-primary" aria-hidden="true" />
                <span className="truncate">{activeTool.label}</span>
                <ChevronDown size={14} className="shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content
                align="start"
                sideOffset={6}
                className="z-50 max-h-[var(--radix-dropdown-menu-content-available-height)] w-56 max-w-[calc(100vw-2rem)] overflow-y-auto rounded-xl border border-border bg-popover p-1.5 text-popover-foreground shadow-lg"
              >
                {visibleTabs.map(({ id, label, icon: Icon }) => (
                  <DropdownMenu.Item
                    key={id}
                    onSelect={() => setActiveTab(id)}
                    className={cn(
                      'flex min-h-10 cursor-pointer items-center gap-3 rounded-lg px-3 text-sm outline-none data-[highlighted]:bg-accent',
                      selectedTab === id && 'text-primary'
                    )}
                  >
                    <Icon size={16} aria-hidden="true" />
                    {label}
                    {selectedTab === id && <Check size={14} className="ml-auto" aria-hidden="true" />}
                  </DropdownMenu.Item>
                ))}
                <DropdownMenu.Separator className="my-1 h-px bg-border" />
                <DropdownMenu.Item
                  onSelect={() => setShowPublisher(true)}
                  className="flex min-h-10 cursor-pointer items-center gap-3 rounded-lg px-3 text-sm outline-none data-[highlighted]:bg-accent"
                >
                  <Send size={16} aria-hidden="true" />
                  Publish story
                </DropdownMenu.Item>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>
          <h3 className="hidden min-w-0 truncate text-xs font-semibold uppercase tracking-wider text-muted-foreground md:block">
            {activeTool.label}
          </h3>
          <div ref={setToolbarSlot} className="ml-auto flex shrink-0 items-center gap-0.5" />
          {mapTool && (
            <button
              type="button"
              aria-pressed={mapExpanded}
              onClick={() => setExpanded((value) => !value)}
              className="hidden h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground md:flex"
              aria-label={mapExpanded ? 'Collapse map (Esc)' : 'Expand map'}
              title={mapExpanded ? 'Collapse map (Esc)' : 'Expand map'}
            >
              {mapExpanded ? <Minimize2 size={15} aria-hidden="true" /> : <Maximize2 size={15} aria-hidden="true" />}
            </button>
          )}
        </div>

        {/* The maps fill the panel; every other tool scrolls with padding. */}
        {selectedTab === 'localMap' ? (
          <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto">
            <LocalMapPanel conversationId={conversationId} narratorMode={narratorMode} />
          </div>
        ) : selectedTab === 'worldMap' ? (
          <PanelToolbarContext.Provider value={toolbarSlot}>
            <WorldMapView conversationId={conversationId} narratorMode={narratorMode} className="min-h-0 flex-1" />
          </PanelToolbarContext.Provider>
        ) : (
        <div className="custom-scrollbar min-h-0 flex-1 overflow-y-auto p-3">
          {selectedTab === 'world' && (
            <div className="space-y-4">
              <WorldStatePanel conversationId={conversationId} />
              {narratorMode && <WorldStateEditor conversationId={conversationId} />}
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
            <>
              {/* Empty packs are left out; the line below covers the case
                  where nobody carries anything. */}
              <div className="peer space-y-3">
                {currentUserId && (
                  <InventoryCard
                    conversationId={conversationId}
                    userId={currentUserId}
                    name={currentUsername || 'You'}
                    narratorMode={narratorMode}
                  />
                )}
                {activeCharacters.map((p) => (
                  <InventoryCard
                    key={p.character.id}
                    conversationId={conversationId}
                    characterId={p.character.id}
                    name={p.character.name}
                    avatar={p.character.avatar}
                    narratorMode={narratorMode}
                  />
                ))}
              </div>
              <p className="hidden py-1 text-center text-sm text-muted-foreground peer-empty:block">
                No one is carrying anything yet.
              </p>
            </>
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
            <ImageGallery conversationId={conversationId} participants={participants} />
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
        )}
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

interface InventoryCardProps {
  conversationId: string;
  characterId?: string;
  userId?: string;
  name: string;
  avatar?: string;
  narratorMode: boolean;
}

/** One person's pack in the inventory tab; hidden while empty unless the narrator can stock it. */
function InventoryCard(props: InventoryCardProps) {
  return props.characterId ? <CharacterInventoryCard {...props} /> : <UserInventoryCard {...props} />;
}

function CharacterInventoryCard(props: InventoryCardProps) {
  const state = useCharacterInventory(props.conversationId, props.characterId || '');
  return <InventoryCardBody {...props} {...state} />;
}

function UserInventoryCard(props: InventoryCardProps) {
  const state = useUserInventory(props.conversationId, props.userId || '');
  return <InventoryCardBody {...props} {...state} />;
}

function InventoryCardBody({
  conversationId,
  characterId,
  userId,
  name,
  avatar,
  narratorMode,
  inventory,
  loading,
  error,
}: InventoryCardProps & { inventory: unknown[]; loading: boolean; error?: unknown }) {
  if (!narratorMode && !loading && !error && inventory.length === 0) return null;

  return (
    <div className="rounded-lg border border-border/50 bg-card/50 p-3">
      <div className="mb-2 flex items-center gap-2">
        {characterId && (avatar ? (
          <img src={avatar} alt="" className="h-5 w-5 rounded-full object-cover" />
        ) : (
          <span aria-hidden="true" className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-[10px] font-bold uppercase text-muted-foreground">
            {name.charAt(0)}
          </span>
        ))}
        <h4 className="text-xs font-medium text-foreground">{name}</h4>
      </div>
      <InventoryList conversationId={conversationId} characterId={characterId} userId={userId} narratorMode={narratorMode} />
    </div>
  );
}
