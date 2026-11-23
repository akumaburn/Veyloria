import { cn } from '@/lib/utils';
import { Loader2, Clock } from 'lucide-react';

interface ImageGenerationProgressProps {
  progress: {
    steps: number;
    totalSteps: number;
    elapsed: number;
    previewUrl?: string;
  };
}

function formatElapsed(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  return `${minutes}m ${remainingSeconds}s`;
}

export default function ImageGenerationProgress({ progress }: ImageGenerationProgressProps) {
  const { steps, totalSteps, elapsed, previewUrl } = progress;
  const percentage = totalSteps > 0 ? Math.min((steps / totalSteps) * 100, 100) : 0;

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-muted/80">
      {/* Preview area */}
      <div className="relative aspect-video w-full bg-card">
        {previewUrl ? (
          <img
            src={previewUrl}
            alt="Generation preview"
            className="h-full w-full object-contain opacity-80"
          />
        ) : (
          <div className="flex h-full items-center justify-center">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          </div>
        )}
        {/* Overlay with step info */}
        <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/75 to-transparent p-3">
          <div className="flex items-center justify-between text-xs text-foreground">
            <span>
              Step {steps} / {totalSteps}
            </span>
            <span className="inline-flex items-center gap-1">
              <Clock size={12} />
              {formatElapsed(elapsed)}
            </span>
          </div>
        </div>
      </div>

      {/* Progress bar */}
      <div className="px-3 py-2.5">
        <div className="h-2 w-full overflow-hidden rounded-full bg-accent">
          <div
            className={cn(
              'h-full rounded-full transition-all duration-300 ease-out',
              percentage < 100 ? 'bg-blue-500' : 'bg-success'
            )}
            style={{ width: `${percentage}%` }}
          />
        </div>
        <p className="mt-1.5 text-center text-xs text-muted-foreground">
          {percentage < 100 ? 'Generating...' : 'Complete'}
          <span className="ml-2 font-mono">{Math.round(percentage)}%</span>
        </p>
      </div>
    </div>
  );
}
