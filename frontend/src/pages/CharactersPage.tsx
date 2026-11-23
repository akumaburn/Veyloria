import { useMutation, useQuery } from '@apollo/client';
import { CharacterAlignmentModal } from '@/components/characters/CharacterAlignmentModal';
import { CharacterRelationshipsModal } from '@/components/characters/CharacterRelationshipsModal';
import { CharacterRosterModal } from '@/components/characters/CharacterRosterModal';
import {
  actorAlignmentKey,
  alignmentMeta,
  type AlignmentSummaryData,
} from '@/components/characters/alignment';
import {
  CHARACTER_ALIGNMENT_SUMMARY_QUERY,
  CHARACTER_GROUPS_QUERY,
  DELETE_CHARACTER_GROUP_MUTATION,
  REMOVE_CHARACTER_FROM_ROSTER_MUTATION,
  RENAME_CHARACTER_GROUP_MUTATION,
} from '@/graphql/operations';
import { useCharacterGeneration } from '@/hooks/useCharacterGeneration';
import React, { useState, useEffect } from 'react';
import { Textarea } from '../components/ui/Textarea';
import { Button } from '../components/ui/Button';
import { LoadingSpinner } from '../components/ui/LoadingStates';
import { EmptyState } from '../components/ui/EmptyStates';
import { toast } from 'react-hot-toast';
import { cn } from '@/lib/utils';
import { useCharacterStore } from '@/stores/characterStore';
import {
  Plus,
  Sparkles,
  Search,
  Bot,
  Loader2,
  Star,
  Trash2,
  Users,
  Edit3,
  Copy,
  X,
  ChevronRight,
  Heart,
  Scale,
} from 'lucide-react';
import type { Character, CharacterGroup } from '@/graphql/types';
import CharacterForm, {
  type CharacterFormData,
  defaultFormData,
  formDataFromCharacter,
} from '@/components/shared/CharacterForm';
import { PageHeader } from '@/components/layout/PageHeader';

const filterCategories = [
  { value: 'all', label: 'All', icon: Users },
  { value: 'general', label: 'General', icon: Bot },
  { value: 'assistant', label: 'Assistant', icon: Bot },
  { value: 'creative', label: 'Creative', icon: Sparkles },
  { value: 'educational', label: 'Educational', icon: Bot },
  { value: 'technical', label: 'Technical', icon: Bot },
  { value: 'roleplay', label: 'Roleplay', icon: Bot },
];

const CharactersPage: React.FC = () => {
  const {
    characters,
    isLoading,
    fetchCharacters,
    createCharacter,
    updateCharacter,
    deleteCharacter,
    cloneCharacter,
  } = useCharacterStore();

  const { generateCharacter, cancelGeneration, isGenerating } = useCharacterGeneration();

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('all');
  const [showEditor, setShowEditor] = useState(false);
  const [editingCharacter, setEditingCharacter] = useState<Character | null>(null);
  const [formData, setFormData] = useState<CharacterFormData>(defaultFormData);
  const [showGenerateModal, setShowGenerateModal] = useState(false);
  const [showRosterModal, setShowRosterModal] = useState(false);
  const [relationshipCharacter, setRelationshipCharacter] = useState<string | null>(null);
  const [showRelationships, setShowRelationships] = useState(false);
  const [alignmentCharacter, setAlignmentCharacter] = useState<string | null>(null);
  const [showAlignment, setShowAlignment] = useState(false);
  const [generatePrompt, setGeneratePrompt] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [view, setView] = useState<'groups' | 'characters'>('groups');

  useEffect(() => {
    fetchCharacters('all', 1, 200);
  }, [fetchCharacters]);

  const { data: alignmentData } = useQuery<AlignmentSummaryData>(
    CHARACTER_ALIGNMENT_SUMMARY_QUERY,
    {
      fetchPolicy: 'cache-and-network',
      errorPolicy: 'ignore',
    },
  );
  const alignmentByCharacter = new Map(
    (alignmentData?.characterAlignments ?? []).map((record) => [
      actorAlignmentKey(record.characterId),
      record,
    ]),
  );

  const {
    data: groupsData,
    refetch: refetchGroups,
  } = useQuery<{ characterGroups: CharacterGroup[] }>(CHARACTER_GROUPS_QUERY, {
    fetchPolicy: 'cache-and-network',
    errorPolicy: 'ignore',
  });
  const [renameGroup] = useMutation(RENAME_CHARACTER_GROUP_MUTATION);
  const [deleteGroup] = useMutation(DELETE_CHARACTER_GROUP_MUTATION);
  const [removeGroupMember] = useMutation(REMOVE_CHARACTER_FROM_ROSTER_MUTATION);

  const charactersById = new Map(characters.map((character) => [character.id, character]));
  const groups = (groupsData?.characterGroups ?? []).map((group) => ({
    ...group,
    members: group.characterIds
      .map((id) => charactersById.get(id))
      .filter((member): member is Character => Boolean(member)),
  }));
  const filteredGroups = groups.filter((group) => {
    const query = searchQuery.toLowerCase();
    return (
      group.name.toLowerCase().includes(query) ||
      group.description?.toLowerCase().includes(query) ||
      group.setting?.toLowerCase().includes(query) ||
      group.members.some((member) => member.name.toLowerCase().includes(query))
    );
  });

  const filteredCharacters = characters.filter((char) => {
    const matchesSearch =
      char.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      char.description?.toLowerCase().includes(searchQuery.toLowerCase());
    if (selectedCategory === 'all') return matchesSearch;
    return matchesSearch && char.category === selectedCategory;
  });

  const handleCreateNew = () => {
    cancelGeneration();
    setSaveSuccess(false);
    setEditingCharacter(null);
    setFormData(defaultFormData);
    setShowEditor(true);
  };

  const handleEdit = (character: Character) => {
    cancelGeneration();
    setSaveSuccess(false);
    setEditingCharacter(character);
    const savedAlignment = alignmentByCharacter.get(character.id);
    setFormData(formDataFromCharacter(character, savedAlignment));
    setShowEditor(true);
  };

  const handleSave = async () => {
    if (!formData.name.trim() || !formData.systemPrompt.trim()) {
      toast.error('Name and system prompt are required');
      return;
    }

    setIsSaving(true);
    try {
      if (editingCharacter) {
        await updateCharacter(editingCharacter.id, formData);
      } else {
        const created = await createCharacter(formData);
        if (!created) return;
      }
      setSaveSuccess(true);
      setTimeout(() => {
        setSaveSuccess(false);
        setShowEditor(false);
      }, 1000);
    } catch {
      toast.error('Failed to save character');
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (confirm('Are you sure you want to delete this character?')) {
      try {
        await deleteCharacter(id);
        await refetchGroups();
        toast.success('Character deleted');
      } catch {
        toast.error('Failed to delete character');
      }
    }
  };

  const handleClone = async (id: string, name: string) => {
    const newName = prompt('Enter name for the cloned character:', `${name} (Copy)`);
    if (newName) {
      try {
        await cloneCharacter(id, newName);
        toast.success('Character cloned');
      } catch {
        toast.error('Failed to clone character');
      }
    }
  };

  const handleRenameGroup = async (group: CharacterGroup) => {
    const name = prompt('Roster name:', group.name);
    if (!name || !name.trim() || name.trim() === group.name) return;
    try {
      await renameGroup({
        variables: { id: group.id, name: name.trim(), description: group.description },
      });
      await refetchGroups();
    } catch {
      toast.error('Failed to rename roster');
    }
  };

  const handleDeleteGroup = async (group: CharacterGroup) => {
    if (!confirm(`Delete the roster “${group.name}”? The characters stay in your collection.`)) return;
    try {
      await deleteGroup({ variables: { id: group.id } });
      await refetchGroups();
      toast.success('Roster deleted; characters kept');
    } catch {
      toast.error('Failed to delete roster');
    }
  };

  const handleRemoveGroupMember = async (group: CharacterGroup, character: Character) => {
    if (!confirm(`Remove ${character.name} from “${group.name}”? The character is not deleted.`))
      return;
    try {
      await removeGroupMember({ variables: { groupId: group.id, characterId: character.id } });
      await refetchGroups();
    } catch {
      toast.error('Failed to remove character from roster');
    }
  };

  const closeGenerateModal = () => {
    cancelGeneration();
    setShowGenerateModal(false);
  };

  const handleGenerate = async () => {
    if (!generatePrompt.trim()) {
      toast.error('Please describe the character you want');
      return;
    }

    try {
      const draft = await generateCharacter(generatePrompt);
      const generated = draft?.character;
      if (generated && draft) {
        setEditingCharacter(null);
        setSaveSuccess(false);
        setFormData(formDataFromCharacter(generated, draft));
        setShowGenerateModal(false);
        setShowEditor(true);
        toast.success('Character generated! Review and save.');
      }
    } catch {
      toast.error('Failed to generate character');
    }
  };

  // Editor view
  if (showEditor) {
    return (
      <div className="flex h-full min-h-0 flex-col">
        <PageHeader
          icon={Bot}
          title={editingCharacter ? 'Edit Character' : 'Create Character'}
          measure="max-w-7xl"
          lead={
            <Button
              variant="ghost"
              size="icon"
              aria-label="Back to characters"
              title="Back to characters"
              onClick={() => setShowEditor(false)}
            >
              <ChevronRight size={20} className="rotate-180" />
            </Button>
          }
        />

        <div className="custom-scrollbar flex-1 overflow-y-auto px-4 py-8 sm:px-6">
          <div className="mx-auto max-w-7xl">
            <CharacterForm
              formData={formData}
              onChange={setFormData}
              onSubmit={() => void handleSave()}
              isLoading={isSaving}
              submitLabel={editingCharacter ? 'Save Changes' : 'Create Character'}
              saveSuccess={saveSuccess}
              characterId={editingCharacter?.id}
              alignment={alignmentByCharacter.get(editingCharacter?.id ?? '')?.alignment ?? null}
              alignmentPoints={
                editingCharacter
                  ? {
                      lawfulness: alignmentByCharacter.get(editingCharacter.id)?.lawfulness ?? 0,
                      goodness: alignmentByCharacter.get(editingCharacter.id)?.goodness ?? 0,
                    }
                  : null
              }
              onCancel={() => setShowEditor(false)}
            />
          </div>
        </div>
      </div>
    );
  }

  // Grid/list view
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader
        icon={Bot}
        title="Characters"
        description="Create characters, generate rosters, and keep each cast together"
        measure="max-w-7xl"
        actions={
          <Button
            variant="primary"
            leftIcon={<Plus size={16} />}
            onClick={handleCreateNew}
            className="w-full sm:w-auto"
          >
            Create Character
          </Button>
        }
      />

      <div className="custom-scrollbar flex-1 overflow-y-auto px-4 py-8 sm:px-6">
        <div className="mx-auto max-w-7xl">
          {/* View switcher and collection tools */}
          <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
            <div
              role="tablist"
              aria-label="Rosters or characters"
              className="border-border bg-muted/40 inline-flex rounded-xl border p-1"
            >
              <button
                role="tab"
                aria-selected={view === 'groups'}
                onClick={() => setView('groups')}
                className={cn(
                  'rounded-lg px-4 py-2 text-sm font-medium transition-colors',
                  view === 'groups'
                    ? 'bg-card text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                Rosters
                <span className="text-muted-foreground ml-2 text-xs">{groups.length}</span>
              </button>
              <button
                role="tab"
                aria-selected={view === 'characters'}
                onClick={() => setView('characters')}
                className={cn(
                  'rounded-lg px-4 py-2 text-sm font-medium transition-colors',
                  view === 'characters'
                    ? 'bg-card text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                Characters
                <span className="text-muted-foreground ml-2 text-xs">{characters.length}</span>
              </button>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                leftIcon={<Heart size={14} className="text-primary" />}
                onClick={() => {
                  setRelationshipCharacter(null);
                  setShowRelationships(true);
                }}
              >
                Relationships
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                leftIcon={<Scale size={14} className="text-primary" />}
                onClick={() => {
                  setAlignmentCharacter(null);
                  setShowAlignment(true);
                }}
              >
                Alignment
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                leftIcon={<Users size={14} className="text-primary" />}
                onClick={() => setShowRosterModal(true)}
              >
                Generate roster
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                leftIcon={<Sparkles size={14} className="text-secondary" />}
                onClick={() => setShowGenerateModal(true)}
              >
                Generate with AI
              </Button>
            </div>
          </div>

          {/* Filters */}
          <div className="mb-6 flex flex-col gap-4 sm:flex-row">
            <div className="max-w-lg flex-1">
              <div className="relative">
                <Search
                  className="text-muted-foreground absolute top-1/2 left-3 -translate-y-1/2"
                  size={18}
                />
                <input
                  type="search"
                  aria-label={view === 'groups' ? 'Search rosters' : 'Search characters'}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder={view === 'groups' ? 'Search rosters...' : 'Search characters...'}
                  className={cn(
                    'border-input bg-background w-full rounded-2xl border',
                    'text-foreground placeholder-muted-foreground h-11 py-0 pr-3 pl-10 text-sm',
                    'focus:border-primary focus:ring-primary/20 focus:ring-2 focus:outline-none',
                    'transition-all duration-150',
                  )}
                />
              </div>
            </div>

            <div className={cn('flex flex-wrap gap-2', view === 'groups' && 'hidden')}>
              {filterCategories.map((cat) => {
                const Icon = cat.icon;
                return (
                  <button
                    key={cat.value}
                    onClick={() => setSelectedCategory(cat.value)}
                    className={cn(
                      'flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium',
                      'whitespace-nowrap transition-colors duration-150',
                      selectedCategory === cat.value
                        ? 'bg-primary/15 text-primary'
                        : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                    )}
                  >
                    <Icon size={13} />
                    {cat.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Groups or characters */}
          {view === 'groups' ? (
            isLoading ? (
              <div className="flex justify-center py-12">
                <LoadingSpinner size="lg" />
              </div>
            ) : filteredGroups.length === 0 ? (
              <EmptyState
                icon={<Users />}
                title="No rosters yet"
                description="Generate a roster to keep its cast together. Characters you create alone still appear in the Characters tab."
                action={{
                  label: 'Generate roster',
                  onClick: () => setShowRosterModal(true),
                }}
              />
            ) : (
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                {filteredGroups.map((group) => (
                  <article
                    key={group.id}
                    className={cn(
                      'border-border bg-card rounded-2xl border p-5',
                      // A lone roster should claim the row: half-empty
                      // right column reads as a layout bug at desktop widths.
                      filteredGroups.length === 1 && 'lg:col-span-2'
                    )}
                  >
                    <div className="mb-3 flex items-start justify-between gap-3">
                      <div className="flex min-w-0 items-start gap-3">
                        <div className="from-primary/15 to-secondary/15 text-primary flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br">
                          <Users size={22} />
                        </div>
                        <div className="min-w-0">
                          <h3 className="text-foreground truncate font-semibold">{group.name}</h3>
                          <p className="text-muted-foreground text-xs">
                            {group.members.length} of {group.characterCount} characters · generated
                            roster
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => void handleRenameGroup(group)}
                          aria-label={`Rename ${group.name}`}
                          className="hover:bg-accent text-muted-foreground hover:text-foreground rounded-lg p-1.5"
                        >
                          <Edit3 size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => void handleDeleteGroup(group)}
                          aria-label={`Delete ${group.name}`}
                          className="hover:bg-destructive/10 text-muted-foreground hover:text-destructive rounded-lg p-1.5"
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>

                    {(group.description || group.setting) && (
                      <p className="text-muted-foreground mb-4 line-clamp-3 text-sm">
                        {group.description || group.setting}
                      </p>
                    )}

                    <ul className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                      {group.members.map((member) => {
                        const meta = alignmentMeta(alignmentByCharacter.get(member.id)?.alignment);
                        return (
                          <li
                            key={member.id}
                            className="border-border/60 bg-muted/20 flex items-center gap-2 rounded-lg border px-2 py-1.5"
                          >
                            <button
                              type="button"
                              onClick={() => handleEdit(member)}
                              className="flex min-w-0 flex-1 items-center gap-2 text-left"
                            >
                              {member.avatar ? (
                                <img
                                  src={member.avatar}
                                  alt=""
                                  className="h-7 w-7 shrink-0 rounded-md object-cover"
                                />
                              ) : (
                                <span className="bg-accent text-muted-foreground flex h-7 w-7 shrink-0 items-center justify-center rounded-md">
                                  <Bot size={14} />
                                </span>
                              )}
                              <span className="text-foreground min-w-0 truncate text-sm">
                                {member.name}
                              </span>
                              <span
                                className={cn(
                                  'ml-auto hidden shrink-0 items-center gap-1 rounded border px-1.5 py-0.5 text-[10px] font-medium sm:inline-flex',
                                  meta.cell,
                                  meta.text,
                                )}
                              >
                                <span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} />
                                {meta.label}
                              </span>
                            </button>
                            <button
                              type="button"
                              onClick={() => void handleRemoveGroupMember(group, member)}
                              aria-label={`Remove ${member.name} from ${group.name}`}
                              className="text-muted-foreground hover:text-destructive hover:bg-destructive/10 rounded p-1"
                            >
                              <X size={12} />
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                    {group.members.length === 0 && (
                      <p className="text-muted-foreground text-sm">
                        This roster has no characters left. Delete the group or generate a new cast.
                      </p>
                    )}
                  </article>
                ))}
              </div>
            )
          ) : isLoading ? (
            <div className="flex justify-center py-12">
              <LoadingSpinner size="lg" />
            </div>
          ) : filteredCharacters.length === 0 ? (
            <EmptyState
              icon={<Sparkles />}
              title="No characters found"
              description={
                searchQuery
                  ? 'Try adjusting your search'
                  : 'Create your first character to get started'
              }
              action={{
                label: 'Create Character',
                onClick: handleCreateNew,
              }}
            />
          ) : (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
              {filteredCharacters.map((character: Character) => (
                <div
                  key={character.id}
                  className={cn(
                    'group border-border bg-card relative rounded-2xl border p-5',
                    'hover:border-border cursor-pointer hover:shadow-lg',
                    'transition-all duration-200',
                  )}
                  onClick={() => handleEdit(character)}
                >
                  <div className="mb-4 flex items-start justify-between">
                    <div className="flex items-start gap-3">
                      {character.avatar ? (
                        <img
                          src={character.avatar}
                          alt={character.name}
                          className="h-11 w-11 flex-shrink-0 rounded-xl object-cover shadow-md"
                        />
                      ) : (
                        <div
                          className={cn(
                            'flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl',
                            'from-secondary to-secondary-hover bg-gradient-to-br shadow-md',
                          )}
                        >
                          <Bot size={22} className="text-secondary-foreground" />
                        </div>
                      )}
                      <div className="min-w-0">
                        <h3 className="text-foreground truncate font-semibold">{character.name}</h3>
                        <div className="mt-1 flex flex-wrap items-center gap-1.5">
                          <span
                            className={cn(
                              'text-2xs inline-block rounded-md px-2 py-0.5',
                              'bg-accent text-muted-foreground capitalize',
                            )}
                          >
                            {character.category}
                          </span>
                          <span
                            className={cn(
                              'text-2xs inline-flex items-center gap-1 rounded-md border px-2 py-0.5 font-medium',
                              alignmentMeta(alignmentByCharacter.get(character.id)?.alignment).cell,
                              alignmentMeta(alignmentByCharacter.get(character.id)?.alignment).text,
                            )}
                          >
                            <span
                              className={cn(
                                'h-1.5 w-1.5 rounded-full',
                                alignmentMeta(alignmentByCharacter.get(character.id)?.alignment).dot,
                              )}
                            />
                            {alignmentMeta(alignmentByCharacter.get(character.id)?.alignment).label}
                          </span>
                        </div>
                      </div>
                    </div>

                  </div>

                  <p className="text-muted-foreground mb-4 line-clamp-2 text-sm">
                    {character.description || 'No description'}
                  </p>

                  <div className="mb-4 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setRelationshipCharacter(character.id);
                        setShowRelationships(true);
                      }}
                      className="border-border text-foreground hover:bg-accent inline-flex min-h-10 items-center gap-2 rounded-lg border px-3 py-2 text-xs font-medium"
                    >
                      <Heart size={14} className="text-primary" />
                      Relationships
                    </button>
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setAlignmentCharacter(character.id);
                        setShowAlignment(true);
                      }}
                      className="border-border text-foreground hover:bg-accent inline-flex min-h-10 items-center gap-2 rounded-lg border px-3 py-2 text-xs font-medium"
                    >
                      <Scale size={14} className="text-primary" />
                      Alignment
                    </button>
                  </div>
                  <div className="text-muted-foreground flex flex-wrap items-center justify-between gap-2 text-xs">
                    <span>{character.usageCount || 0} uses</span>
                    <div className="ml-auto flex items-center gap-1">
                      <span className="mr-1 flex items-center gap-1">
                        <Star size={12} className="text-warning fill-warning" />
                        <span>{character.rating?.toFixed(1) || '-'}</span>
                      </span>
                      {/* Touch devices have no hover, so keep the actions visible. */}
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleEdit(character);
                        }}
                        className="hover:bg-accent text-muted-foreground hover:text-foreground rounded-lg p-1.5 transition-colors"
                        title="Edit"
                        aria-label={`Edit ${character.name}`}
                      >
                        <Edit3 size={14} />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleClone(character.id, character.name);
                        }}
                        className="hover:bg-accent text-muted-foreground hover:text-foreground rounded-lg p-1.5 transition-colors"
                        title="Clone"
                        aria-label={`Clone ${character.name}`}
                      >
                        <Copy size={14} />
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleDelete(character.id);
                        }}
                        className="hover:bg-destructive/10 text-muted-foreground hover:text-destructive rounded-lg p-1.5 transition-colors"
                        title="Delete"
                        aria-label={`Delete ${character.name}`}
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Generate Character Modal */}
          {showGenerateModal && (
            <div
              role="dialog"
              aria-modal="true"
              aria-label="Generate with AI"
              className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
            >
              <div className="bg-card border-border mx-4 w-full max-w-md overflow-hidden rounded-2xl border shadow-2xl">
                <div className="border-border flex items-center justify-between border-b p-4">
                  <div className="flex items-center gap-3">
                    <div className="from-secondary to-secondary-hover flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br">
                      <Sparkles size={20} className="text-secondary-foreground" />
                    </div>
                    <h3 className="text-foreground text-lg font-semibold">Generate with AI</h3>
                  </div>
                  <button
                    onClick={closeGenerateModal}
                    className="hover:bg-accent text-muted-foreground hover:text-foreground rounded-lg p-2"
                  >
                    <X size={18} />
                  </button>
                </div>

                <div className="space-y-4 p-4">
                  <p className="text-muted-foreground text-sm">
                    Describe the character you want to create, and AI will generate it for you.
                  </p>

                  <Textarea
                    value={generatePrompt}
                    disabled={isGenerating}
                    aria-label="Character description"
                    onChange={(e) => setGeneratePrompt(e.target.value)}
                    placeholder="e.g., Create a friendly pirate captain who tells sea stories and speaks in nautical terms"
                    rows={4}
                  />

                  <div className="flex justify-end gap-3 pt-2">
                    <button
                      onClick={closeGenerateModal}
                      className={cn(
                        'rounded-xl px-4 py-2.5 text-sm font-medium',
                        'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                        'transition-colors duration-150',
                      )}
                    >
                      Cancel
                    </button>

                    <button
                      onClick={() => void handleGenerate()}
                      disabled={isGenerating || !generatePrompt.trim()}
                      className={cn(
                        'inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium',
                        'from-secondary to-secondary-hover text-secondary-foreground bg-gradient-to-r',
                        'shadow-secondary/25 hover:shadow-secondary/40 shadow-lg',
                        'transition-all duration-200 disabled:opacity-50',
                      )}
                    >
                      {isGenerating ? (
                        <>
                          <Loader2 className="animate-spin" size={16} />
                          Generating...
                        </>
                      ) : (
                        <>
                          <Sparkles size={16} />
                          Generate
                        </>
                      )}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}
          {showRelationships && (
            <CharacterRelationshipsModal
              characterId={relationshipCharacter ?? undefined}
              onClose={() => setShowRelationships(false)}
            />
          )}
          {showAlignment && (
            <CharacterAlignmentModal
              characterId={alignmentCharacter ?? undefined}
              onClose={() => setShowAlignment(false)}
            />
          )}
          {showRosterModal && (
            <CharacterRosterModal
              onClose={() => setShowRosterModal(false)}
              onSaved={() => {
                setSearchQuery('');
                setSelectedCategory('all');
                setView('groups');
                void refetchGroups();
              }}
            />
          )}
        </div>
      </div>
    </div>
  );
};

export default CharactersPage;
