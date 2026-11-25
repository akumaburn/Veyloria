import { useEffect, useMemo } from 'react';
import {
  ArrowLeft,
  Calendar,
  Eye,
  Star,
  User,
  BookOpen,
  Tag,
  Loader2,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { useCommunityStore } from '@/stores/communityStore';
import { cn, formatDate } from '@/lib/utils';
import type { PublishedStory } from '@/graphql/types';
import RatingWidget from '@/components/roleplay/RatingWidget';
import { GenreTag } from './GenreTag';

interface StoryReaderProps {
  storyId: string;
  onBack: () => void;
  className?: string;
}

export default function StoryReader({
  storyId,
  onBack,
  className,
}: StoryReaderProps) {
  const {
    publishedStories,
    isLoading,
    rateStory,
    fetchPublishedStories,
  } = useCommunityStore();

  // Find the story from the store
  const story: PublishedStory | undefined = useMemo(
    () => publishedStories.find((s) => s.storyId === storyId),
    [publishedStories, storyId]
  );

  // Fetch stories if we don't have this one loaded
  useEffect(() => {
    if (!story) {
      void fetchPublishedStories(undefined, 50, 0);
    }
  }, [story, fetchPublishedStories]);

  const handleRate = (id: string, rating: number) => {
    void rateStory({ storyId: id, rating });
  };

  if (isLoading && !story) {
    return (
      <div className={cn('flex items-center justify-center py-16', className)}>
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!story) {
    return (
      <div className={cn('space-y-4', className)}>
        <Button
          variant="ghost"
          size="sm"
          leftIcon={<ArrowLeft size={16} />}
          onClick={onBack}
        >
          Back to Stories
        </Button>
        <div className="flex flex-col items-center justify-center py-12 text-center">
          <BookOpen size={40} className="mb-3 text-muted-foreground/50" />
          <p className="text-sm font-medium text-muted-foreground">Story not found</p>
          <p className="mt-1 text-xs text-muted-foreground">
            This story may have been removed or is no longer available.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className={cn('space-y-4 sm:space-y-6', className)}>
      {/* Story Header */}
      <Card className="p-4 sm:p-6">
        <div className="space-y-4">
          {/* Title */}
          <h2 className="text-xl font-bold break-words text-foreground">{story.title}</h2>

          {/* Meta row */}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-muted-foreground">
            {/* Author */}
            <div className="flex min-w-0 max-w-full items-center gap-1.5">
              <User size={14} className="shrink-0" />
              <span className="truncate">{story.userId}</span>
            </div>

            {/* Genre badge */}
            <GenreTag genre={story.genre} />

            {/* Published date */}
            {story.publishedAt && (
              <div className="flex items-center gap-1.5">
                <Calendar size={14} />
                <span>{formatDate(story.publishedAt)}</span>
              </div>
            )}

            {/* View count */}
            <div className="flex items-center gap-1.5">
              <Eye size={14} />
              <span>{story.viewCount ?? 0} views</span>
            </div>

            {/* Rating */}
            <div className="flex items-center gap-1.5">
              <Star size={14} className="text-warning fill-warning" />
              <span>
                {(story.rating ?? 0).toFixed(1)} ({story.ratingCount ?? 0})
              </span>
            </div>
          </div>

          {/* Tags */}
          {story.tags && story.tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {story.tags.map((tag) => (
                <span
                  key={tag}
                  className="inline-flex items-center gap-1 rounded-md bg-accent/50 px-2 py-0.5 text-xs text-muted-foreground"
                >
                  <Tag size={10} />
                  {tag}
                </span>
              ))}
            </div>
          )}
        </div>
      </Card>

      {/* Story Content / Description */}
      <Card className="p-4 sm:p-6">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
          Synopsis
        </h3>
        {story.synopsis ? (
          <div className="max-w-none">
            <p className="whitespace-pre-wrap break-words leading-relaxed text-foreground">
              {story.synopsis}
            </p>
          </div>
        ) : (
          <p className="text-sm italic text-muted-foreground">No synopsis provided.</p>
        )}

        {/* Word count */}
        {story.wordCount != null && story.wordCount > 0 && (
          <div className="mt-4 border-t border-border/50 pt-3">
            <span className="text-xs text-muted-foreground">
              {story.wordCount.toLocaleString()} words
            </span>
          </div>
        )}
      </Card>

      {/* Rating Section */}
      <Card className="p-4 sm:p-6">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
          Rate this Story
        </h3>
        <RatingWidget
          storyId={story.storyId}
          currentRating={story.rating ?? 0}
          totalRatings={story.ratingCount ?? 0}
          onRate={handleRate}
          size="lg"
        />
      </Card>
    </div>
  );
}
