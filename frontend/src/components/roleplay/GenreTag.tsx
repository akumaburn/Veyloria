import { cn } from '@/lib/utils';

/** Genre slugs the product ships, with their display labels. */
const GENRE_LABELS: Record<string, string> = {
  fantasy: 'Fantasy',
  'sci-fi': 'Sci-Fi',
  horror: 'Horror',
  romance: 'Romance',
  mystery: 'Mystery',
  adventure: 'Adventure',
  survival: 'Survival',
  'slice-of-life': 'Slice of Life',
  historical: 'Historical',
  mythology: 'Mythology',
  western: 'Western',
  superhero: 'Superhero',
  any: 'Freeform',
};

/**
 * A genre is taxonomy, not identity or status, so every genre takes the same
 * quiet butter label. The meaning lives in the word; color stays on the warm
 * axis instead of spreading a rainbow across the story and template grids.
 */
export function GenreTag({ genre, className }: { genre?: string | null; className?: string }) {
  if (!genre) return null;
  const key = genre.toLowerCase();
  const label = GENRE_LABELS[key] ?? genre;

  return (
    <span
      className={cn(
        'bg-accent text-muted-foreground inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-[0.08em] uppercase',
        className,
      )}
    >
      {label}
    </span>
  );
}
