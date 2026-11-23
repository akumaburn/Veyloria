import { cn, formatDate, toTimestamp } from '@/lib/utils';
import { useStoryBranches } from '@/hooks/useRoleplay';
import { type StoryBranch } from '@/graphql/types';
import { GitBranch, GitFork, Circle, Loader2 } from 'lucide-react';

interface StoryTimelineProps {
  conversationId: string;
}

export default function StoryTimeline({ conversationId }: StoryTimelineProps) {
  const { branches, loading } = useStoryBranches(conversationId);

  if (loading) {
    return (
      <div className="flex h-32 items-center justify-center">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (branches.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-border py-8">
        <GitBranch className="mb-2 h-6 w-6 text-muted-foreground/70" />
        <p className="text-sm text-muted-foreground">No branches yet</p>
        <p className="text-xs text-muted-foreground">Create a branch to explore alternate storylines</p>
      </div>
    );
  }

  // Sort branches by creation date
  const sorted = [...(branches as StoryBranch[])].sort(
    (a: StoryBranch, b: StoryBranch) => toTimestamp(a.createdAt) - toTimestamp(b.createdAt)
  );

  return (
    <div className="space-y-1">
      <h3 className="mb-3 text-sm font-semibold text-foreground">Story Timeline</h3>

      {/* Main branch root */}
      <div className="relative pl-8">
        <div className="absolute left-3 top-0 bottom-0 w-px bg-border" />

        {/* Root node */}
        <div className="relative mb-4 flex items-start gap-3">
          <div className="absolute -left-5 top-1 flex h-5 w-5 items-center justify-center rounded-full bg-primary">
            <Circle size={8} className="fill-current text-primary-foreground" />
          </div>
          <div>
            <p className="text-sm font-medium text-foreground">Main Story</p>
            <p className="text-xs text-muted-foreground">Origin</p>
          </div>
        </div>

        {/* Branch nodes */}
        {sorted.map((branch, index) => {
          const isLast = index === sorted.length - 1;
          return (
            <div key={branch.branchId} className="relative mb-4 flex items-start gap-3">
              {/* Branch point icon */}
              <div className="absolute -left-5 top-1 flex h-5 w-5 items-center justify-center rounded-full border-2 border-input bg-muted">
                <GitFork size={10} className="text-muted-foreground" />
              </div>

              {/* Content */}
              <div
                className={cn(
                  'flex-1 rounded-lg border border-border bg-muted/60 p-3 transition-colors hover:border-border-hover',
                  isLast && 'border-primary/30'
                )}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <h4 className="truncate text-sm font-medium text-foreground">{branch.label}</h4>
                    {branch.description && (
                      <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{branch.description}</p>
                    )}
                  </div>
                  <GitBranch size={14} className="flex-shrink-0 text-muted-foreground/70" />
                </div>
                {branch.createdAt && (
                  <p className="mt-2 text-[10px] text-muted-foreground">{formatDate(branch.createdAt)}</p>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
