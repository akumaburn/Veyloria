import { useState, useEffect, useMemo } from 'react';
import {
  Search,
  Filter,
  BookOpen,
  Download,
  Star,
  User,
  Wand2,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Card } from '@/components/ui/Card';
import { Select } from '@/components/ui/Select';
import { useCommunityStore } from '@/stores/communityStore';
import { cn } from '@/lib/utils';
import type { CommunityTemplate } from '@/graphql/types';
import { GenreTag } from './GenreTag';

interface TemplateBrowserProps {
  onUseTemplate: (templateId: string) => void;
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

export default function TemplateBrowser({
  onUseTemplate,
  className,
}: TemplateBrowserProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [genreFilter, setGenreFilter] = useState('');

  const {
    communityTemplates,
    isLoading,
    templatesHasMore: hasMore,
    fetchCommunityTemplates,
  } = useCommunityStore();

  useEffect(() => {
    void fetchCommunityTemplates(20, 0);
  }, [fetchCommunityTemplates]);

  const handleLoadMore = () => {
    void fetchCommunityTemplates(20, communityTemplates.length);
  };

  const filteredTemplates = useMemo(() => {
    let results = [...communityTemplates];

    // Genre filter
    if (genreFilter) {
      results = results.filter(
        (ct) =>
          ct.template?.genre?.toLowerCase() === genreFilter.toLowerCase()
      );
    }

    // Search filter
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase().trim();
      results = results.filter(
        (ct) =>
          (ct.template?.name && ct.template.name.toLowerCase().includes(query)) ||
          (ct.template?.description &&
            ct.template.description.toLowerCase().includes(query)) ||
          (ct.template?.genre && ct.template.genre.toLowerCase().includes(query))
      );
    }

    return results;
  }, [communityTemplates, genreFilter, searchQuery]);

  return (
    <div className={cn('space-y-4', className)}>
      {/* Search and Filter */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex-1">
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
        <div className="w-full sm:w-44">
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
      {isLoading && communityTemplates.length === 0 ? (
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
              : 'No community templates are available yet.'}
          </p>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {filteredTemplates.map((ct: CommunityTemplate) => {
              const template = ct.template;
              const genre = template?.genre ?? '';

              return (
                <Card
                  key={ct.submissionId}
                  hover
                  className="flex flex-col justify-between p-4 transition-all"
                >
                  <div className="space-y-2">
                    {/* Template name */}
                    <div className="flex items-start justify-between gap-2">
                      <h4 className="text-sm font-semibold leading-tight text-foreground">
                        {template?.name ?? 'Untitled Template'}
                      </h4>
                      <span title="Community template">
                        <Wand2
                          size={14}
                          className="flex-shrink-0 text-primary"
                        />
                      </span>
                    </div>

                    {/* Author */}
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <User size={12} />
                      <span>{ct.userId}</span>
                    </div>

                    {/* Genre badge */}
                    <GenreTag genre={genre} />

                    {/* Description */}
                    {template?.description && (
                      <p className="text-xs leading-relaxed text-muted-foreground line-clamp-3">
                        {template.description}
                      </p>
                    )}

                    {/* Stats */}
                    <div className="flex items-center gap-3 text-xs text-muted-foreground">
                      {ct.rating != null && (
                        <div className="flex items-center gap-1">
                          <Star size={12} className="text-warning fill-warning" />
                          <span className="text-muted-foreground">
                            {ct.rating.toFixed(1)}
                          </span>
                        </div>
                      )}
                      {ct.downloads != null && (
                        <div className="flex items-center gap-1">
                          <Download size={12} />
                          <span>{ct.downloads}</span>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Use Template button */}
                  <div className="mt-3 border-t border-border/50 pt-3">
                    <Button
                      variant="outline"
                      size="sm"
                      className="w-full"
                      onClick={() => onUseTemplate(ct.templateId)}
                      leftIcon={<BookOpen size={14} />}
                    >
                      Use Template
                    </Button>
                  </div>
                </Card>
              );
            })}
          </div>

          {/* Load More */}
          {hasMore && (
            <div className="flex justify-center pt-2">
              <Button
                variant="outline"
                size="sm"
                onClick={handleLoadMore}
                isLoading={isLoading}
                loadingText="Loading..."
              >
                Load More
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
