import { cn } from '@/lib/utils';
import { type CharacterStat } from '@/graphql/types';
import { Tag } from 'lucide-react';

interface StatBarProps {
  stat: CharacterStat;
}

function getBarColor(percentage: number): string {
  if (percentage >= 75) return 'bg-success';
  if (percentage >= 50) return 'bg-warning';
  if (percentage >= 25) return 'bg-primary';
  return 'bg-destructive';
}

function getBarTrackColor(percentage: number): string {
  if (percentage >= 75) return 'bg-success/15';
  if (percentage >= 50) return 'bg-warning/15';
  if (percentage >= 25) return 'bg-primary/15';
  return 'bg-destructive/15';
}

export default function StatBar({ stat }: StatBarProps) {
  const isNumeric = stat.statType !== 'text' && stat.textValue == null;
  const maxValue = stat.statMax ?? 100;
  const percentage = isNumeric
    ? Math.min(Math.max((stat.statValue / maxValue) * 100, 0), 100)
    : 0;

  return (
    <div className="flex flex-col gap-1.5 rounded-lg bg-muted/50 p-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-foreground">{stat.statName}</span>
        <div className="flex items-center gap-2">
          {stat.statCategory && (
            <span className="inline-flex items-center gap-1 rounded-md bg-accent/60 px-2 py-0.5 text-xs text-muted-foreground">
              <Tag size={10} />
              {stat.statCategory}
            </span>
          )}
          {isNumeric && (
            <span className="text-xs font-mono text-muted-foreground">
              {stat.statValue}/{maxValue}
            </span>
          )}
        </div>
      </div>

      {isNumeric ? (
        <div
          className={cn(
            'h-2.5 w-full overflow-hidden rounded-full',
            getBarTrackColor(percentage)
          )}
        >
          <div
            className={cn(
              'h-full rounded-full transition-all duration-500 ease-out',
              getBarColor(percentage)
            )}
            style={{ width: `${percentage}%` }}
          />
        </div>
      ) : (
        <p className="text-sm text-foreground">
          {stat.textValue ?? String(stat.statValue)}
        </p>
      )}
    </div>
  );
}
