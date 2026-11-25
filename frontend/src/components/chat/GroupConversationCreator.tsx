import { useState, useMemo } from 'react';
import { Bot, Search, X, Check, Users, Shuffle, RotateCcw, Scale, Play } from 'lucide-react';
import { cn } from '@/lib/utils';
import { toast } from 'react-hot-toast';
import { useCharacters, useCharacterGroups } from '@/hooks';
import { RosterPicker } from '@/components/characters/RosterPicker';
import { useMutation } from '@apollo/client';
import { CREATE_GROUP_CONVERSATION_MUTATION } from '@/graphql/operations';
import type { Character } from '@/graphql/types';

type TurnMode = 'RANDOM' | 'ROUND_ROBIN' | 'WEIGHTED';

const MAX_CHARACTERS = 10;

interface GroupConversationCreatorProps {
  onCreated: (conversationId: string) => void;
  onClose: () => void;
}

export const GroupConversationCreator = ({
  onCreated,
  onClose
}: GroupConversationCreatorProps) => {
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCharacters, setSelectedCharacters] = useState<Character[]>([]);
  const [characterWeights, setCharacterWeights] = useState<Record<string, number>>({});
  const [title, setTitle] = useState('');
  const [turnMode, setTurnMode] = useState<TurnMode>('RANDOM');
  const [maxConsecutive, setMaxConsecutive] = useState(3);

  // Load all characters the user has access to (own characters + public ones)
  // The isPublic filter will be used later for a public character library feature
  const { characters: availableCharacters, loading } = useCharacters(
    undefined, // No filter - show all accessible characters
    100,
    0
  );
  const { rosters, loading: loadingRosters } = useCharacterGroups();

  const [createGroupConversation, { loading: creating }] = useMutation(
    CREATE_GROUP_CONVERSATION_MUTATION
  );

  // Filter characters based on search
  const filteredCharacters = useMemo(() => {
    if (!availableCharacters) return [];
    return availableCharacters.filter((char: Character) =>
      char.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      char.description?.toLowerCase().includes(searchTerm.toLowerCase())
    );
  }, [availableCharacters, searchTerm]);

  const characterNames = useMemo(
    () =>
      Object.fromEntries(
        (availableCharacters ?? []).map((char: Character) => [char.id, char.name])
      ),
    [availableCharacters]
  );

  // Toggle character selection
  const toggleCharacter = (character: Character) => {
    setSelectedCharacters(prev => {
      const exists = prev.find(c => c.id === character.id);
      if (exists) {
        // Remove character and their weight
        setCharacterWeights(w => {
          const newWeights = { ...w };
          delete newWeights[character.id];
          return newWeights;
        });
        return prev.filter(c => c.id !== character.id);
      }
      if (prev.length >= MAX_CHARACTERS) {
        toast.error(`A group holds up to ${MAX_CHARACTERS} characters.`);
        return prev;
      }
      // Add character with default weight
      setCharacterWeights(w => ({ ...w, [character.id]: 1.0 }));
      return [...prev, character];
    });
  };

  /** Adds roster members in saved order, respecting the group limit. */
  const addCharacters = (characterIds: string[]) => {
    const known = new Map<string, Character>(
      (availableCharacters ?? []).map((char: Character) => [char.id, char])
    );
    const seen = new Set(selectedCharacters.map((c) => c.id));
    const next = [...selectedCharacters];
    for (const id of characterIds) {
      if (next.length >= MAX_CHARACTERS) break;
      const character = known.get(id);
      if (!character || seen.has(id)) continue;
      seen.add(id);
      next.push(character);
    }
    if (next.length === selectedCharacters.length) return;
    setSelectedCharacters(next);
    setCharacterWeights((prev) => {
      const weights = { ...prev };
      for (const character of next) {
        if (weights[character.id] == null) weights[character.id] = 1.0;
      }
      return weights;
    });
  };

  const removeCharacters = (characterIds: string[]) => {
    const drop = new Set(characterIds);
    setSelectedCharacters(prev => prev.filter((c) => !drop.has(c.id)));
    setCharacterWeights(prev => {
      const next = { ...prev };
      for (const id of characterIds) delete next[id];
      return next;
    });
  };

  const clearCharacters = () => {
    setSelectedCharacters([]);
    setCharacterWeights({});
  };

  // Update character weight
  const updateWeight = (characterId: string, weight: number) => {
    setCharacterWeights(prev => ({
      ...prev,
      [characterId]: Math.max(0.1, Math.min(5.0, weight))
    }));
  };

  // Handle create group
  const handleCreate = async () => {
    if (selectedCharacters.length < 2) return;

    try {
      // Build weights array for weighted mode
      const charactersWithWeights = turnMode === 'WEIGHTED'
        ? selectedCharacters.map(c => ({
            characterId: c.id,
            weight: characterWeights[c.id] ?? 1.0
          }))
        : undefined;

      const result = await createGroupConversation({
        variables: {
          input: {
            title: title || `Group Chat (${selectedCharacters.map(c => c.name).join(', ')})`,
            characterIds: selectedCharacters.map(c => c.id),
            charactersWithWeights,
            turnMode,
            maxConsecutiveResponses: maxConsecutive,
          }
        }
      });

      if (result.data?.createGroupConversation?.id) {
        toast.success('Group conversation created');
        onCreated(result.data.createGroupConversation.id);
      }
    } catch (error) {
      console.error('Failed to create group conversation:', error);
      toast.error('Failed to create group conversation');
    }
  };

  const turnModes = [
    { id: 'RANDOM', name: 'Random', icon: Shuffle, description: 'Characters respond in random order' },
    { id: 'ROUND_ROBIN', name: 'Round Robin', icon: RotateCcw, description: 'Characters take turns in sequence' },
    { id: 'WEIGHTED', name: 'Weighted', icon: Scale, description: 'Characters respond based on weights' },
  ] as const;

  return (
    <div className={cn(
      'fixed inset-0 z-50 flex items-center justify-center p-4',
      'bg-black/50 backdrop-blur-sm'
    )}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="group-creator-title"
        className={cn(
          'flex w-full max-w-3xl max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-h-[90dvh]',
          'rounded-2xl border border-border bg-background',
          'shadow-2xl animate-fade-up'
        )}
      >
        {/* Header */}
        <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border p-4">
          <div className="flex min-w-0 items-center gap-3">
            <div className={cn(
              'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
              'bg-gradient-to-br from-secondary to-secondary-hover shadow-lg'
            )}>
              <Users size={20} className="text-secondary-foreground" />
            </div>
            <div className="min-w-0">
              <h2 id="group-creator-title" className="text-lg font-semibold text-foreground">Create Group Chat</h2>
              <p className="text-xs text-muted-foreground">Select 2-{MAX_CHARACTERS} characters to chat with</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className={cn(
              'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
          >
            <X size={20} />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4 space-y-4">
          {/* Title input */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Group Name (optional)
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="My Group Chat"
              className={cn(
                'w-full rounded-xl border border-border bg-muted/50',
                'px-4 py-2.5 text-sm text-foreground placeholder-muted-foreground',
                'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                'transition-all duration-150'
              )}
            />
          </div>

          {/* Turn mode selection */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Turn Mode
            </label>
            <div className="grid grid-cols-3 gap-2">
              {turnModes.map((mode) => {
                const Icon = mode.icon;
                return (
                  <button
                    key={mode.id}
                    type="button"
                    onClick={() => setTurnMode(mode.id as TurnMode)}
                    aria-pressed={turnMode === mode.id}
                    className={cn(
                      'flex flex-col items-center gap-1 rounded-xl border p-2 sm:gap-2 sm:p-3',
                      'transition-all duration-150',
                      turnMode === mode.id
                        ? 'border-primary bg-primary/10'
                        : 'border-border bg-muted/30 hover:bg-accent/50'
                    )}
                  >
                    <Icon size={20} className={turnMode === mode.id ? 'text-primary' : 'text-muted-foreground'} />
                    <span className="text-center text-sm font-medium">{mode.name}</span>
                    <span className="text-xs text-muted-foreground text-center max-sm:hidden">{mode.description}</span>
                  </button>
                );
              })}
            </div>
            <p className="mt-1.5 text-xs text-muted-foreground sm:hidden">
              {turnModes.find((mode) => mode.id === turnMode)?.description}
            </p>
          </div>

          {/* Max consecutive responses */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Max Consecutive Responses: {maxConsecutive}
            </label>
            <input
              type="range"
              min={1}
              max={10}
              value={maxConsecutive}
              onChange={(e) => setMaxConsecutive(parseInt(e.target.value))}
              className="w-full accent-primary"
            />
            <p className="text-xs text-muted-foreground mt-1">
              Maximum times one character can respond before another must speak
            </p>
          </div>

          {/* Roster shortcut */}
          <RosterPicker
            rosters={rosters}
            selectedCharacterIds={selectedCharacters.map((c) => c.id)}
            onAdd={addCharacters}
            onRemove={removeCharacters}
            characterNames={characterNames}
            loading={loadingRosters}
            maxSelectable={MAX_CHARACTERS}
          />

          {selectedCharacters.length > 3 && (
            <p className="text-xs text-muted-foreground">
              The chat opens with a few characters and introduces the rest as it develops.
            </p>
          )}

          {/* Selected characters with weight configuration */}
          {selectedCharacters.length > 0 && (
            <div>
              <div className="mb-1.5 flex items-center justify-between gap-2">
                <label className="block text-sm font-medium text-foreground">
                  Selected Characters ({selectedCharacters.length}/{MAX_CHARACTERS})
                  {turnMode === 'WEIGHTED' && (
                    <span className="text-xs text-muted-foreground ml-2">
                      - Adjust weights to control response frequency
                    </span>
                  )}
                </label>
                <button
                  type="button"
                  onClick={clearCharacters}
                  className="shrink-0 text-xs text-muted-foreground transition-colors hover:text-foreground max-md:-my-2 max-md:min-h-10 max-md:px-2"
                >
                  Clear
                </button>
              </div>
              <div className={cn(
                turnMode === 'WEIGHTED' ? 'space-y-2' : 'flex flex-wrap gap-2'
              )}>
                {selectedCharacters.map((char) => (
                  <div
                    key={char.id}
                    className={cn(
                      'flex items-center gap-2 rounded-lg',
                      'bg-secondary/20 border border-secondary/30',
                      turnMode === 'WEIGHTED' ? 'p-3' : 'px-3 py-1.5'
                    )}
                  >
                    {char.avatar ? (
                      <img src={char.avatar} alt={char.name} className="h-6 w-6 rounded-full object-cover" />
                    ) : (
                      <div className="flex h-6 w-6 items-center justify-center rounded-full bg-secondary">
                        <Bot size={12} className="text-secondary-foreground" />
                      </div>
                    )}
                    <span className="min-w-[80px] max-w-[6rem] truncate text-sm font-medium sm:max-w-[10rem]">{char.name}</span>

                    {turnMode === 'WEIGHTED' && (
                      <div className="flex min-w-0 flex-1 items-center gap-2 sm:ml-2 sm:gap-3">
                        <input
                          type="range"
                          min={0.1}
                          max={5}
                          step={0.1}
                          value={characterWeights[char.id] ?? 1.0}
                          onChange={(e) => updateWeight(char.id, parseFloat(e.target.value))}
                          aria-label={`${char.name} response weight`}
                          className="min-w-0 flex-1 accent-secondary h-1.5"
                        />
                        <span className="w-10 shrink-0 text-right text-xs text-muted-foreground">
                          {(characterWeights[char.id] ?? 1.0).toFixed(1)}x
                        </span>
                      </div>
                    )}

                    <button
                      type="button"
                      onClick={() => toggleCharacter(char)}
                      aria-label={`Remove ${char.name}`}
                      className="-my-2.5 -mr-2.5 ml-auto inline-flex h-10 w-10 shrink-0 items-center justify-center text-muted-foreground hover:text-destructive transition-colors"
                    >
                      <X size={14} />
                    </button>
                  </div>
                ))}
              </div>
              {turnMode === 'WEIGHTED' && selectedCharacters.length >= 2 && (
                <p className="text-xs text-muted-foreground mt-2">
                  Higher weight = more likely to respond. A character with 2.0x weight responds twice as often as one with 1.0x.
                </p>
              )}
            </div>
          )}

          {/* Character search */}
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={18} />
            <input
              type="text"
              placeholder="Search characters..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className={cn(
                'w-full rounded-xl border border-border bg-muted/50',
                'py-2.5 pl-10 pr-3 text-sm text-foreground placeholder-muted-foreground',
                'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                'transition-all duration-150'
              )}
            />
          </div>

          {/* Character grid */}
          {loading ? (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {[...Array(6)].map((_, i) => (
                <div key={i} className="animate-pulse rounded-xl bg-muted/30 h-24" />
              ))}
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {filteredCharacters.map((character: Character) => {
                const isSelected = selectedCharacters.some(c => c.id === character.id);
                return (
                  <button
                    key={character.id}
                    type="button"
                    onClick={() => toggleCharacter(character)}
                    aria-pressed={isSelected}
                    className={cn(
                      'group relative rounded-xl border p-3 text-left',
                      'transition-all duration-200',
                      isSelected
                        ? 'border-secondary bg-secondary/10 shadow-lg shadow-secondary/10'
                        : 'border-border/50 bg-card hover:border-border hover:bg-accent/50'
                    )}
                  >
                    <div className="flex items-center gap-3">
                      {character.avatar ? (
                        <img
                          src={character.avatar}
                          alt={character.name}
                          className="h-10 w-10 rounded-xl object-cover shadow-md"
                        />
                      ) : (
                        <div className={cn(
                          'flex h-10 w-10 items-center justify-center rounded-xl',
                          'bg-gradient-to-br from-secondary to-secondary-hover shadow-md'
                        )}>
                          <Bot size={20} className="text-secondary-foreground" />
                        </div>
                      )}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center justify-between">
                          <h4 className="font-medium text-foreground truncate">{character.name}</h4>
                          {isSelected && (
                            <div className="flex h-5 w-5 items-center justify-center rounded-full bg-secondary">
                              <Check size={12} className="text-secondary-foreground" />
                            </div>
                          )}
                        </div>
                        <p className="text-xs text-muted-foreground line-clamp-1">
                          {character.description || 'No description'}
                        </p>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex shrink-0 flex-col gap-3 border-t border-border p-4 bg-background-secondary/50 sm:flex-row sm:items-center sm:justify-between">
          <span className="text-sm text-muted-foreground">
            {selectedCharacters.length < 2
              ? `Select ${2 - selectedCharacters.length} more character${2 - selectedCharacters.length > 1 ? 's' : ''}`
              : `${selectedCharacters.length} characters selected`}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onClose}
              className={cn(
                'rounded-lg px-4 py-2 text-sm font-medium max-sm:min-h-11',
                'bg-muted text-muted-foreground',
                'hover:bg-accent hover:text-foreground',
                'transition-colors duration-150'
              )}
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleCreate}
              disabled={selectedCharacters.length < 2 || creating}
              className={cn(
                'flex flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-lg px-4 py-2 text-sm font-medium sm:flex-none max-sm:min-h-11',
                'bg-primary text-primary-foreground',
                'hover:bg-primary-hover',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'transition-colors duration-150'
              )}
            >
              <Play size={16} />
              {creating ? 'Creating...' : 'Start Group Chat'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
