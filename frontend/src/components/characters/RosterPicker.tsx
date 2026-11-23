import { Check, Users } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { cn } from '@/lib/utils';
import type { CharacterGroup } from '@/graphql/types';

interface RosterPickerProps {
  rosters: CharacterGroup[];
  selectedCharacterIds: string[];
  /** Adds members that are not already selected, in roster order. */
  onAdd: (characterIds: string[]) => void;
  /** Removes the given members from the current selection. */
  onRemove: (characterIds: string[]) => void;
  /** Resolved names for the roster members, when available. */
  characterNames?: Record<string, string>;
  loading?: boolean;
  /** Upper bound on the total selection. Defaults to no limit. */
  maxSelectable?: number;
  className?: string;
}

/**
 * Adds every member of a saved roster in one action.
 *
 * The chip reports three states. Not selected means no member is chosen.
 * Partly selected means the player removed or added individual members after
 * using the roster. Fully selected means the whole roster is present. Clicking
 * a full chip removes its members; clicking any other chip completes it.
 */
export function RosterPicker({
  rosters,
  selectedCharacterIds,
  onAdd,
  onRemove,
  characterNames,
  loading = false,
  maxSelectable,
  className,
}: RosterPickerProps) {
  if (loading) {
    return (
      <div className={cn('space-y-2', className)} aria-hidden="true">
        <div className="h-4 w-40 animate-pulse rounded bg-muted" />
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {[0, 1].map((index) => (
            <div key={index} className="h-14 animate-pulse rounded-lg bg-muted/60" />
          ))}
        </div>
      </div>
    );
  }

  if (rosters.length === 0) return null;

  const selected = new Set(selectedCharacterIds);
  const room = maxSelectable == null
    ? Number.POSITIVE_INFINITY
    : Math.max(0, maxSelectable - selectedCharacterIds.length);

  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex items-baseline justify-between gap-2">
        <p className="text-sm font-medium text-foreground">Start from a saved roster</p>
        <span className="text-xs text-muted-foreground">optional</span>
      </div>
      <div role="group" aria-label="Saved rosters" className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {rosters.map((roster) => {
          const members = roster.characterIds ?? [];
          const chosen = members.filter((id) => selected.has(id));
          const full = members.length > 0 && chosen.length === members.length;
          const partial = chosen.length > 0 && !full;
          const empty = members.length === 0;
          const missing = members.filter((id) => !selected.has(id));

          const memberNames = members
            .map((id) => characterNames?.[id])
            .filter((name): name is string => Boolean(name));
          const subtitle = empty
            ? 'No characters left'
            : memberNames.length > 0
              ? `${memberNames.slice(0, 3).join(', ')}${memberNames.length > 3 ? ` +${memberNames.length - 3}` : ''}`
              : roster.description || roster.setting || 'Saved cast';

          const handleClick = () => {
            if (empty) return;
            if (full) {
              onRemove(members);
              return;
            }
            const accepted = missing.slice(0, room);
            if (accepted.length > 0) onAdd(accepted);
            if (accepted.length < missing.length) {
              const limit = maxSelectable ?? 0;
              toast.error(
                accepted.length === 0
                  ? `A group holds up to ${limit} characters.`
                  : `A group holds up to ${limit} characters. Added ${accepted.length} of ${missing.length}.`
              );
            }
          };

          const memberWord = (count: number) => `${count} member${count === 1 ? '' : 's'}`;

          return (
            <button
              key={roster.id}
              type="button"
              onClick={handleClick}
              disabled={empty}
              aria-pressed={full}
              title={roster.name}
              aria-label={
                empty
                  ? `${roster.name} has no characters`
                  : full
                    ? members.length === 1
                      ? `Remove 1 member of ${roster.name}`
                      : `Remove all ${members.length} members of ${roster.name}`
                    : `Add ${memberWord(missing.length)} of ${roster.name}`
              }
              className={cn(
                'flex items-center gap-2.5 rounded-lg border px-3 py-2 text-left transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                full
                  ? 'border-primary bg-primary/10'
                  : partial
                    ? 'border-primary/50 border-dashed bg-primary/5'
                    : 'border-border bg-muted/40 hover:border-border-hover hover:bg-accent/50',
                empty && 'cursor-not-allowed opacity-50'
              )}
            >
              <span
                className={cn(
                  'flex h-7 w-7 shrink-0 items-center justify-center rounded-md',
                  full || partial
                    ? 'bg-primary/15 text-primary'
                    : 'bg-muted text-muted-foreground'
                )}
              >
                {full ? <Check size={14} /> : <Users size={14} />}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-foreground">
                  {roster.name}
                </span>
                <span className="block truncate text-xs text-muted-foreground">{subtitle}</span>
              </span>
              <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                {chosen.length > 0 ? `${chosen.length}/${members.length}` : members.length}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
