import { useState, useMemo } from 'react';
import { BookOpen, Wand2, Search, Filter } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Card } from '@/components/ui/Card';
import { Select } from '@/components/ui/Select';
import { useStoryTemplates } from '@/hooks/useRoleplay';
import { cn } from '@/lib/utils';
import type { StoryTemplate } from '@/graphql/types';
import { GenreTag } from './GenreTag';

interface StoryTemplateSelectorProps {
  onSelectTemplate: (template: StoryTemplate) => void;
  selectedTemplateId?: string;
  className?: string;
}

const GENRE_OPTIONS = [
  { value: '', label: 'All Genres' },
  { value: 'fantasy', label: 'Fantasy' },
  { value: 'sci-fi', label: 'Sci-Fi' },
  { value: 'horror', label: 'Horror' },
  { value: 'romance', label: 'Romance' },
  { value: 'mystery', label: 'Mystery' },
  { value: 'adventure', label: 'Adventure' },
  { value: 'survival', label: 'Survival' },
  { value: 'slice-of-life', label: 'Slice of Life' },
  { value: 'historical', label: 'Historical' },
  { value: 'any', label: 'Freeform' },
];

export default function StoryTemplateSelector({
  onSelectTemplate,
  selectedTemplateId,
  className,
}: StoryTemplateSelectorProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [genreFilter, setGenreFilter] = useState('');
  const { templates, loading } = useStoryTemplates(genreFilter || undefined);

  const filteredTemplates = useMemo(() => {
    if (!templates) return [];
    const query = searchQuery.toLowerCase().trim();
    if (!query) return templates;
    return templates.filter(
      (t: StoryTemplate) =>
        t.name.toLowerCase().includes(query) ||
        (t.description && t.description.toLowerCase().includes(query)) ||
        t.genre.toLowerCase().includes(query)
    );
  }, [templates, searchQuery]);

  return (
    <div className={cn('space-y-4', className)}>
      {/* Search and Filter Bar */}
      <div className="flex items-center gap-2 sm:gap-3">
        <div className="min-w-0 flex-1">
          <Input
            size="sm"
            placeholder="Search templates..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            leftIcon={<Search size={16} />}
            clearable
            onClear={() => setSearchQuery('')}
          />
        </div>
        <div className="w-36 shrink-0 sm:w-44">
          <Select
            size="sm"
            options={GENRE_OPTIONS}
            value={genreFilter}
            onChange={(e) => setGenreFilter(e.target.value)}
            leftIcon={<Filter size={14} />}
          />
        </div>
      </div>

      {/* Template Grid */}
      {loading ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3].map((i) => (
            <Card key={i} className="animate-pulse p-4">
              <div className="space-y-2">
                <div className="h-4 w-3/4 rounded bg-muted" />
                <div className="h-3 w-1/4 rounded bg-muted" />
                <div className="h-3 w-full rounded bg-muted" />
                <div className="h-3 w-2/3 rounded bg-muted" />
              </div>
            </Card>
          ))}
        </div>
      ) : filteredTemplates.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <div className="bg-primary/10 text-primary mb-4 flex h-10 w-10 items-center justify-center rounded-2xl">
            <BookOpen size={20} aria-hidden="true" />
          </div>
          <p className="text-foreground text-sm font-semibold">No templates found</p>
          <p className="text-muted-foreground mt-1 max-w-sm text-sm">
            {searchQuery
              ? 'Try adjusting your search or filter.'
              : 'No story templates are available yet.'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {filteredTemplates.map((template: StoryTemplate) => {
            const isSelected = selectedTemplateId === template.templateId;

            return (
              <Card
                key={template.templateId}
                hover
                className={cn(
                  'flex flex-col justify-between p-4 transition-all',
                  isSelected && 'ring-2 ring-primary border-primary'
                )}
              >
                <div className="space-y-2">
                  <div className="flex items-start justify-between gap-2">
                    <h4 className="text-sm font-semibold text-foreground leading-tight">
                      {template.name}
                    </h4>
                    {template.isBuiltIn && (
                      <span title="Built-in template"><Wand2 size={14} className="flex-shrink-0 text-primary" /></span>
                    )}
                  </div>

                  <GenreTag genre={template.genre} />

                  {template.description && (
                    <p className="text-xs leading-relaxed text-muted-foreground line-clamp-3">
                      {template.description}
                    </p>
                  )}
                </div>

                <div className="mt-3 pt-3 border-t border-border/50">
                  <Button
                    variant={isSelected ? 'secondary' : 'outline'}
                    size="sm"
                    className="h-10 w-full sm:h-8"
                    onClick={() => onSelectTemplate(template)}
                    leftIcon={<BookOpen size={14} />}
                  >
                    {isSelected ? 'Selected' : 'Use Template'}
                  </Button>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
