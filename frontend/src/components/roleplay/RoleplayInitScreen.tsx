import { useState, useEffect, useCallback } from 'react';
import { print } from 'graphql';
import { GROUP_STATE_CHANGED_SUBSCRIPTION } from '@/graphql/operations';
import { useWebSocketSubscription } from '@/lib/websocket-client';
import { cn } from '@/lib/utils';
import { Sparkles } from 'lucide-react';

const GROUP_STATE_CHANGED_QUERY = print(GROUP_STATE_CHANGED_SUBSCRIPTION);

interface RoleplayInitScreenProps {
  conversationId: string;
  onComplete: () => void;
}

interface Step {
  label: string;
  range: [number, number];
}

const STEPS: Step[] = [
  { label: 'Generating world lore', range: [0, 30] },
  { label: 'Building world map', range: [30, 60] },
  { label: 'Setting the scene', range: [60, 90] },
  { label: 'Ready to play', range: [90, 100] },
];

const STEP_DURATION_MS = 4000;

export default function RoleplayInitScreen({ conversationId, onComplete }: RoleplayInitScreenProps) {
  const [progress, setProgress] = useState(0);
  const [done, setDone] = useState(false);
  const [fadeOut, setFadeOut] = useState(false);

  // Subscribe to group state changes to detect completion
  const { data: groupStateData } = useWebSocketSubscription(
    GROUP_STATE_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  useEffect(() => {
    if (groupStateData?.groupStateChanged?.processingOnly) return;
    const newState = groupStateData?.groupStateChanged?.groupState;
    if (newState && newState !== 'INITIALIZING') {
      setDone(true);
    }
  }, [groupStateData]);

  // Animate progress based on time estimates
  useEffect(() => {
    if (done) return;

    const interval = setInterval(() => {
      setProgress((prev) => {
        // Slow down as we approach 90% (waiting for backend)
        if (prev >= 88) return prev + 0.1;
        if (prev >= 75) return prev + 0.3;
        return prev + (90 / (STEP_DURATION_MS * 3 / 50));
      });
    }, 50);

    return () => clearInterval(interval);
  }, [done]);

  // When done, animate to 100% and fade out
  useEffect(() => {
    if (!done) return;

    // Quickly animate to 100
    const rampInterval = setInterval(() => {
      setProgress((prev) => {
        if (prev >= 100) {
          clearInterval(rampInterval);
          return 100;
        }
        return prev + 3;
      });
    }, 30);

    // Fade out after reaching 100
    const fadeTimer = setTimeout(() => {
      setFadeOut(true);
    }, 800);

    const completeTimer = setTimeout(() => {
      onComplete();
    }, 1400);

    return () => {
      clearInterval(rampInterval);
      clearTimeout(fadeTimer);
      clearTimeout(completeTimer);
    };
  }, [done, onComplete]);

  const currentStepIndex = STEPS.findIndex(
    (step) => progress < step.range[1]
  );
  const activeStep = currentStepIndex === -1 ? STEPS.length - 1 : currentStepIndex;

  const getStepStatus = useCallback((index: number): 'done' | 'active' | 'pending' => {
    if (index < activeStep) return 'done';
    if (index === activeStep) return 'active';
    return 'pending';
  }, [activeStep]);

  return (
    <div
      className={cn(
        'absolute inset-0 z-20 flex items-center justify-center',
        'bg-background/95 backdrop-blur-sm transition-opacity duration-500',
        fadeOut ? 'opacity-0 pointer-events-none' : 'opacity-100'
      )}
    >
      <div className="w-full max-w-md px-6">
        {/* Title */}
        <div className="mb-8 text-center">
          <div className="mb-4 inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10">
            <Sparkles size={28} className="text-primary animate-pulse" />
          </div>
          <h2 className="text-xl font-bold text-foreground">Preparing Your World</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Your adventure is being crafted...
          </p>
        </div>

        {/* Steps */}
        <div className="mb-6 space-y-3">
          {STEPS.map((step, i) => {
            const status = getStepStatus(i);
            return (
              <div key={step.label} className="flex items-center gap-3">
                {/* Status indicator */}
                <div className="flex h-6 w-6 flex-shrink-0 items-center justify-center">
                  {status === 'done' ? (
                    <div className="flex h-5 w-5 items-center justify-center rounded-full bg-success/15 text-success">
                      <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                        <path d="M2.5 6L5 8.5L9.5 3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </div>
                  ) : status === 'active' ? (
                    <div className="h-2.5 w-2.5 rounded-full bg-primary animate-pulse" />
                  ) : (
                    <div className="h-2 w-2 rounded-full bg-muted-foreground/30" />
                  )}
                </div>
                {/* Label */}
                <span
                  className={cn(
                    'text-sm transition-colors duration-300',
                    status === 'done' && 'text-success',
                    status === 'active' && 'text-foreground font-medium',
                    status === 'pending' && 'text-muted-foreground'
                  )}
                >
                  {step.label}
                  {status === 'active' && '...'}
                </span>
              </div>
            );
          })}
        </div>

        {/* Progress bar */}
        <div className="space-y-2">
          <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
            <div
              className="h-full rounded-full bg-gradient-to-r from-primary to-primary-hover transition-all duration-300 ease-out"
              style={{ width: `${Math.min(progress, 100)}%` }}
            />
          </div>
          <p className="text-center text-xs text-muted-foreground">
            {Math.min(Math.round(progress), 100)}%
          </p>
        </div>
      </div>
    </div>
  );
}
