import { useState, useMemo } from 'react';
import { cn } from '@/lib/utils';
import { type KnowledgeEntry } from '@/graphql/types';
import { useKnowledgeEntries } from '@/hooks/useRoleplay';
import {
  Tag,
  Calendar,
  Filter,
  Loader2,
  AlertTriangle,
  Info,
  AlertCircle,
  Star,
} from 'lucide-react';

interface KnowledgeViewProps {
  conversationId: string;
  characterId?: string;
}

type ImportanceFilter = 'all' | 'critical' | 'high' | 'medium' | 'low';

function getImportanceConfig(importance: number): {
  label: string;
  color: string;
  bgColor: string;
  icon: React.ReactNode;
} {
  if (importance >= 9) {
    return {
      label: 'Critical',
      color: 'text-destructive',
      bgColor: 'bg-destructive/15',
      icon: <AlertCircle size={12} />,
    };
  }
  if (importance >= 7) {
    return {
      label: 'High',
      color: 'text-primary',
      bgColor: 'bg-primary/15',
      icon: <AlertTriangle size={12} />,
    };
  }
  if (importance >= 4) {
    return {
      label: 'Medium',
      color: 'text-warning',
      bgColor: 'bg-warning/15',
      icon: <Star size={12} />,
    };
  }
  return {
    label: 'Low',
    color: 'text-muted-foreground',
    bgColor: 'bg-muted',
    icon: <Info size={12} />,
  };
}

function matchesFilter(importance: number, filter: ImportanceFilter): boolean {
  if (filter === 'all') return true;
  if (filter === 'critical') return importance >= 9;
  if (filter === 'high') return importance >= 7 && importance < 9;
  if (filter === 'medium') return importance >= 4 && importance < 7;
  return importance < 4;
}

const FILTER_OPTIONS: { value: ImportanceFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'critical', label: 'Critical' },
  { value: 'high', label: 'High' },
  { value: 'medium', label: 'Medium' },
  { value: 'low', label: 'Low' },
];

export default function KnowledgeView({ conversationId, characterId }: KnowledgeViewProps) {
  const { entries, loading } = useKnowledgeEntries(conversationId, characterId);
  const [filter, setFilter] = useState<ImportanceFilter>('all');

  const filteredEntries = useMemo(() => {
    return (entries as KnowledgeEntry[]).filter((entry) => {
      if (!entry.isActive) return false;
      return matchesFilter(entry.importance ?? 5, filter);
    });
  }, [entries, filter]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Filter bar */}
      <div className="flex items-start gap-2">
        <Filter size={14} className="mt-1 flex-shrink-0 text-muted-foreground" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap gap-1">
            {FILTER_OPTIONS.map((option) => (
              <button
                key={option.value}
                onClick={() => setFilter(option.value)}
                className={cn(
                  'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
                  filter === option.value
                    ? 'bg-primary/15 text-primary'
                    : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground'
                )}
              >
                {option.label}
              </button>
            ))}
          </div>
          {filteredEntries.length > 0 && (
            <p className="mt-1 text-[10px] text-muted-foreground">
              {filteredEntries.length} {filteredEntries.length === 1 ? 'entry' : 'entries'}
            </p>
          )}
        </div>
      </div>

      {/* Empty state */}
      {filteredEntries.length === 0 && (
        <p className="py-1 text-center text-sm text-muted-foreground">
          {filter === 'all' ? 'No knowledge entries yet' : `No ${filter} importance entries`}
        </p>
      )}

      {/* Knowledge entries */}
      {filteredEntries.map((entry) => {
        const importance = entry.importance ?? 5;
        const config = getImportanceConfig(importance);

        return (
          <div
            key={entry.entryId}
            className="rounded-xl border border-border bg-card p-4 transition-colors hover:border-border-hover"
          >
            {/* Header row */}
            <div className="mb-2 flex items-start justify-between">
              <span
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium',
                  config.bgColor,
                  config.color
                )}
              >
                {config.icon}
                {config.label} ({importance})
              </span>

              {entry.dayNumber != null && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Calendar size={11} />
                  Day {entry.dayNumber}
                </span>
              )}
            </div>

            {/* Summary */}
            <p className="text-sm leading-relaxed text-foreground">{entry.summary}</p>

            {/* Tags */}
            {entry.tags && entry.tags.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-1">
                {entry.tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center gap-1 rounded-md bg-accent/50 px-1.5 py-0.5 text-xs text-muted-foreground"
                  >
                    <Tag size={10} />
                    {tag}
                  </span>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
