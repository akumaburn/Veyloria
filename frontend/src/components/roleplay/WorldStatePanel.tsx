import { useEffect, useRef } from 'react';
import { Sun, Moon, Cloud, MapPin, Clock, Sunrise, Sunset, Theater, Sparkles } from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { useWorldState, useWorldStateChanged } from '@/hooks/useRoleplay';
import { cn } from '@/lib/utils';

interface WorldStatePanelProps {
  conversationId: string;
  className?: string;
}

function getTimeIcon(timeOfDay?: string) {
  switch (timeOfDay) {
    case 'dawn':
    case 'morning':
      return <Sunrise size={16} className="text-warning" />;
    case 'midday':
    case 'afternoon':
      return <Sun size={16} className="text-warning" />;
    case 'dusk':
    case 'evening':
      return <Sunset size={16} className="text-primary" />;
    case 'night':
    case 'midnight':
      return <Moon size={16} className="text-primary" />;
    default:
      return <Clock size={16} className="text-muted-foreground" />;
  }
}

export default function WorldStatePanel({ conversationId, className }: WorldStatePanelProps) {
  const { worldState, loading, refetch } = useWorldState(conversationId);

  // Subscribe to real-time world state changes from the agent pipeline
  const { worldState: wsEvent } = useWorldStateChanged(conversationId);

  // Use a ref to stabilize refetch and prevent subscription flapping
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;

  useEffect(() => {
    if (wsEvent) refetchRef.current();
  }, [wsEvent]);

  if (loading && !worldState) {
    return (
      <Card className={cn('animate-pulse', className)}>
        <div className="space-y-3 p-4">
          <div className="h-4 w-32 rounded bg-muted" />
          <div className="h-3 w-full rounded bg-muted" />
          <div className="h-3 w-full rounded bg-muted" />
          <div className="h-3 w-3/4 rounded bg-muted" />
        </div>
      </Card>
    );
  }

  if (!worldState) {
    return (
      <Card className={cn('p-4', className)}>
        <p className="text-sm text-muted-foreground">No world state for this conversation.</p>
      </Card>
    );
  }

  return (
    <Card className={cn('overflow-hidden', className)}>
      {/* Header */}
      <div className="border-b border-border/50 px-4 py-3">
        <h3 className="text-sm font-semibold text-foreground">World State</h3>
      </div>

      {/* Fields */}
      <div className="space-y-3 p-4">
        {/* Time of Day */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            {getTimeIcon(worldState.timeOfDay)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Time of Day</p>
            <p className="text-sm font-medium capitalize break-words text-foreground">
              {worldState.timeOfDay || 'Unset'}
            </p>
          </div>
        </div>

        {/* Day Number */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            <Clock size={16} className="text-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Day</p>
            <p className="text-sm font-medium break-words text-foreground">
              Day {worldState.dayNumber ?? 1}
            </p>
          </div>
        </div>

        {/* Weather */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            <Cloud size={16} className="text-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Weather</p>
            <p className="text-sm font-medium capitalize break-words text-foreground">
              {worldState.weather || 'Unset'}
            </p>
          </div>
        </div>

        {/* Mood */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            <Sparkles size={16} className="text-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Mood</p>
            <p className="text-sm font-medium capitalize break-words text-foreground">
              {worldState.mood || 'Unset'}
            </p>
          </div>
        </div>

        {/* Location */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            <MapPin size={16} className="text-rose-400" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Location</p>
            <p className="text-sm font-medium break-words text-foreground">
              {worldState.currentLocation || 'Unset'}
            </p>
          </div>
        </div>

        {/* Area */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            <MapPin size={16} className="text-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Area</p>
            <p className="text-sm font-medium break-words text-foreground">
              {worldState.currentArea || 'Unset'}
            </p>
          </div>
        </div>

        {/* Scene */}
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-muted">
            <Theater size={16} className="text-success" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Scene</p>
            <p className="text-sm font-medium break-words text-foreground">
              {worldState.currentScene || 'Unset'}
            </p>
          </div>
        </div>
      </div>
    </Card>
  );
}
