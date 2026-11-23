import { useState } from 'react';
import { cn } from '@/lib/utils';
import { formatDate } from '@/lib/utils';
import { useAgentRuns } from '@/hooks/useRoleplay';
import { type AgentRun } from '@/graphql/types';
import {
  ChevronDown,
  ChevronRight,
  Highlighter,
  Sparkles,
  BookOpen,
  Calculator,
  ShieldCheck,
  Clock,
  CheckCircle2,
  XCircle,
  Loader2,
  Activity,
} from 'lucide-react';

interface AgentInsightsProps {
  conversationId: string;
}

const AGENT_ICONS: Record<string, React.ReactNode> = {
  highlighter: <Highlighter size={14} className="text-warning" />,
  muse: <Sparkles size={14} className="text-primary" />,
  narrator: <BookOpen size={14} className="text-primary" />,
  accountant: <Calculator size={14} className="text-success" />,
  skeptic: <ShieldCheck size={14} className="text-destructive" />,
};

function getAgentIcon(agentName: string): React.ReactNode {
  const key = agentName.toLowerCase();
  return AGENT_ICONS[key] ?? <Activity size={14} className="text-muted-foreground" />;
}

function getStatusIcon(status: string): React.ReactNode {
  switch (status) {
    case 'running':
      return <Loader2 size={12} className="animate-spin text-warning" />;
    case 'success':
      return <CheckCircle2 size={12} className="text-success" />;
    case 'error':
    case 'failed':
      return <XCircle size={12} className="text-destructive" />;
    case 'pending':
    default:
      return <Clock size={12} className="text-muted-foreground" />;
  }
}

function getStatusBadge(status: string): string {
  switch (status) {
    case 'running':
      return 'bg-warning/15 text-warning';
    case 'success':
      return 'bg-success/15 text-success';
    case 'error':
    case 'failed':
      return 'bg-destructive/15 text-destructive';
    case 'pending':
    default:
      return 'bg-accent/60 text-muted-foreground';
  }
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
}

/**
 * Panel heading shared by the loading, empty and populated states.
 *
 * The panel is 255px wide inside the tool strip, so the summary sits on its own
 * line under the title. Placing the title and the counters side by side on one
 * row squeezed both and wrapped each word onto a separate line.
 */
function PanelHeader({
  successCount,
  failedCount = 0,
  avgDurationMs,
}: {
  successCount?: number;
  failedCount?: number;
  avgDurationMs?: number;
}) {
  const average = Math.round(avgDurationMs ?? 0);
  const hasSummary = successCount != null || failedCount > 0 || average > 0;

  return (
    <div className="mb-3 space-y-1">
      <div className="flex items-center gap-2">
        <Activity size={16} className="shrink-0 text-muted-foreground" />
        <h3 className="truncate text-sm font-semibold text-foreground">Agent Insights</h3>
      </div>
      {hasSummary && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 pl-6 text-[11px] text-muted-foreground">
          {successCount != null && <span>{successCount} completed</span>}
          {failedCount > 0 && <span className="text-destructive">{failedCount} failed</span>}
          {average > 0 && <span>avg {formatDuration(average)}</span>}
        </div>
      )}
    </div>
  );
}

function AgentRunItem({ run }: { run: AgentRun }) {
  const [isExpanded, setIsExpanded] = useState(false);

  return (
    <div className="rounded-lg bg-muted/40 transition-colors hover:bg-muted/60">
      {/* Collapsed header: name and badge share the first line, the pass and
          timing the second. Appending the pass to the name overflowed the row
          and truncated the agent name it was meant to qualify. */}
      <button
        type="button"
        onClick={() => setIsExpanded(!isExpanded)}
        aria-expanded={isExpanded}
        className="flex w-full items-start gap-2 px-3 py-2.5 text-left"
      >
        <span className="mt-0.5 shrink-0 text-muted-foreground">
          {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </span>

        <span className="mt-0.5 shrink-0">{getAgentIcon(run.agentName)}</span>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span className="min-w-0 truncate text-sm font-medium text-foreground">
              {run.agentName}
            </span>
            <span
              className={cn(
                'inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] leading-none',
                getStatusBadge(run.status)
              )}
            >
              {getStatusIcon(run.status)}
              {run.status}
            </span>
          </div>

          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[10px] text-muted-foreground">
            {run.agentPass && <span>{run.agentPass}</span>}
            {run.durationMs != null && (
              <span className="flex items-center gap-1 font-mono">
                <Clock size={10} />
                {formatDuration(run.durationMs)}
              </span>
            )}
            {run.createdAt && <span>{formatDate(run.createdAt)}</span>}
          </div>

          {run.errorMessage && (
            <p className="mt-0.5 line-clamp-2 break-words text-[10px] text-destructive">
              {run.errorMessage}
            </p>
          )}
        </div>
      </button>

      {/* Expanded details */}
      {isExpanded && (
        <div className="space-y-1 border-t border-border/60 px-3 py-2.5 pl-10">
          {run.modelUsed && (
            <p className="break-words text-[10px] text-muted-foreground">Model: {run.modelUsed}</p>
          )}
          {(run.inputTokens != null || run.outputTokens != null) && (
            <p className="text-[10px] text-muted-foreground">
              Tokens: {run.inputTokens ?? '?'} in / {run.outputTokens ?? '?'} out
            </p>
          )}
          {run.errorMessage && (
            <p className="break-words text-xs text-destructive">{run.errorMessage}</p>
          )}
        </div>
      )}
    </div>
  );
}

export default function AgentInsights({ conversationId }: AgentInsightsProps) {
  const { runs, loading } = useAgentRuns(conversationId, 50);

  if (loading) {
    return (
      <div className="rounded-xl border border-border bg-card p-4">
        <PanelHeader />
        <div className="flex justify-center py-6">
          <Loader2 size={16} className="animate-spin text-muted-foreground" />
        </div>
      </div>
    );
  }

  if (runs.length === 0) {
    return (
      <div className="rounded-xl border border-border bg-card p-4">
        <PanelHeader />
        <p className="py-6 text-center text-xs text-muted-foreground">
          No agent runs yet. Agent insights will appear here after the pipeline processes a message.
        </p>
      </div>
    );
  }

  const successCount = runs.filter((r: AgentRun) => r.status === 'success').length;
  const failedCount = runs.filter((r: AgentRun) => r.status === 'error' || r.status === 'failed').length;
  const runsWithDuration = runs.filter((r: AgentRun) => r.durationMs != null);
  const avgDuration =
    runsWithDuration.length > 0
      ? runsWithDuration.reduce((sum: number, r: AgentRun) => sum + (r.durationMs ?? 0), 0) /
        runsWithDuration.length
      : 0;

  return (
    <div className="rounded-xl border border-border bg-card p-4">
      <PanelHeader
        successCount={successCount}
        failedCount={failedCount}
        avgDurationMs={avgDuration}
      />

      {/* Run list */}
      <div className="custom-scrollbar flex max-h-96 flex-col gap-1.5 overflow-y-auto">
        {runs.map((run: AgentRun) => (
          <AgentRunItem key={run.runId} run={run} />
        ))}
      </div>
    </div>
  );
}
