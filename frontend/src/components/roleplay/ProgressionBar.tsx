import { useEffect } from 'react';
import { useAchievementStore } from '@/stores/achievementStore';
import { Star, BookOpen, MessageSquare, Dices, Clock } from 'lucide-react';

interface ProgressionBarProps {
  userId?: string;
}

function xpForLevel(level: number): number {
  return level * level * 100;
}

export default function ProgressionBar({ userId }: ProgressionBarProps) {
  const { progression, isLoading, fetchProgression } = useAchievementStore();

  useEffect(() => {
    fetchProgression(userId);
  }, [userId, fetchProgression]);

  if (isLoading || !progression) {
    return (
      <div className="h-20 animate-pulse rounded-lg bg-muted" />
    );
  }

  const level = progression.level ?? 1;
  const totalXP = progression.totalXP ?? 0;
  const currentLevelXP = xpForLevel(level);
  const nextLevelXP = xpForLevel(level + 1);
  const xpIntoLevel = Math.max(0, totalXP - currentLevelXP);
  const xpNeeded = nextLevelXP - currentLevelXP;
  const percentage = xpNeeded > 0 ? Math.min((xpIntoLevel / xpNeeded) * 100, 100) : 0;

  const stats = [
    { icon: BookOpen, label: 'Stories', value: progression.storiesCompleted ?? 0 },
    { icon: MessageSquare, label: 'Messages', value: progression.totalMessages ?? 0 },
    { icon: Dices, label: 'Dice Rolls', value: progression.totalDiceRolls ?? 0 },
    { icon: Clock, label: 'Playtime', value: `${Math.floor((progression.totalPlaytimeMin ?? 0) / 60)}h` },
  ];

  return (
    <div className="rounded-2xl border border-border bg-card p-4">
      {/* Level and title */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/20">
            <span className="text-sm font-bold text-primary">{level}</span>
          </div>
          <div>
            <h3 className="text-sm font-semibold text-foreground">
              {progression.title ?? `Level ${level}`}
            </h3>
            <p className="text-xs text-muted-foreground">
              {totalXP.toLocaleString()} total XP
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1 text-xs text-muted-foreground">
          <Star size={12} className="text-warning" />
          <span>{xpIntoLevel.toLocaleString()} / {xpNeeded.toLocaleString()} XP</span>
        </div>
      </div>

      {/* XP bar */}
      <div className="mt-3 h-2.5 w-full overflow-hidden rounded-full bg-border/50">
        <div
          className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all duration-500 ease-out"
          style={{ width: `${percentage}%` }}
        />
      </div>

      {/* Stats row */}
      <div className="mt-3 grid grid-cols-4 gap-2">
        {stats.map(({ icon: Icon, label, value }) => (
          <div key={label} className="bg-muted/60 flex flex-col items-center rounded-lg py-2">
            <Icon size={14} className="text-muted-foreground mb-1" />
            <span className="text-foreground text-sm font-semibold">{value}</span>
            <span className="text-muted-foreground text-xs">{label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
