import { cn } from '@/lib/utils';
import { type CharacterRelationship } from '@/graphql/types';
import { Heart, Swords, Users, Shield, Flame, HelpCircle } from 'lucide-react';

interface RelationshipCardProps {
  relationship: CharacterRelationship;
}

const RELATIONSHIP_CONFIG: Record<string, { color: string; bgColor: string; icon: React.ReactNode }> = {
  ally: {
    color: 'text-success',
    bgColor: 'bg-success/15',
    icon: <Shield size={14} />,
  },
  friend: {
    color: 'text-secondary',
    bgColor: 'bg-sky-500/15',
    icon: <Users size={14} />,
  },
  enemy: {
    color: 'text-destructive',
    bgColor: 'bg-destructive/15',
    icon: <Swords size={14} />,
  },
  rival: {
    color: 'text-warning',
    bgColor: 'bg-primary/15',
    icon: <Flame size={14} />,
  },
  lover: {
    color: 'text-primary',
    bgColor: 'bg-pink-500/15',
    icon: <Heart size={14} />,
  },
  neutral: {
    color: 'text-muted-foreground',
    bgColor: 'bg-muted',
    icon: <HelpCircle size={14} />,
  },
};

function getRelationshipConfig(type: string) {
  const key = type.toLowerCase();
  return (
    RELATIONSHIP_CONFIG[key] ?? {
      color: 'text-muted-foreground',
      bgColor: 'bg-muted',
      icon: <HelpCircle size={14} />,
    }
  );
}

function getIntensityColor(intensity: number): string {
  if (intensity >= 80) return 'bg-purple-500';
  if (intensity >= 60) return 'bg-blue-500';
  if (intensity >= 40) return 'bg-cyan-500';
  if (intensity >= 20) return 'bg-teal-500';
  return 'bg-muted-foreground';
}

export default function RelationshipCard({ relationship }: RelationshipCardProps) {
  const config = getRelationshipConfig(relationship.relationshipType);
  const intensity = relationship.intensity ?? 50;
  const intensityPercent = Math.min(Math.max(intensity, 0), 100);

  return (
    <div className="rounded-xl border border-border bg-card p-4 transition-colors hover:border-border-hover">
      {/* Character pair */}
      <div className="mb-3 flex items-center gap-3">
        <div className="flex h-9 w-9 items-center justify-center rounded-full bg-accent text-sm font-semibold text-foreground">
          {relationship.fromCharacterId.charAt(0).toUpperCase()}
        </div>
        <div className="flex flex-col items-center">
          <span className={cn('text-xs', config.color)}>
            {config.icon}
          </span>
          <div className="h-px w-6 bg-border" />
        </div>
        <div className="flex h-9 w-9 items-center justify-center rounded-full bg-accent text-sm font-semibold text-foreground">
          {relationship.toCharacterId.charAt(0).toUpperCase()}
        </div>
      </div>

      {/* Type badge */}
      <div className="mb-2 flex items-center gap-2">
        <span
          className={cn(
            'inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium capitalize',
            config.bgColor,
            config.color
          )}
        >
          {config.icon}
          {relationship.relationshipType}
        </span>
        {relationship.isReciprocal && (
          <span className="rounded-md bg-accent/50 px-1.5 py-0.5 text-xs text-muted-foreground">
            mutual
          </span>
        )}
      </div>

      {/* Intensity bar */}
      <div className="mb-2">
        <div className="mb-1 flex items-center justify-between">
          <span className="text-xs text-muted-foreground">Intensity</span>
          <span className="text-xs font-mono text-muted-foreground">{intensity}%</span>
        </div>
        <div className="h-1.5 w-full overflow-hidden rounded-full bg-accent/50">
          <div
            className={cn(
              'h-full rounded-full transition-all duration-500 ease-out',
              getIntensityColor(intensity)
            )}
            style={{ width: `${intensityPercent}%` }}
          />
        </div>
      </div>

      {/* Description */}
      {relationship.description && (
        <p className="text-xs leading-relaxed text-muted-foreground">
          {relationship.description}
        </p>
      )}
    </div>
  );
}
