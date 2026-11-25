import { useEffect, useRef, useState } from 'react';
import { cn } from '@/lib/utils';
import { type Achievement } from '@/graphql/types';
import { Trophy, Star, X } from 'lucide-react';

interface AchievementPopupProps {
  achievement: Achievement;
  onDismiss: () => void;
}

export default function AchievementPopup({ achievement, onDismiss }: AchievementPopupProps) {
  const [isVisible, setIsVisible] = useState(false);
  const [isLeaving, setIsLeaving] = useState(false);

  useEffect(() => {
    // Trigger enter animation. Track the handle so unmount cancels it.
    const rafId = requestAnimationFrame(() => setIsVisible(true));

    // Auto-dismiss after 5 seconds. Track both the outer and the nested
    // exit-animation timer so unmount clears all of them — the inner
    // setTimeout(onDismiss, 300) previously fired on unmounted parents.
    let exitTimer: ReturnType<typeof setTimeout> | null = null;
    const timer = setTimeout(() => {
      setIsLeaving(true);
      exitTimer = setTimeout(onDismiss, 300);
    }, 5000);

    return () => {
      cancelAnimationFrame(rafId);
      clearTimeout(timer);
      if (exitTimer !== null) clearTimeout(exitTimer);
    };
  }, [onDismiss]);

  const dismissTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (dismissTimerRef.current !== null) clearTimeout(dismissTimerRef.current);
  }, []);

  const handleDismiss = () => {
    setIsLeaving(true);
    dismissTimerRef.current = setTimeout(onDismiss, 300);
  };

  return (
    <div
      className={cn(
        'pointer-events-auto w-80 max-w-[calc(100vw-2rem)] overflow-hidden rounded-xl border border-warning/30 bg-card shadow-2xl shadow-yellow-500/10 transition-all duration-300',
        isVisible && !isLeaving ? 'translate-y-0 opacity-100' : 'translate-y-4 opacity-0'
      )}
    >
      {/* Shimmer top border */}
      <div className="h-1 w-full bg-gradient-to-r from-yellow-600 via-yellow-400 to-yellow-600" />

      <div className="relative p-4">
        {/* Dismiss button */}
        <button
          type="button"
          onClick={handleDismiss}
          aria-label="Dismiss achievement"
          title="Dismiss"
          className="absolute right-1 top-1 inline-flex h-10 w-10 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground sm:right-2 sm:top-2 sm:h-7 sm:w-7"
        >
          <X size={14} />
        </button>

        <div className="flex items-start gap-3">
          {/* Icon */}
          <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-lg bg-warning/15">
            {achievement.icon ? (
              <span className="text-2xl">{achievement.icon}</span>
            ) : (
              <Trophy className="h-6 w-6 text-warning" />
            )}
          </div>

          {/* Content */}
          <div className="min-w-0 flex-1 pr-8 sm:pr-6">
            <p className="text-xs font-medium uppercase tracking-wider text-warning">
              Achievement Unlocked
            </p>
            <h4 className="mt-0.5 truncate text-sm font-semibold text-foreground">
              {achievement.name}
            </h4>
            <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
              {achievement.description}
            </p>

            {/* XP reward */}
            {achievement.xpReward && achievement.xpReward > 0 && (
              <div className="mt-2 inline-flex items-center gap-1 rounded-full bg-warning/10 px-2 py-0.5">
                <Star size={12} className="text-warning" />
                <span className="text-xs font-semibold text-warning">
                  +{achievement.xpReward} XP
                </span>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
