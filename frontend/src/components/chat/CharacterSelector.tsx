import { useState, useMemo } from 'react';
import { Bot, Search, X, Check, Info, Star, Sparkles, Users } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useCharacters } from '@/hooks/useGraphQL';
import type { Character } from '@/graphql/types';

// Character data passed when selected
export interface SelectedCharacter {
  id: string;
  name: string;
  greeting?: string;
  personality?: string;
  gender?: string;
  pronouns?: string;
  ageYears?: number;
  avatar?: string;
}

interface CharacterSelectorProps {
  selectedCharacterId: string | null;
  onSelect: (character: SelectedCharacter | null) => void;
  onClose: () => void;
}

// UI-specific character display data
interface CharacterDisplay {
  id: string;
  name: string;
  description: string;
  personality: string;
  gender?: string; pronouns?: string; ageYears?: number;
  greeting?: string;
  avatar?: string;
  tags: string[];
  isDefault?: boolean;
  popularity: number;
  modelPreferences?: {
    temperature?: number;
    maxTokens?: number;
    topP?: number;
    topK?: number;
  };
}

export const CharacterSelector = ({
  selectedCharacterId,
  onSelect,
  onClose
}: CharacterSelectorProps) => {
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('all');

  // Use GraphQL query
  const { characters: charactersData, loading: isLoading } = useCharacters(
    { isPublic: true },
    50,
    0
  );

  // Transform GraphQL data to component format
  const characters: CharacterDisplay[] = useMemo(() => {
    return charactersData?.map((char: Character) => ({
      id: char.id,
      name: char.name,
      description: char.description || '',
      personality: char.personality || '',
      gender: char.gender, pronouns: char.pronouns, ageYears: char.ageYears,
      greeting: char.greeting,
      avatar: char.avatar,
      tags: char.tags || [],
      isDefault: char.isDefault,
      popularity: char.usageCount || 0,
      modelPreferences: {
        temperature: char.temperature,
        maxTokens: char.maxTokens,
        topP: char.topP,
        topK: char.topK,
      },
    })) || [];
  }, [charactersData]);

  // Handle character selection - pass full character data
  const handleSelect = (character: CharacterDisplay) => {
    onSelect({
      id: character.id,
      name: character.name,
      personality: character.personality,
      gender: character.gender, pronouns: character.pronouns, ageYears: character.ageYears,
      greeting: character.greeting,
      avatar: character.avatar,
    });
  };

  const categories = [
    { id: 'all', name: 'All', icon: Users },
    { id: 'general', name: 'General', icon: Bot },
    { id: 'technical', name: 'Technical', icon: Sparkles },
    { id: 'creative', name: 'Creative', icon: Star },
  ];

  const filteredCharacters = characters
    .filter((char) => {
      const matchesSearch =
        char.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
        char.description.toLowerCase().includes(searchTerm.toLowerCase());

      const matchesCategory =
        selectedCategory === 'all' ||
        char.tags.includes(selectedCategory);

      return matchesSearch && matchesCategory;
    })
    // Sort: default characters first, then by popularity
    .sort((a, b) => {
      if (a.isDefault && !b.isDefault) return -1;
      if (!a.isDefault && b.isDefault) return 1;
      return b.popularity - a.popularity;
    });

  return (
    <div className={cn(
      'absolute left-0 right-0 top-full z-50',
      'border-b border-border/50 bg-background-secondary/95 backdrop-blur-md',
      'shadow-2xl animate-fade-down'
    )}>
      <div className="mx-auto max-w-6xl p-4">
        {/* Header */}
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={cn(
              'flex h-10 w-10 items-center justify-center rounded-xl',
              'bg-gradient-to-br from-secondary to-secondary-hover shadow-lg'
            )}>
              <Bot size={20} className="text-secondary-foreground" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-foreground">Select Character</h3>
              <p className="text-xs text-muted-foreground">Choose an AI personality for your conversation</p>
            </div>
          </div>
          <button
            aria-label="Close character selector"
            onClick={onClose}
            className={cn(
              'rounded-lg p-2 text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
          >
            <X size={20} />
          </button>
        </div>

        {/* Search and filters */}
        <div className="mb-4 flex flex-col gap-3 sm:flex-row">
          <div className="relative flex-1">
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

          <div className="flex flex-wrap gap-2">
            {categories.map((category) => {
              const Icon = category.icon;
              return (
                <button
                  key={category.id}
                  onClick={() => setSelectedCategory(category.id)}
                  className={cn(
                    'flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium',
                    'transition-all duration-150 whitespace-nowrap',
                    selectedCategory === category.id
                      ? 'bg-primary text-primary-foreground shadow-lg shadow-primary/25'
                      : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground'
                  )}
                >
                  <Icon size={14} />
                  {category.name}
                </button>
              );
            })}
          </div>
        </div>

        {/* Characters grid */}
        {isLoading ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {[...Array(6)].map((_, i) => (
              <div key={i} className="animate-pulse rounded-xl bg-muted/30 h-32" />
            ))}
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 max-h-[400px] overflow-y-auto custom-scrollbar pr-2">
            {/* Character cards from backend */}
            {filteredCharacters.map((character) => (
              <button
                key={character.id}
                onClick={() => handleSelect(character)}
                className={cn(
                  'group relative rounded-xl border p-4 text-left',
                  'transition-all duration-200',
                  selectedCharacterId === character.id
                    ? 'border-secondary bg-secondary/10 shadow-lg shadow-secondary/10'
                    : 'border-border/50 bg-card hover:border-border hover:bg-accent/50'
                )}
              >
                <div className="flex items-start gap-3">
                  {character.avatar ? (
                    <img
                      src={character.avatar}
                      alt={character.name}
                      className={cn(
                        'h-12 w-12 rounded-xl object-cover shadow-md',
                        'transition-transform duration-200 group-hover:scale-105'
                      )}
                    />
                  ) : (
                    <div className={cn(
                      'flex h-12 w-12 items-center justify-center rounded-xl',
                      'bg-gradient-to-br from-secondary to-secondary-hover shadow-md',
                      'transition-transform duration-200 group-hover:scale-105'
                    )}>
                      <Bot size={24} className="text-secondary-foreground" />
                    </div>
                  )}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <h4 className="font-semibold text-foreground truncate">{character.name}</h4>
                        {character.isDefault && (
                          <Star size={12} className="text-warning fill-warning" />
                        )}
                      </div>
                      {selectedCharacterId === character.id && (
                        <div className="flex h-5 w-5 items-center justify-center rounded-full bg-secondary">
                          <Check size={12} className="text-secondary-foreground" />
                        </div>
                      )}
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground line-clamp-2">
                      {character.description}
                    </p>
                    {character.tags.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1">
                        {character.tags.slice(0, 3).map((tag) => (
                          <span
                            key={tag}
                            className={cn(
                              'rounded-md px-1.5 py-0.5 text-2xs',
                              'bg-accent text-muted-foreground'
                            )}
                          >
                            {tag}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </div>

                {/* Hover tooltip with more info */}
                <div className={cn(
                  'absolute left-0 top-full z-20 mt-2 w-full',
                  'rounded-xl border border-border bg-card p-3 shadow-xl',
                  'opacity-0 invisible translate-y-1',
                  'group-hover:opacity-100 group-hover:visible group-hover:translate-y-0',
                  'transition-all duration-200'
                )}>
                  <div className="space-y-2 text-xs">
                    {character.personality && (
                      <div>
                        <span className="font-medium text-muted-foreground">Personality</span>
                        <p className="text-foreground mt-0.5 line-clamp-2">{character.personality}</p>
                      </div>
                    )}
                    {character.greeting && (
                      <div>
                        <span className="font-medium text-muted-foreground">Greeting</span>
                        <p className="text-foreground mt-0.5 italic">"{character.greeting}"</p>
                      </div>
                    )}
                    {character.popularity > 0 && (
                      <div className="flex items-center gap-1.5 pt-1 border-t border-border/50">
                        <Star size={10} className="text-warning fill-warning" />
                        <span className="text-muted-foreground">
                          {character.popularity.toLocaleString()} conversations
                        </span>
                      </div>
                    )}
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}

        {filteredCharacters.length === 0 && !isLoading && (
          <div className="py-12 text-center">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
              <Search size={24} className="text-muted-foreground" />
            </div>
            <p className="text-sm font-medium text-foreground">No characters found</p>
            <p className="text-xs text-muted-foreground mt-1">Try adjusting your search or filters</p>
          </div>
        )}

        {/* Footer info */}
        <div className={cn(
          'mt-4 flex items-center gap-2 pt-3 border-t border-border/50',
          'text-xs text-muted-foreground'
        )}>
          <Info size={14} />
          <span>Characters define the AI's personality, response style, and capabilities</span>
        </div>
      </div>
    </div>
  );
};
