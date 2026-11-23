import { useState, useEffect } from 'react';
import { cn } from '@/lib/utils';
import {
  useCharacterStats,
  useCharacterRelationships,
  useCharacterInventory,
  useStatChanged,
  useRelationshipChanged,
  useSetCharacterStat,
  useSetRelationship,
} from '@/hooks/useRoleplay';
import { type CharacterStat, type CharacterRelationship, type CharacterInventoryItem } from '@/graphql/types';
import StatBar from '@/components/roleplay/StatBar';
import RelationshipCard from '@/components/roleplay/RelationshipCard';
import InventoryList from '@/components/roleplay/InventoryList';
import {
  BarChart3,
  Users,
  Package,
  ChevronDown,
  ChevronUp,
  Loader2,
  User,
  Pencil,
  Check,
  X,
} from 'lucide-react';
import { toast } from 'react-hot-toast';
import { useCharacterCardStore } from '@/stores/characterCardStore';

interface CharacterSheetProps {
  conversationId: string;
  characterId: string;
  characterName: string;
  characterAvatar?: string;
  narratorMode?: boolean;
}

type SectionId = 'stats' | 'relationships' | 'inventory';

interface CollapsibleSectionProps {
  id: SectionId;
  title: string;
  icon: React.ReactNode;
  count?: number;
  isOpen: boolean;
  onToggle: () => void;
  loading?: boolean;
  children: React.ReactNode;
}

function CollapsibleSection({
  title,
  icon,
  count,
  isOpen,
  onToggle,
  loading,
  children,
}: CollapsibleSectionProps) {
  return (
    <div className="rounded-xl border border-border bg-card">
      <button
        onClick={onToggle}
        className="flex w-full items-center justify-between rounded-t-xl px-4 py-3 transition-colors hover:bg-accent/60"
      >
        <div className="flex items-center gap-2.5">
          <span className="text-muted-foreground">{icon}</span>
          <span className="text-sm font-medium text-foreground">{title}</span>
          {count != null && (
            <span className="rounded-md bg-accent/50 px-1.5 py-0.5 text-xs font-mono text-muted-foreground">
              {count}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {loading && <Loader2 size={14} className="animate-spin text-muted-foreground" />}
          {isOpen ? (
            <ChevronUp size={16} className="text-muted-foreground" />
          ) : (
            <ChevronDown size={16} className="text-muted-foreground" />
          )}
        </div>
      </button>

      {isOpen && (
        <div className="border-t border-border/50 px-4 py-3">
          {children}
        </div>
      )}
    </div>
  );
}

function EditableStatRow({
  stat,
  conversationId,
  characterId,
}: {
  stat: CharacterStat;
  conversationId: string;
  characterId: string;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(String(stat.statValue ?? 0));
  const { setCharacterStat, loading } = useSetCharacterStat();

  const handleSave = async () => {
    try {
      await setCharacterStat({
        conversationId,
        characterId,
        statName: stat.statName,
        statValue: parseFloat(draft),
        statMax: stat.statMax,
        statCategory: stat.statCategory,
      });
      setEditing(false);
    } catch {
      toast.error('Failed to update stat');
    }
  };

  if (editing) {
    return (
      <div className="flex items-center gap-2">
        <div className="flex-1">
          <StatBar stat={stat} />
        </div>
        <input
          type="number"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          className={cn(
            'w-16 rounded border border-input bg-background px-1.5 py-0.5',
            'text-xs text-foreground',
            'focus:border-primary focus:outline-none'
          )}
          autoFocus
          onKeyDown={(e) => {
            if (e.key === 'Enter') void handleSave();
            if (e.key === 'Escape') setEditing(false);
          }}
        />
        <button onClick={() => void handleSave()} disabled={loading} className="text-success hover:bg-success/10 rounded p-0.5">
          {loading ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />}
        </button>
        <button onClick={() => setEditing(false)} className="text-muted-foreground hover:bg-accent rounded p-0.5">
          <X size={12} />
        </button>
      </div>
    );
  }

  return (
    <div className="group flex items-center gap-1">
      <div className="flex-1">
        <StatBar stat={stat} />
      </div>
      <button
        onClick={() => { setDraft(String(stat.statValue ?? 0)); setEditing(true); }}
        className="rounded p-0.5 text-muted-foreground/70 opacity-0 group-hover:opacity-100 hover:bg-accent hover:text-muted-foreground transition-all"
      >
        <Pencil size={10} />
      </button>
    </div>
  );
}

function EditableRelationshipRow({
  relationship,
  conversationId,
}: {
  relationship: CharacterRelationship;
  conversationId: string;
}) {
  const [editing, setEditing] = useState(false);
  const [draftIntensity, setDraftIntensity] = useState(String(relationship.intensity ?? 50));
  const [draftType, setDraftType] = useState(relationship.relationshipType ?? '');
  const { setRelationship, loading } = useSetRelationship();

  const handleSave = async () => {
    try {
      await setRelationship({
        conversationId,
        fromCharacterId: relationship.fromCharacterId,
        toCharacterId: relationship.toCharacterId,
        relationshipType: draftType,
        intensity: parseInt(draftIntensity, 10),
      });
      setEditing(false);
    } catch {
      toast.error('Failed to update relationship');
    }
  };

  if (editing) {
    return (
      <div className="space-y-1.5 rounded-lg border border-input bg-background p-2">
        <div className="text-xs text-foreground font-medium">
          {relationship.fromCharacterId.charAt(0).toUpperCase()} → {relationship.toCharacterId.charAt(0).toUpperCase()}
        </div>
        <div className="flex gap-2">
          <input
            type="text"
            value={draftType}
            onChange={(e) => setDraftType(e.target.value)}
            placeholder="Type"
            className="flex-1 rounded border border-input bg-background px-1.5 py-0.5 text-xs text-foreground focus:border-primary focus:outline-none"
          />
          <input
            type="number"
            value={draftIntensity}
            onChange={(e) => setDraftIntensity(e.target.value)}
            className="w-14 rounded border border-input bg-background px-1.5 py-0.5 text-xs text-foreground focus:border-primary focus:outline-none"
            min={0}
            max={100}
          />
        </div>
        <div className="flex gap-1">
          <button onClick={() => void handleSave()} disabled={loading} className="text-success hover:bg-success/10 rounded p-0.5 text-xs">
            {loading ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />}
          </button>
          <button onClick={() => setEditing(false)} className="text-muted-foreground hover:bg-accent rounded p-0.5 text-xs">
            <X size={12} />
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="group relative">
      <RelationshipCard relationship={relationship} />
      <button
        onClick={() => {
          setDraftIntensity(String(relationship.intensity ?? 50));
          setDraftType(relationship.relationshipType ?? '');
          setEditing(true);
        }}
        className="absolute top-2 right-2 rounded p-0.5 text-muted-foreground/70 opacity-0 group-hover:opacity-100 hover:bg-accent hover:text-muted-foreground transition-all"
      >
        <Pencil size={10} />
      </button>
    </div>
  );
}

export default function CharacterSheet({
  conversationId,
  characterId,
  characterName,
  characterAvatar,
  narratorMode,
}: CharacterSheetProps) {
  const openCharacterCard = useCharacterCardStore((state) => state.openCharacterCard);
  const { stats, loading: statsLoading, refetch: refetchStats } = useCharacterStats(conversationId, characterId);
  const { relationships, loading: relsLoading, refetch: refetchRelationships } = useCharacterRelationships(
    conversationId,
    characterId
  );
  const { inventory, loading: invLoading } = useCharacterInventory(conversationId, characterId);

  // Subscribe to real-time changes from the agent pipeline
  const { stat: statEvent } = useStatChanged(conversationId);
  const { relationship: relEvent } = useRelationshipChanged(conversationId);

  // Refetch data when subscription events arrive
  useEffect(() => {
    if (statEvent) refetchStats();
  }, [statEvent, refetchStats]);

  useEffect(() => {
    if (relEvent) refetchRelationships();
  }, [relEvent, refetchRelationships]);



  const [openSections, setOpenSections] = useState<Record<SectionId, boolean>>({
    stats: true,
    relationships: true,
    inventory: true,
  });

  const toggleSection = (section: SectionId) => {
    setOpenSections((prev) => ({ ...prev, [section]: !prev[section] }));
  };

  // Group stats by category for display
  const statsByCategory = (stats as CharacterStat[]).reduce<Record<string, CharacterStat[]>>(
    (acc, stat) => {
      const category = stat.statCategory || 'General';
      if (!acc[category]) acc[category] = [];
      acc[category].push(stat);
      return acc;
    },
    {}
  );

  return (
    <div className="flex flex-col gap-4">
      {/* Character header */}
      <div className="flex items-center gap-4 rounded-xl border border-border bg-card p-4">
        <button
          type="button"
          onClick={() => openCharacterCard(characterId, conversationId)}
          className="shrink-0 cursor-pointer rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          aria-label={`Open ${characterName}'s character card`}
          title={`Open ${characterName}'s character card`}
        >
          {characterAvatar ? (
            <img
              src={characterAvatar}
              alt={characterName}
              className="h-14 w-14 rounded-full border-2 border-input object-cover"
            />
          ) : (
            <span className="bg-accent flex h-14 w-14 items-center justify-center rounded-full border-2 border-input">
              <User size={24} className="text-muted-foreground" />
            </span>
          )}
        </button>
        <div className="min-w-0 flex-1">
          <h2 className="text-lg font-semibold text-foreground truncate">{characterName}</h2>
          <p className="text-xs text-muted-foreground">Character Sheet</p>
        </div>
      </div>

      {/* Stats section */}
      <CollapsibleSection
        id="stats"
        title="Stats"
        icon={<BarChart3 size={16} />}
        count={(stats as CharacterStat[]).length}
        isOpen={openSections.stats}
        onToggle={() => toggleSection('stats')}
        loading={statsLoading}
      >
        {(stats as CharacterStat[]).length === 0 ? (
          <p className="py-1 text-center text-sm text-muted-foreground">No stats defined</p>
        ) : (
          <div className="flex flex-col gap-4">
            {Object.entries(statsByCategory).map(([category, categoryStats]) => (
              <div key={category}>
                {Object.keys(statsByCategory).length > 1 && (
                  <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    {category}
                  </h4>
                )}
                <div className="flex flex-col gap-2">
                  {narratorMode
                    ? categoryStats.map((stat) => (
                        <EditableStatRow
                          key={stat.statId}
                          stat={stat}
                          conversationId={conversationId}
                          characterId={characterId}
                        />
                      ))
                    : categoryStats.map((stat) => (
                        <StatBar key={stat.statId} stat={stat} />
                      ))
                  }
                </div>
              </div>
            ))}
          </div>
        )}
      </CollapsibleSection>

      {/* Relationships section */}
      <CollapsibleSection
        id="relationships"
        title="Relationships"
        icon={<Users size={16} />}
        count={(relationships as CharacterRelationship[]).length}
        isOpen={openSections.relationships}
        onToggle={() => toggleSection('relationships')}
        loading={relsLoading}
      >
        {(relationships as CharacterRelationship[]).length === 0 ? (
          <p className="py-1 text-center text-sm text-muted-foreground">No relationships defined</p>
        ) : narratorMode ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {(relationships as CharacterRelationship[]).map((rel) => (
              <EditableRelationshipRow
                key={rel.relationshipId}
                relationship={rel}
                conversationId={conversationId}
              />
            ))}
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {(relationships as CharacterRelationship[]).map((rel) => (
              <RelationshipCard key={rel.relationshipId} relationship={rel} />
            ))}
          </div>
        )}
      </CollapsibleSection>

      {/* Inventory section */}
      <CollapsibleSection
        id="inventory"
        title="Inventory"
        icon={<Package size={16} />}
        count={(inventory as CharacterInventoryItem[]).length}
        isOpen={openSections.inventory}
        onToggle={() => toggleSection('inventory')}
        loading={invLoading}
      >
        <InventoryList conversationId={conversationId} characterId={characterId} narratorMode={narratorMode} />
      </CollapsibleSection>
    </div>
  );
}
