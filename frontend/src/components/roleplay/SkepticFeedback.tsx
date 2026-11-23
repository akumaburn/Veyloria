import { cn } from '@/lib/utils';
import {
  ShieldCheck,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  RotateCcw,
  BookOpen,
  Users,
} from 'lucide-react';

type GateResult = 'pass' | 'fail' | 'retry';

interface SkepticFeedbackData {
  narrationGateResult: GateResult;
  characterGateResult: GateResult;
  feedbackText?: string;
  retryCount: number;
}

interface SkepticFeedbackProps {
  feedback: SkepticFeedbackData;
}

function getGateIcon(result: GateResult): React.ReactNode {
  switch (result) {
    case 'pass':
      return <CheckCircle2 size={14} className="text-success" />;
    case 'retry':
      return <AlertTriangle size={14} className="text-warning" />;
    case 'fail':
      return <XCircle size={14} className="text-destructive" />;
  }
}

function getGateStyle(result: GateResult): string {
  switch (result) {
    case 'pass':
      return 'bg-success/10 ring-success/30 text-success';
    case 'retry':
      return 'bg-warning/10 ring-warning/30 text-warning';
    case 'fail':
      return 'bg-destructive/10 ring-destructive/30 text-destructive';
  }
}

function getGateLabel(result: GateResult): string {
  switch (result) {
    case 'pass':
      return 'Passed';
    case 'retry':
      return 'Retry';
    case 'fail':
      return 'Failed';
  }
}

function getOverallStatus(feedback: SkepticFeedbackData): {
  label: string;
  style: string;
  icon: React.ReactNode;
} {
  if (
    feedback.narrationGateResult === 'pass' &&
    feedback.characterGateResult === 'pass'
  ) {
    return {
      label: 'Quality Check Passed',
      style: 'text-success',
      icon: <CheckCircle2 size={16} className="text-success" />,
    };
  }
  if (
    feedback.narrationGateResult === 'fail' ||
    feedback.characterGateResult === 'fail'
  ) {
    return {
      label: 'Quality Check Failed',
      style: 'text-destructive',
      icon: <XCircle size={16} className="text-destructive" />,
    };
  }
  return {
    label: 'Retry Requested',
    style: 'text-warning',
    icon: <AlertTriangle size={16} className="text-warning" />,
  };
}

export default function SkepticFeedback({ feedback }: SkepticFeedbackProps) {
  const overall = getOverallStatus(feedback);

  return (
    <div className="rounded-xl border border-border bg-card p-4">
      {/* Header */}
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <ShieldCheck size={16} className="text-destructive" />
          <h4 className="text-sm font-semibold text-foreground">Skeptic QC</h4>
        </div>
        <div className={cn('flex items-center gap-1.5 text-xs font-medium', overall.style)}>
          {overall.icon}
          {overall.label}
        </div>
      </div>

      {/* Gate results */}
      <div className="grid grid-cols-2 gap-2 mb-3">
        {/* Narration Gate */}
        <div
          className={cn(
            'flex items-center gap-2 rounded-lg p-2.5 ring-1 transition-colors',
            getGateStyle(feedback.narrationGateResult)
          )}
        >
          <BookOpen size={14} />
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium">Narration Gate</p>
            <div className="flex items-center gap-1 mt-0.5">
              {getGateIcon(feedback.narrationGateResult)}
              <span className="text-xs">{getGateLabel(feedback.narrationGateResult)}</span>
            </div>
          </div>
        </div>

        {/* Character Gate */}
        <div
          className={cn(
            'flex items-center gap-2 rounded-lg p-2.5 ring-1 transition-colors',
            getGateStyle(feedback.characterGateResult)
          )}
        >
          <Users size={14} />
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium">Character Gate</p>
            <div className="flex items-center gap-1 mt-0.5">
              {getGateIcon(feedback.characterGateResult)}
              <span className="text-xs">{getGateLabel(feedback.characterGateResult)}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Retry count */}
      {feedback.retryCount > 0 && (
        <div className="mb-3 flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2">
          <RotateCcw size={12} className="text-warning" />
          <span className="text-xs text-foreground">
            Retried{' '}
            <span className="font-mono font-bold text-warning">
              {feedback.retryCount}
            </span>{' '}
            {feedback.retryCount === 1 ? 'time' : 'times'}
          </span>
        </div>
      )}

      {/* Feedback text */}
      {feedback.feedbackText && (
        <div className="rounded-lg bg-muted/40 p-3">
          <p className="mb-1 text-xs font-medium text-muted-foreground uppercase tracking-wider">
            Feedback
          </p>
          <p className="text-xs text-foreground leading-relaxed whitespace-pre-wrap">
            {feedback.feedbackText}
          </p>
        </div>
      )}
    </div>
  );
}
