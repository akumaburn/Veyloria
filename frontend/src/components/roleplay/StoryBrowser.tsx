import { useState, useEffect, useMemo } from 'react';
import {
  Search,
  Filter,
  SortAsc,
  BookOpen,
  Eye,
  Star,
  User,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Card } from '@/components/ui/Card';
import { Select } from '@/components/ui/Select';
import { useCommunityStore } from '@/stores/communityStore';
import { cn, truncateText, toTimestamp } from '@/lib/utils';
import type { PublishedStory } from '@/graphql/types';
import { GenreTag } from './GenreTag';

interface StoryBrowserProps {
  onSelectStory: (storyId: string) => void;
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
  { value: 'slice-of-life', label: 'Slice of Life' },
  { value: 'historical', label: 'Historical' },
];

const SORT_OPTIONS = [
  { value: 'newest', label: 'Newest' },
  { value: 'popular', label: 'Most Popular' },
  { value: 'highest-rated', label: 'Highest Rated' },
];

const PAGE_SIZE = 20;

export default function StoryBrowser({
  onSelectStory,
  className,
}: StoryBrowserProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [genreFilter, setGenreFilter] = useState('');
  const [sortBy, setSortBy] = useState('newest');
  const [page, setPage] = useState(0);

  const {
    publishedStories,
    isLoading,
    storiesHasMore: hasMore,
    fetchPublishedStories,
  } = useCommunityStore();

  useEffect(() => {
    setPage(0);
    void fetchPublishedStories(genreFilter || undefined, PAGE_SIZE, 0);
  }, [genreFilter, fetchPublishedStories]);

  const handleLoadMore = () => {
    const nextPage = page + 1;
    setPage(nextPage);
    void fetchPublishedStories(genreFilter || undefined, PAGE_SIZE, nextPage * PAGE_SIZE);
  };

  const filteredAndSorted = useMemo(() => {
    let results = [...publishedStories];

    // Client-side search filtering
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase().trim();
      results = results.filter(
        (story) =>
          story.title.toLowerCase().includes(query) ||
          (story.synopsis && story.synopsis.toLowerCase().includes(query))
      );
    }

    // Sort
    switch (sortBy) {
      case 'popular':
        results.sort((a, b) => (b.viewCount ?? 0) - (a.viewCount ?? 0));
        break;
      case 'highest-rated':
        results.sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0));
        break;
      case 'newest':
      default:
        results.sort((a, b) => {
          const dateA = a.publishedAt ? toTimestamp(a.publishedAt) : 0;
          const dateB = b.publishedAt ? toTimestamp(b.publishedAt) : 0;
          return dateB - dateA;
        });
        break;
    }

    return results;
  }, [publishedStories, searchQuery, sortBy]);

  return (
    <div className={cn('space-y-4', className)}>
      {/* Search, Genre Filter, Sort */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex-1">
          <Input
            size="sm"
            placeholder="Search stories..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            leftIcon={<Search size={16} />}
            clearable
            onClear={() => setSearchQuery('')}
          />
        </div>
        <div className="grid grid-cols-2 gap-2 sm:flex sm:items-center">
          <div className="min-w-0 sm:w-40">
            <Select
              size="sm"
              options={GENRE_OPTIONS}
              value={genreFilter}
              onChange={(e) => setGenreFilter(e.target.value)}
              leftIcon={<Filter size={14} />}
            />
          </div>
          <div className="min-w-0 sm:w-40">
            <Select
              size="sm"
              options={SORT_OPTIONS}
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value)}
              leftIcon={<SortAsc size={14} />}
            />
          </div>
        </div>
      </div>

      {/* Story Grid */}
      {isLoading && publishedStories.length === 0 ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Card key={i} className="animate-pulse p-4">
              <div className="space-y-2">
                <div className="h-4 w-3/4 rounded bg-muted" />
                <div className="h-3 w-1/3 rounded bg-muted" />
                <div className="h-3 w-full rounded bg-muted" />
                <div className="h-3 w-2/3 rounded bg-muted" />
                <div className="flex items-center gap-3 pt-2">
                  <div className="h-3 w-16 rounded bg-muted" />
                  <div className="h-3 w-12 rounded bg-muted" />
                </div>
              </div>
            </Card>
          ))}
        </div>
      ) : filteredAndSorted.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <div className="bg-primary/10 text-primary mb-4 flex h-10 w-10 items-center justify-center rounded-2xl">
            <BookOpen size={20} aria-hidden="true" />
          </div>
          <p className="text-foreground text-sm font-semibold">No stories found</p>
          <p className="text-muted-foreground mt-1 max-w-sm text-sm">
            {searchQuery
              ? 'Try adjusting your search or genre filter.'
              : 'No published stories are available yet.'}
          </p>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {filteredAndSorted.map((story: PublishedStory) => {

              return (
                <Card
                  key={story.storyId}
                  hover
                  className="flex cursor-pointer flex-col justify-between p-4 transition-all"
                  onClick={() => onSelectStory(story.storyId)}
                >
                  <div className="space-y-2">
                    {/* Title */}
                    <h4 className="text-sm font-semibold leading-tight break-words text-foreground">
                      {story.title}
                    </h4>

                    {/* Author and genre */}
                    <div className="flex items-center gap-2">
                      <GenreTag genre={story.genre} />
                    </div>

                    {/* Synopsis excerpt */}
                    {story.synopsis && (
                      <p className="text-xs leading-relaxed text-muted-foreground line-clamp-3">
                        {truncateText(story.synopsis, 140)}
                      </p>
                    )}
                  </div>

                  {/* Footer stats */}
                  <div className="mt-3 flex items-center gap-3 border-t border-border/50 pt-3 text-xs text-muted-foreground">
                    {/* Rating */}
                    <div className="flex items-center gap-1">
                      <Star size={12} className="text-warning fill-warning" />
                      <span className="text-muted-foreground">
                        {(story.rating ?? 0).toFixed(1)}
                      </span>
                    </div>

                    {/* View count */}
                    <div className="flex items-center gap-1">
                      <Eye size={12} />
                      <span>{story.viewCount ?? 0}</span>
                    </div>

                    {/* Author */}
                    <div className="ml-auto flex min-w-0 items-center gap-1 text-muted-foreground">
                      <User size={12} className="shrink-0" />
                      <span className="truncate max-w-[80px]">
                        {story.userId}
                      </span>
                    </div>
                  </div>
                </Card>
              );
            })}
          </div>

          {/* Load More / Pagination */}
          {hasMore && (
            <div className="flex justify-center pt-2">
              <Button
                variant="outline"
                size="sm"
                className="h-10 sm:h-8"
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
