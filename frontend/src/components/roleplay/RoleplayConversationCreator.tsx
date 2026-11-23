import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { Plus, Wand2, Users, Settings, X, BookOpen, Search } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import { Select } from '@/components/ui/Select';
import { Switch } from '@/components/ui/Switch';
import { Modal } from '@/components/ui/Modal';
import { Card } from '@/components/ui/Card';
import { RosterPicker } from '@/components/characters/RosterPicker';
import { useCreateRoleplayConversation, useStoryTemplates } from '@/hooks/useRoleplay';
import { useCharacters, useCharacterGroups } from '@/hooks';
import { cn } from '@/lib/utils';
import type { Character, StoryTemplate, TurnMode } from '@/graphql/types';

interface RoleplayConversationCreatorProps {
  isOpen: boolean;
  onClose: () => void;
  onCreated?: (conversationId: string) => void;
  initialTemplate?: StoryTemplate | null;
  /** Entry-point preset: solo = exactly one character, group = two or more. */
  initialMode?: 'solo' | 'group';
}

const RP_MODE_OPTIONS = [
  { value: 'narrative', label: 'Narrative' },
  { value: 'freeform', label: 'Freeform' },
  { value: 'guided', label: 'Guided' },
];

const TURN_MODE_OPTIONS = [
  { value: 'RANDOM', label: 'Random' },
  { value: 'ROUND_ROBIN', label: 'Round Robin' },
  { value: 'WEIGHTED', label: 'Weighted' },
];

export default function RoleplayConversationCreator({
  isOpen,
  onClose,
  onCreated,
  initialTemplate,
  initialMode,
}: RoleplayConversationCreatorProps) {
  const { createRoleplayConversation, loading: creating } = useCreateRoleplayConversation();
  const { characters, loading: loadingCharacters } = useCharacters(undefined, 100, 0);
  const { templates, loading: loadingTemplates } = useStoryTemplates();
  const { rosters, loading: loadingRosters } = useCharacterGroups();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [selectedCharacterIds, setSelectedCharacterIds] = useState<string[]>([]);
  const [characterSearch, setCharacterSearch] = useState('');
  const [rpMode, setRpMode] = useState('narrative');
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [agentPipelineEnabled, setAgentPipelineEnabled] = useState(true);
  const [turnMode, setTurnMode] = useState<TurnMode>('ROUND_ROBIN');
  const [mode, setMode] = useState<'solo' | 'group' | null>(null);
  const [error, setError] = useState('');

  // Reset form and pre-fill from template when modal opens
  const prevIsOpenRef = useRef(false);
  useEffect(() => {
    if (isOpen && !prevIsOpenRef.current) {
      // Always start fresh when modal opens
      setTitle('');
      setDescription('');
      setSelectedCharacterIds([]);
      setCharacterSearch('');
      setRpMode('narrative');
      setSelectedTemplateId('');
      setAgentPipelineEnabled(true);
      setTurnMode('ROUND_ROBIN');
      setMode(initialMode ?? null);
      setError('');
      // Pre-fill from template if provided (e.g. from StoryTemplateSelector)
      if (initialTemplate) {
        setSelectedTemplateId(initialTemplate.templateId);
        setTitle(initialTemplate.name);
        if (initialTemplate.description) {
          setDescription(initialTemplate.description);
        }
      }
    }
    prevIsOpenRef.current = isOpen;
  }, [isOpen, initialTemplate, initialMode]);

  const selectedTemplate = templates?.find(
    (t: StoryTemplate) => t.templateId === selectedTemplateId
  ) ?? null;

  const toggleCharacter = useCallback((characterId: string) => {
    setSelectedCharacterIds((prev) => {
      if (prev.includes(characterId)) return prev.filter((id) => id !== characterId);
      // Solo adventures keep exactly one character: a new pick replaces it.
      return mode === 'solo' ? [characterId] : [...prev, characterId];
    });
  }, [mode]);

  const addCharacters = useCallback((characterIds: string[]) => {
    setSelectedCharacterIds((prev) => {
      const seen = new Set(prev);
      const next = [...prev];
      for (const id of characterIds) {
        if (!seen.has(id)) {
          seen.add(id);
          next.push(id);
        }
      }
      // Rosters belong to group stories; solo keeps the first character only.
      return mode === 'solo' ? next.slice(0, 1) : next;
    });
  }, [mode]);

  const removeCharacters = useCallback((characterIds: string[]) => {
    const drop = new Set(characterIds);
    setSelectedCharacterIds((prev) => prev.filter((id) => !drop.has(id)));
  }, []);

  const characterNames = useMemo(
    () => Object.fromEntries((characters ?? []).map((char: Character) => [char.id, char.name])),
    [characters]
  );

  const filteredCharacters = useMemo(() => {
    const term = characterSearch.trim().toLowerCase();
    if (!term) return characters ?? [];
    return (characters ?? []).filter(
      (char: Character) =>
        char.name.toLowerCase().includes(term) ||
        char.description?.toLowerCase().includes(term)
    );
  }, [characters, characterSearch]);

  const handleSubmit = async () => {
    setError('');

    if (selectedCharacterIds.length === 0) {
      setError('Select at least one character.');
      return;
    }
    if (mode === 'group' && selectedCharacterIds.length < 2) {
      setError('Pick at least two characters for a group story.');
      return;
    }

    try {
      const result = await createRoleplayConversation({
        title: title.trim() || undefined,
        description: description.trim() || undefined,
        characterIds: selectedCharacterIds,
        templateId: selectedTemplateId || undefined,
        rpMode,
        turnMode,
        agentPipelineEnabled,
      });

      const newConversationId = result?.data?.createRoleplayConversation?.id;
      if (newConversationId && onCreated) {
        onCreated(newConversationId);
      }

      handleReset();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create roleplay conversation.');
    }
  };

  const handleReset = () => {
    setTitle('');
    setDescription('');
    setSelectedCharacterIds([]);
    setCharacterSearch('');
    setRpMode('narrative');
    setSelectedTemplateId('');
    setAgentPipelineEnabled(true);
    setTurnMode('ROUND_ROBIN');
    setError('');
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={mode === 'solo' ? 'New Solo Adventure' : mode === 'group' ? 'New Group Story' : 'New Roleplay'}
      description={
        mode === 'solo'
          ? 'One character, your story — they guide you through the adventure.'
          : mode === 'group'
            ? 'Two or more characters take turns carrying the story.'
            : 'Create a new roleplay conversation with characters and settings.'
      }
      size="lg"
      footer={
        <div className="flex items-center justify-between">
          <Button variant="ghost" size="sm" onClick={handleReset} disabled={creating}>
            Reset
          </Button>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={onClose} disabled={creating}>
              Cancel
            </Button>
            <Button
              variant="primary"
              size="sm"
              onClick={handleSubmit}
              isLoading={creating}
              loadingText="Creating..."
              leftIcon={<Plus size={16} />}
              disabled={selectedCharacterIds.length === 0}
            >
              Create Roleplay
            </Button>
          </div>
        </div>
      }
    >
      <div className="space-y-5">
        {error && (
          <div className="rounded-lg border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {error}
          </div>
        )}

        {/* Title and Description */}
        <div className="space-y-3">
          <Input
            label="Title"
            placeholder="Enter a title for your roleplay..."
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
          <Textarea
            label="Description"
            placeholder="Describe the setting or premise (optional)..."
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            minRows={2}
            maxRows={4}
            autoResize
          />
        </div>

        {/* Character Selection */}
        <div>
          <div className="mb-2 flex items-center justify-between gap-2">
            <label className="block text-sm font-medium text-foreground">
              <Users size={14} className="mr-1.5 inline-block" />
              Characters
            </label>
            <div className="flex items-center gap-2">
              <span
                aria-live="polite"
                className={cn(
                  'text-xs',
                  selectedCharacterIds.length > 0 ? 'text-muted-foreground' : 'sr-only'
                )}
              >
                {selectedCharacterIds.length} selected
              </span>
              {selectedCharacterIds.length > 0 && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setSelectedCharacterIds([])}
                  disabled={creating}
                >
                  Clear
                </Button>
              )}
            </div>
          </div>

          {!loadingCharacters &&
            (characters?.length ?? 0) > 0 &&
            selectedCharacterIds.length === 0 && (
              <p className="mb-2 text-xs text-muted-foreground">
                {mode === 'solo'
                  ? 'Pick one character to adventure with.'
                  : mode === 'group'
                    ? 'Pick two or more characters — they take turns speaking.'
                    : 'Pick a roster or individual characters.'}
              </p>
            )}

          {selectedCharacterIds.length > 3 && (
            <p className="mb-2 text-xs text-muted-foreground">
              The story opens with a few characters and introduces the rest as it develops.
            </p>
          )}

          {mode !== 'solo' && (
            <RosterPicker
              rosters={rosters}
              selectedCharacterIds={selectedCharacterIds}
              onAdd={addCharacters}
              onRemove={removeCharacters}
              characterNames={characterNames}
              loading={loadingRosters}
              className="mb-3"
            />
          )}

          {loadingCharacters ? (
            <div className="animate-pulse space-y-2">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-10 rounded-lg bg-muted" />
              ))}
            </div>
          ) : !characters || characters.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No characters available. Generate a roster on the Characters page, then return here.
            </p>
          ) : (
            <>
              {characters.length > 6 && (
                <div className="relative mb-2">
                  <Search
                    size={16}
                    className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                  />
                  <input
                    type="search"
                    value={characterSearch}
                    onChange={(e) => setCharacterSearch(e.target.value)}
                    placeholder="Search characters..."
                    aria-label="Search characters"
                    className={cn(
                      'w-full rounded-lg border border-border bg-muted/50 py-2 pl-9 pr-3 text-sm',
                      'text-foreground placeholder-muted-foreground',
                      'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20'
                    )}
                  />
                </div>
              )}
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {filteredCharacters.map((char: Character) => {
                  const isSelected = selectedCharacterIds.includes(char.id);
                  return (
                    <button
                      key={char.id}
                      type="button"
                      onClick={() => toggleCharacter(char.id)}
                      aria-pressed={isSelected}
                      aria-label={`${isSelected ? 'Remove' : 'Add'} ${char.name}`}
                      className={cn(
                        'flex items-center gap-3 rounded-lg border px-3 py-2 text-left transition-all',
                        isSelected
                          ? 'border-primary bg-primary/10 text-foreground'
                          : 'border-border bg-muted/50 text-muted-foreground hover:border-border-hover hover:text-foreground'
                      )}
                    >
                      <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-muted text-xs font-bold uppercase">
                        {char.avatar ? (
                          <img
                            src={char.avatar}
                            alt=""
                            className="h-8 w-8 rounded-full object-cover"
                          />
                        ) : (
                          char.name.charAt(0)
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{char.name}</p>
                        {char.description && (
                          <p className="truncate text-xs text-muted-foreground">
                            {char.description}
                          </p>
                        )}
                      </div>
                      {isSelected && (
                        <X size={14} className="flex-shrink-0 text-primary" />
                      )}
                    </button>
                  );
                })}
              </div>
              {filteredCharacters.length === 0 && (
                <p className="text-sm text-muted-foreground">No characters match that search.</p>
              )}
            </>
          )}
        </div>

        {/* RP Mode and Turn Mode (turn mode only matters with 2+ characters) */}
        <div className={cn('grid grid-cols-1 gap-3', mode !== 'solo' && 'sm:grid-cols-2')}>
          <Select
            label="RP Mode"
            options={RP_MODE_OPTIONS}
            value={rpMode}
            onChange={(e) => setRpMode(e.target.value)}
            leftIcon={<Settings size={14} />}
          />
          {mode !== 'solo' && (
            <Select
              label="Turn Mode"
              options={TURN_MODE_OPTIONS}
              value={turnMode}
              onChange={(e) => setTurnMode(e.target.value as TurnMode)}
              leftIcon={<Users size={14} />}
            />
          )}
        </div>

        {/* Template Selection */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            <BookOpen size={14} className="mr-1.5 inline-block" />
            Story Template (optional)
          </label>
          {loadingTemplates ? (
            <div className="h-10 animate-pulse rounded-lg bg-muted" />
          ) : !templates || templates.length === 0 ? (
            <p className="text-sm text-muted-foreground">No templates available.</p>
          ) : (
            <Select
              options={[
                { value: '', label: 'No Template' },
                ...templates.map((t: StoryTemplate) => ({
                  value: t.templateId,
                  label: `${t.name} (${t.genre})`,
                })),
              ]}
              value={selectedTemplateId}
              onChange={(e) => {
                const newId = e.target.value;
                setSelectedTemplateId(newId);
                if (newId) {
                  const tpl = templates.find((t: StoryTemplate) => t.templateId === newId);
                  if (tpl) {
                    setTitle(tpl.name);
                    setDescription(tpl.description ?? '');
                  }
                }
              }}
              placeholder="Select a template"
            />
          )}

          {selectedTemplate && (
            <Card padding="sm" className="mt-3 space-y-2">
              <div className="flex items-center justify-between">
                <h4 className="text-sm font-semibold text-foreground">{selectedTemplate.name}</h4>
                <span className="rounded-full bg-primary/15 px-2 py-0.5 text-xs font-medium text-primary">
                  {selectedTemplate.genre}
                </span>
              </div>
              {selectedTemplate.description && (
                <p className="text-xs leading-relaxed text-muted-foreground">
                  {selectedTemplate.description}
                </p>
              )}
              {/* Template rules / defaultWorldState are kept server-side (marked
                  @Ignore on the StoryTemplate model) and applied via
                  initializeFromTemplate; they are not exposed to the client. */}
            </Card>
          )}
        </div>

        {/* Agent Pipeline Toggle */}
        <Card padding="sm" className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Wand2 size={16} className="text-primary" />
            <div>
              <p className="text-sm font-medium text-foreground">Agent Pipeline</p>
              <p className="text-xs text-muted-foreground">
                Enable multi-agent processing for richer narrative generation.
              </p>
            </div>
          </div>
          <Switch
            checked={agentPipelineEnabled}
            onChange={(e) => setAgentPipelineEnabled(e.target.checked)}
            size="sm"
          />
        </Card>
      </div>
    </Modal>
  );
}
