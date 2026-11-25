import { useState } from 'react';
import { Star } from 'lucide-react';
import { cn } from '@/lib/utils';

interface RatingWidgetProps {
  storyId: string;
  currentRating: number;
  totalRatings: number;
  userRating?: number;
  onRate: (storyId: string, rating: number) => void;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const SIZE_MAP = {
  sm: 14,
  md: 18,
  lg: 24,
};

export default function RatingWidget({
  storyId,
  currentRating,
  totalRatings,
  userRating,
  onRate,
  size = 'md',
  className,
}: RatingWidgetProps) {
  const [hoveredStar, setHoveredStar] = useState<number | null>(null);

  const iconSize = SIZE_MAP[size];
  const displayRating = hoveredStar ?? userRating ?? 0;

  const handleClick = (star: number) => {
    onRate(storyId, star);
  };

  return (
    <div className={cn('flex flex-wrap items-center gap-2', className)}>
      {/* Interactive stars */}
      <div className="-mx-1.5 flex items-center sm:mx-0 sm:gap-0.5">
        {[1, 2, 3, 4, 5].map((star) => {
          const isFilled = star <= displayRating;
          const isHovered = hoveredStar !== null && star <= hoveredStar;

          return (
            <button
              key={star}
              type="button"
              onClick={() => handleClick(star)}
              onMouseEnter={() => setHoveredStar(star)}
              onMouseLeave={() => setHoveredStar(null)}
              className={cn(
                'rounded-md p-1.5 transition-colors duration-150 sm:p-0',
                'hover:scale-110 active:scale-95',
                isFilled || isHovered
                  ? 'text-warning'
                  : 'text-muted-foreground/70 hover:text-warning/50'
              )}
              aria-label={`Rate ${star} star${star > 1 ? 's' : ''}`}
            >
              <Star
                size={iconSize}
                className={cn(
                  isFilled || isHovered ? 'fill-current' : ''
                )}
              />
            </button>
          );
        })}
      </div>

      {/* Rating info */}
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">
          {currentRating.toFixed(1)}
        </span>
        <span>({totalRatings} rating{totalRatings !== 1 ? 's' : ''})</span>
      </div>
    </div>
  );
}
