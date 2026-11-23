import { Link } from 'react-router-dom';
import { useRef, useEffect } from 'react';
import { cn } from '@/lib/utils';
import { X, Loader2, MapPin, AlertTriangle, CheckCircle2 } from 'lucide-react';
import type { JourneyStepResult } from '@/graphql/types';

interface TravelJourneyOverlayProps {
  conversationId?: string;
  destinationName: string;
  totalSteps: number;
  steps: JourneyStepResult[];
  isComplete: boolean;
  isInterrupted: boolean;
  interruptReason?: string;
  onCancel: () => void;
  onDismiss: () => void;
  cancelling?: boolean;
  className?: string;
}

const EVENT_BADGES: Record<string, { label: string; color: string }> = {
  encounter: {
    label: 'Encounter',
    color: 'bg-destructive/15 text-destructive border-destructive/30',
  },
  discovery: { label: 'Discovery', color: 'bg-success/15 text-success border-success/30' },
  weather: { label: 'Weather', color: 'bg-primary/15 text-primary border-primary/30' },
  rest: { label: 'Rest', color: 'bg-secondary/15 text-secondary border-secondary/30' },
};

export default function TravelJourneyOverlay({
  conversationId,
  destinationName,
  totalSteps,
  steps,
  isComplete,
  isInterrupted,
  interruptReason,
  onCancel,
  onDismiss,
  cancelling,
  className,
}: TravelJourneyOverlayProps) {
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to latest step
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [steps.length]);

  const currentStep = Math.max(0, ...steps.map((step) => step.stepNumber + 1));
  const isPaused = steps.some((step) => step.journeyPaused);
  const isActive = !isComplete && !isInterrupted && !isPaused;

  return (
    <div
      className={cn(
        'bg-card/95 border-border/60 w-80 max-w-full rounded-lg border shadow-xl backdrop-blur-sm',
        'flex max-h-96 flex-col',
        className,
      )}
    >
      {/* Header */}
      <div className="border-border/60 flex items-center justify-between border-b px-3 py-2.5">
        <div className="flex min-w-0 items-center gap-2">
          {isActive && <Loader2 size={14} className="text-primary flex-shrink-0 animate-spin" />}
          {isComplete && <CheckCircle2 size={14} className="text-success flex-shrink-0" />}
          {isInterrupted && <AlertTriangle size={14} className="text-warning flex-shrink-0" />}
          <div className="min-w-0">
            <p className="text-foreground truncate text-xs font-medium">
              {isPaused
                ? 'Journey paused for an encounter'
                : isComplete
                  ? `Arrived at ${destinationName}`
                  : isInterrupted
                    ? 'Journey Interrupted'
                    : `Traveling to ${destinationName}`}
              {isActive && <span className="animate-pulse">...</span>}
            </p>
          </div>
        </div>
        {isActive ? (
          <button
            onClick={onCancel}
            disabled={cancelling}
            className={cn(
              'text-muted-foreground hover:text-foreground hover:bg-accent/50 flex-shrink-0 rounded p-1 transition-colors',
              cancelling && 'pointer-events-none opacity-50',
            )}
            title="Cancel journey"
          >
            <X size={14} />
          </button>
        ) : (
          <button
            onClick={onDismiss}
            className="text-muted-foreground hover:text-foreground hover:bg-accent/50 flex-shrink-0 rounded p-1 transition-colors"
            title="Dismiss"
          >
            <X size={14} />
          </button>
        )}
      </div>

      {/* Progress bar */}
      <div className="border-border/50 border-b px-3 py-2">
        <div className="flex items-center gap-1.5">
          {Array.from({ length: totalSteps }, (_, i) => {
            const isCompleted = i < currentStep;
            const isCurrent = isActive && i === currentStep;
            const step = steps.find((result) => result.stepNumber === i);
            const eventType = step?.eventType;
            const hasEvent = eventType && eventType !== 'none';

            return (
              <div key={i} className="flex flex-1 flex-col items-center gap-0.5">
                <div
                  className={cn(
                    'h-1.5 w-full rounded-full transition-colors',
                    isCompleted
                      ? hasEvent
                        ? eventType === 'encounter'
                          ? 'bg-destructive'
                          : eventType === 'discovery'
                            ? 'bg-success'
                            : eventType === 'weather'
                              ? 'bg-sky-500'
                              : eventType === 'rest'
                                ? 'bg-violet-500'
                                : 'bg-primary'
                        : 'bg-primary'
                      : isCurrent
                        ? 'bg-primary animate-pulse'
                        : 'bg-accent',
                  )}
                />
              </div>
            );
          })}
        </div>
        <p className="text-muted-foreground mt-1 text-[10px]">
          Step {Math.min(currentStep, totalSteps)}/{totalSteps}
          {steps.length > 0 && steps[steps.length - 1]?.timeOfDay && (
            <span className="ml-2 capitalize">{steps[steps.length - 1].timeOfDay}</span>
          )}
          {steps.length > 0 && steps[steps.length - 1]?.dayNumber != null && (
            <span className="ml-1">Day {steps[steps.length - 1].dayNumber}</span>
          )}
        </p>
      </div>

      {/* Narration scroll area */}
      <div ref={scrollRef} className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 py-2">
        {steps.map((step, idx) => (
          <div key={idx} className="text-xs">
            {step.eventType && step.eventType !== 'none' && EVENT_BADGES[step.eventType] && (
              <span
                className={cn(
                  'mb-1 inline-block rounded-full border px-1.5 py-0.5 text-[10px] font-medium',
                  EVENT_BADGES[step.eventType].color,
                )}
              >
                {EVENT_BADGES[step.eventType].label}
              </span>
            )}
            <p className="text-foreground leading-relaxed">
              {step.narration || 'The party continued onward...'}
            </p>
            {step.discoveredLocationIds && step.discoveredLocationIds.length > 0 && (
              <div className="text-success mt-1 flex items-center gap-1">
                <MapPin size={10} />
                <span className="text-[10px]">
                  {step.discoveredLocationIds.length} location
                  {step.discoveredLocationIds.length > 1 ? 's' : ''} discovered
                </span>
              </div>
            )}
          </div>
        ))}

        {steps.length === 0 && isActive && (
          <p className="text-muted-foreground text-xs italic">Setting out on the journey...</p>
        )}
      </div>

      {isPaused && conversationId && (
        <div className="border-border border-t px-3 py-3">
          <Link
            to={`/chat/${conversationId}${steps.find((step) => step.battleId)?.battleId ? `?battle=${encodeURIComponent(steps.find((step) => step.battleId)!.battleId!)}` : ''}`}
            className="text-primary hover:bg-accent inline-flex rounded-md px-3 py-2 text-sm font-medium"
          >
            Continue encounter in chat
          </Link>
        </div>
      )}
      {/* Completion/interruption footer */}
      {(isComplete || isInterrupted) && (
        <div
          className={cn(
            'border-t px-3 py-2 text-xs',
            isComplete ? 'border-success/40 text-success' : 'border-warning/40 text-warning',
          )}
        >
          {isComplete && <p className="font-medium">Journey complete!</p>}
          {isInterrupted && (
            <>
              <p className="font-medium">Journey interrupted</p>
              {interruptReason && (
                <p className="text-warning mt-0.5 text-[10px]">{interruptReason}</p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
