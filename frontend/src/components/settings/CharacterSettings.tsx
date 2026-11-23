import { useCharacterGeneration } from '@/hooks/useCharacterGeneration';
/**
 * Character Settings Component
 *
 * Manages AI character creation, editing, and deletion within the Settings panel.
 * Uses shared CharacterForm component for the editor view.
 */

import { useState, useEffect } from 'react';
import { useQuery } from '@apollo/client';
import {
  Bot,
  Plus,
  Sparkles,
  Search,
  Edit3,
  Trash2,
  Copy,
  Star,
  Users,
  ChevronRight,
  X,
  Loader2,
  AlertCircle,
  Wand2,
} from 'lucide-react';
import { useCharacterStore } from '@/stores/characterStore';
import { cn } from '@/lib/utils';
import type { Character } from '@/graphql/types';
import CharacterForm, {
  type CharacterFormData,
  defaultFormData,
  formDataFromCharacter,
  categories,
} from '@/components/shared/CharacterForm';
import { actorAlignmentKey, type AlignmentSummaryData } from '@/components/characters/alignment';
import { CHARACTER_ALIGNMENT_SUMMARY_QUERY } from '@/graphql/operations';

export const CharacterSettings = () => {
  const {
    characters,
    isLoading,
    error,
    fetchCharacters,
    createCharacter,
    updateCharacter,
    deleteCharacter,
    cloneCharacter,
    clearError,
  } = useCharacterStore();

  const { generateCharacter, cancelGeneration, isGenerating } = useCharacterGeneration();

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFilter, setSelectedFilter] = useState<'all' | 'mine' | 'public'>('all');
  const [showEditor, setShowEditor] = useState(false);
  const [editingCharacter, setEditingCharacter] = useState<Character | null>(null);
  const [formData, setFormData] = useState<CharacterFormData>(defaultFormData);
  const [showGenerateModal, setShowGenerateModal] = useState(false);
  const [generatePrompt, setGeneratePrompt] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);

  useEffect(() => {
    fetchCharacters();
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

  useEffect(() => {
    if (error) {
      const timer = setTimeout(clearError, 5000);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [error, clearError]);

  const filteredCharacters = characters.filter((char) => {
    const matchesSearch =
      char.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      char.description?.toLowerCase().includes(searchQuery.toLowerCase());

    if (selectedFilter === 'mine') return matchesSearch && !char.isPublic;
    if (selectedFilter === 'public') return matchesSearch && char.isPublic;
    return matchesSearch;
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
    } catch (err) {
      console.error('Failed to save character:', err);
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (confirm('Are you sure you want to delete this character?')) {
      try {
        await deleteCharacter(id);
      } catch (err) {
        console.error('Failed to delete character:', err);
      }
    }
  };

  const handleClone = async (id: string, name: string) => {
    const newName = prompt('Enter name for the cloned character:', `${name} (Copy)`);
    if (newName) {
      try {
        await cloneCharacter(id, newName);
      } catch (err) {
        console.error('Failed to clone character:', err);
      }
    }
  };

  const closeGenerateModal = () => {
    cancelGeneration();
    setShowGenerateModal(false);
  };

  const handleGenerate = async () => {
    if (!generatePrompt.trim()) return;

    try {
      const draft = await generateCharacter(generatePrompt);
      const generated = draft?.character;
      if (generated && draft) {
        setEditingCharacter(null);
        setSaveSuccess(false);
        setFormData(formDataFromCharacter(generated, draft));
        setShowGenerateModal(false);
        setShowEditor(true);
      }
    } catch (err) {
      console.error('Failed to generate character:', err);
    }
  };

  const getCategoryColor = (category: string) => {
    return categories.find((c) => c.value === category)?.color || 'bg-muted';
  };

  // Character List View
  if (!showEditor) {
    return (
      <div className="space-y-6">
        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-44 flex-1">
            <h2 className="text-foreground text-xl font-semibold">Characters</h2>
            <p className="text-muted-foreground mt-1 text-sm">
              Create and manage AI personalities for your conversations
            </p>
          </div>
          <div className="flex shrink-0 gap-2">
            <button
              onClick={() => setShowGenerateModal(true)}
              className={cn(
                'inline-flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-medium',
                'border-border bg-card text-foreground border',
                'hover:bg-accent transition-colors duration-150',
              )}
            >
              <Wand2 size={16} className="text-secondary" />
              Generate
            </button>
            <button
              onClick={handleCreateNew}
              className={cn(
                'inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium',
                'bg-primary text-primary-foreground',
                'shadow-primary/25 hover:shadow-primary/40 hover:bg-primary-hover shadow-lg',
                'transition-all duration-200',
              )}
            >
              <Plus size={16} />
              Create
            </button>
          </div>
        </div>

        {/* Error Message */}
        {error && (
          <div className="bg-destructive/10 border-destructive/30 flex items-start gap-3 rounded-xl border p-4">
            <AlertCircle size={18} className="text-destructive mt-0.5 shrink-0" />
            <div className="text-destructive flex-1 text-sm">{error}</div>
            <button onClick={clearError} className="text-destructive hover:bg-destructive/10">
              <X size={16} />
            </button>
          </div>
        )}

        {/* Search and Filter */}
        <div className="flex flex-col gap-3 sm:flex-row">
          <div className="relative flex-1">
            <Search
              className="text-muted-foreground absolute top-1/2 left-3 -translate-y-1/2"
              size={18}
            />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search characters..."
              className={cn(
                'border-border bg-muted/50 w-full rounded-xl border',
                'text-foreground placeholder-muted-foreground py-2.5 pr-3 pl-10 text-sm',
                'focus:border-primary focus:ring-primary/20 focus:ring-2 focus:outline-none',
                'transition-all duration-150',
              )}
            />
          </div>
          <div className="flex flex-wrap gap-2">
            {(['all', 'mine', 'public'] as const).map((filter) => (
              <button
                key={filter}
                onClick={() => setSelectedFilter(filter)}
                className={cn(
                  'rounded-xl px-3 py-1.5 text-xs font-medium capitalize whitespace-nowrap sm:px-4 sm:py-2 sm:text-sm',
                  'transition-all duration-150',
                  selectedFilter === filter
                    ? 'bg-primary text-primary-foreground shadow-md'
                    : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                )}
              >
                {filter === 'mine' ? 'My Characters' : filter}
              </button>
            ))}
          </div>
        </div>

        {/* Characters Grid */}
        {isLoading ? (
          <div className="flex justify-center py-12">
            <Loader2 className="text-muted-foreground animate-spin" size={32} />
          </div>
        ) : filteredCharacters.length === 0 ? (
          <div className="border-border rounded-xl border border-dashed py-12 text-center">
            <Bot size={48} className="text-muted-foreground/50 mx-auto mb-4" />
            <h3 className="text-foreground mb-2 text-lg font-medium">No characters found</h3>
            <p className="text-muted-foreground mb-4 text-sm">
              {searchQuery
                ? 'Try adjusting your search'
                : 'Create your first character to get started'}
            </p>
            <button
              onClick={handleCreateNew}
              className={cn(
                'inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium',
                'bg-primary text-primary-foreground hover:bg-primary-hover',
                'transition-colors duration-150',
              )}
            >
              <Plus size={16} />
              Create Character
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {filteredCharacters.map((character) => (
              <div
                key={character.id}
                className={cn(
                  'group border-border/50 bg-card relative rounded-xl border p-4',
                  'hover:border-border hover:shadow-lg',
                  'cursor-pointer transition-all duration-200',
                )}
                onClick={() => handleEdit(character)}
              >
                {/* Category indicator */}
                <div
                  className={cn(
                    'absolute top-0 left-6 h-1 w-12 rounded-b-full',
                    getCategoryColor(character.category || 'general'),
                  )}
                />

                <div className="flex items-start gap-3">
                  {character.avatar ? (
                    <img
                      src={character.avatar}
                      alt={character.name}
                      className="h-12 w-12 shrink-0 rounded-xl object-cover shadow-lg"
                    />
                  ) : (
                    <div
                      className={cn(
                        'flex h-12 w-12 shrink-0 items-center justify-center rounded-xl',
                        'from-secondary/80 to-secondary bg-gradient-to-br shadow-lg',
                      )}
                    >
                      <Bot size={24} className="text-secondary-foreground" />
                    </div>
                  )}

                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <h3 className="text-foreground truncate font-semibold">{character.name}</h3>
                      {character.isPublic && (
                        <span className="text-2xs bg-accent text-muted-foreground rounded px-1.5 py-0.5">
                          Public
                        </span>
                      )}
                    </div>
                    <p className="text-muted-foreground mt-1 line-clamp-2 text-sm">
                      {character.description || 'No description'}
                    </p>
                  </div>
                </div>

                {/* Footer stats */}
                <div className="border-border/50 text-muted-foreground mt-4 flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-xs">
                  <div className="flex items-center gap-3">
                    <span className={cn('rounded-md px-2 py-0.5 capitalize', 'bg-accent')}>
                      {character.category || 'general'}
                    </span>
                    <span className="flex items-center gap-1">
                      <Users size={12} />
                      {character.usageCount || 0} uses
                    </span>
                  </div>
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
                      <Edit3 size={16} />
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
                      <Copy size={16} />
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
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Generate Modal */}
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
                <textarea
                  value={generatePrompt}
                  disabled={isGenerating}
                  aria-label="Character description"
                  onChange={(e) => setGeneratePrompt(e.target.value)}
                  placeholder="e.g., Create a friendly pirate captain who tells sea stories and speaks in nautical terms"
                  className={cn(
                    'border-border bg-muted/50 w-full rounded-xl border p-3',
                    'text-foreground placeholder-muted-foreground text-sm',
                    'focus:border-primary focus:ring-primary/20 focus:ring-2 focus:outline-none',
                    'resize-none',
                  )}
                  rows={4}
                />
                <div className="flex justify-end gap-3">
                  <button
                    onClick={closeGenerateModal}
                    className={cn(
                      'rounded-xl px-4 py-2 text-sm font-medium',
                      'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                      'transition-colors',
                    )}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={() => void handleGenerate()}
                    disabled={isGenerating || !generatePrompt.trim()}
                    className={cn(
                      'inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium',
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
      </div>
    );
  }

  // Character Editor View — uses shared CharacterForm
  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <button
          onClick={() => setShowEditor(false)}
          className={cn(
            'inline-flex items-center gap-2 text-sm font-medium',
            'text-muted-foreground hover:text-foreground',
            'transition-colors',
          )}
        >
          <ChevronRight size={16} className="rotate-180" />
          Back to Characters
        </button>
      </div>

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
  );
};
