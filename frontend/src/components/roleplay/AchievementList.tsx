import { useEffect, useMemo, useState } from 'react';
import { cn } from '@/lib/utils';
import { useAchievementStore } from '@/stores/achievementStore';
import { useAchievementUnlocked } from '@/hooks/useRoleplay';
import {
  Trophy,
  Star,
  Filter,
  Lock,
  CheckCircle2,
  Target,
  Sword,
  Heart,
  Map,
  Package,
  Timer,
  GitBranch,
  Moon,
  Skull,
  Shield,
  Flame,
  Crown,
  Compass,
  Footprints,
  Coins,
  Key,
  Eye,
  BookOpen,
  Library,
  Pencil,
  HeartHandshake,
  MessageSquare,
  Dices,
  Sparkles,
  type LucideIcon,
} from 'lucide-react';

/**
 * Achievement records store a lucide icon name. Resolve the names the seeding
 * and generation paths write, and fall back to the trophy for anything newer.
 */
const ACHIEVEMENT_ICONS: Record<string, LucideIcon> = {
  target: Target,
  sword: Sword,
  heart: Heart,
  map: Map,
  package: Package,
  timer: Timer,
  'git-branch': GitBranch,
  moon: Moon,
  skull: Skull,
  shield: Shield,
  flame: Flame,
  crown: Crown,
  compass: Compass,
  footprints: Footprints,
  coins: Coins,
  key: Key,
  eye: Eye,
  'book-open': BookOpen,
  library: Library,
  pencil: Pencil,
  'heart-handshake': HeartHandshake,
  'message-square': MessageSquare,
  dices: Dices,
  sparkles: Sparkles,
};

function AchievementIcon({ name, className }: { name?: string | null; className?: string }) {
  if (!name) return <Trophy size={20} className={className} />;
  const Icon = ACHIEVEMENT_ICONS[name.toLowerCase()];
  if (Icon) return <Icon size={20} className={className} />;
  // A short glyph field is an emoji the author chose, not an icon name.
  if (Array.from(name).length <= 2) return <span className="text-xl">{name}</span>;
  return <Trophy size={20} className={className} />;
}

interface AchievementListProps {
  userId?: string;
}

export default function AchievementList({ userId }: AchievementListProps) {
  const { achievements, userAchievements, isLoading, fetchAchievements, fetchUserAchievements, addUserAchievement } =
    useAchievementStore();
  const [categoryFilter, setCategoryFilter] = useState<string>('all');

  // Subscribe to real-time achievement unlocks
  const { achievement: unlockedEvent } = useAchievementUnlocked(userId || '');

  useEffect(() => {
    fetchAchievements();
  }, [fetchAchievements]);

  useEffect(() => {
    if (userId) {
      fetchUserAchievements(userId);
    }
  }, [userId, fetchUserAchievements]);

  // Update store when a new achievement is unlocked via subscription
  useEffect(() => {
    if (unlockedEvent) {
      addUserAchievement(unlockedEvent);
    }
  }, [unlockedEvent, addUserAchievement]);

  const unlockedIds = useMemo(
    () => new Set(userAchievements.filter((ua) => ua.isComplete).map((ua) => ua.achievementId)),
    [userAchievements]
  );

  const categories = useMemo(() => {
    const cats = new Set(achievements.map((a) => a.category).filter(Boolean));
    return ['all', ...Array.from(cats)] as string[];
  }, [achievements]);

  const filtered = useMemo(
    () =>
      categoryFilter === 'all'
        ? achievements
        : achievements.filter((a) => a.category === categoryFilter),
    [achievements, categoryFilter]
  );

  if (isLoading) {
    return (
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="h-32 animate-pulse rounded-lg bg-muted" />
        ))}
      </div>
    );
  }

  if (filtered.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <div className="bg-primary/10 text-primary mb-4 flex h-10 w-10 items-center justify-center rounded-2xl">
          <Trophy size={20} aria-hidden="true" />
        </div>
        <p className="text-foreground text-sm font-semibold">
          {categoryFilter === 'all' ? 'No achievements yet' : `Nothing in ${categoryFilter}`}
        </p>
        <p className="text-muted-foreground mt-1 text-sm">
          {categoryFilter === 'all'
            ? 'Start a roleplay to begin unlocking them.'
            : 'Choose another category to see what is available.'}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Category filter */}
      {categories.length > 2 && (
        <div className="flex flex-wrap items-center gap-2">
          <Filter size={14} className="flex-shrink-0 text-muted-foreground" />
          {categories.map((cat) => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={cn(
                'rounded-md px-2.5 py-1 text-xs font-medium whitespace-nowrap transition-colors',
                categoryFilter === cat
                  ? 'bg-primary/20 text-primary'
                  : 'bg-muted text-muted-foreground hover:bg-accent'
              )}
            >
              {cat === 'all' ? 'All' : cat}
            </button>
          ))}
        </div>
      )}

      {/* Achievement grid */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        {filtered.map((achievement) => {
          const unlocked = unlockedIds.has(achievement.achievementId);
          return (
            <div
              key={achievement.achievementId}
              className={cn(
                'relative flex flex-col items-center rounded-2xl border p-4 text-center transition-colors',
                unlocked
                  ? 'border-warning/50 bg-warning/5 hover:border-warning'
                  : 'border-border bg-card hover:border-border-hover'
              )}
            >
              {unlocked ? (
                <CheckCircle2
                  size={14}
                  className="text-success absolute top-2 right-2"
                  aria-label="Unlocked"
                />
              ) : (
                <Lock
                  size={13}
                  className="text-muted-foreground/70 absolute top-2 right-2"
                  aria-hidden="true"
                />
              )}

              <div
                className={cn(
                  'mb-2.5 flex h-10 w-10 items-center justify-center rounded-xl',
                  unlocked ? 'bg-warning/15 text-warning' : 'bg-muted text-muted-foreground/70'
                )}
              >
                <AchievementIcon name={achievement.icon} />
              </div>

              <h4 className="text-foreground text-sm font-semibold">{achievement.name}</h4>
              <p className="text-muted-foreground mt-1 line-clamp-2 text-xs">
                {achievement.isHidden && !unlocked ? '???' : achievement.description}
              </p>

              {achievement.xpReward && achievement.xpReward > 0 && (
                <div className="mt-2 inline-flex items-center gap-0.5">
                  <Star size={11} className={unlocked ? 'text-warning' : 'text-muted-foreground'} />
                  <span
                    className={cn(
                      'text-[11px] font-medium',
                      unlocked ? 'text-warning' : 'text-muted-foreground'
                    )}
                  >
                    {achievement.xpReward} XP
                  </span>
                </div>
              )}

              {achievement.category && (
                <span className="bg-muted text-muted-foreground mt-1.5 rounded-md px-1.5 py-0.5 text-[10px]">
                  {achievement.category}
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
