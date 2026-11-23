import { useEffect, useMemo, useRef } from 'react';
import { Compass, DoorOpen, MapPin } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useLocalMap } from './hooks/useMapData';
import { useWorldStateChanged } from '@/hooks/useRoleplay';
import type { LocalArea } from '@/graphql/types';

interface LocalMapPanelProps {
  conversationId: string;
  className?: string;
  /** Narrator (GM) view: the whole layout is known, so no fog of war. */
  narratorMode?: boolean;
  expanded?: boolean;
}

/**
 * Compass bearing from one area to another in the layout's screen axes:
 * +x is east, +y is south (the SVG renders y downward). Mirrors the backend
 * StageService.compassDirection so the map and the STAGE block agree.
 */
function compassDirection(fromX: number, fromY: number, toX: number, toY: number): string {
  const dx = toX - fromX;
  const dy = toY - fromY;
  if (Math.abs(dx) < 1e-3 && Math.abs(dy) < 1e-3) return 'straight ahead';
  const angle = (Math.atan2(dy, dx) * 180) / Math.PI;
  const names = ['east', 'southeast', 'south', 'southwest', 'west', 'northwest', 'north', 'northeast'];
  let index = Math.round(angle / 45) % 8;
  if (index < 0) index += 8;
  return names[index];
}

/**
 * The interior layout of the current world-map node. With fog of war, only
 * areas the party has actually been in are drawn; in narrator mode the whole
 * layout is revealed, since the GM knows the place. The current room is
 * highlighted, gateways are dashed, and only recorded connections are drawn.
 * This is the visual counterpart of the STAGE block given to the model —
 * the narrative drives the position; this panel shows the result.
 */
export default function LocalMapPanel({ conversationId, className, narratorMode = false, expanded = false }: LocalMapPanelProps) {
  const { localMap, refetch, loading, error } = useLocalMap(conversationId);
  const { worldState } = useWorldStateChanged(conversationId);
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;

  useEffect(() => {
    if (worldState) refetchRef.current();
  }, [worldState]);

  const visited = useMemo(
    () => new Set(localMap?.visitedAreaIds ?? []),
    [localMap]
  );

  const visibleAreas = useMemo(() => {
    if (!localMap) return [];
    // The narrator knows the whole place; the party only its trail.
    if (narratorMode) return localMap.areas;
    return localMap.areas.filter(
      (area) => area.localAreaId === localMap.currentAreaId || visited.has(area.localAreaId)
    );
  }, [localMap, visited, narratorMode]);

  const visibleIds = useMemo(
    () => new Set(visibleAreas.map((area) => area.localAreaId)),
    [visibleAreas]
  );

  const current = useMemo(
    () => localMap?.areas?.find((area) => area.localAreaId === localMap.currentAreaId) ?? null,
    [localMap]
  );

  // Explored, not visible: the counter must keep telling the truth in narrator
  // mode, where every area is drawn regardless of visit history.
  const exploredCount = useMemo(
    () =>
      (localMap?.areas ?? []).filter(
        (area) => area.localAreaId === localMap?.currentAreaId || visited.has(area.localAreaId)
      ).length,
    [localMap, visited]
  );

  const exits = useMemo(() => {
    if (!localMap?.currentAreaId || !current) return [];
    const byId = new Map(localMap.areas.map((area) => [area.localAreaId, area]));
    const found: Array<{ area: LocalArea; label?: string; direction: string }> = [];
    for (const connection of localMap.connections) {
      const otherId =
        connection.fromAreaId === localMap.currentAreaId
          ? connection.toAreaId
          : connection.toAreaId === localMap.currentAreaId
            ? connection.fromAreaId
            : null;
      if (!otherId) continue;
      const area = byId.get(otherId);
      if (!area) continue;
      found.push({
        area,
        label: connection.label ?? undefined,
        direction: compassDirection(current.x, current.y, area.x, area.y),
      });
    }
    found.sort((a, b) => a.area.name.localeCompare(b.area.name));
    return found;
  }, [localMap, current]);

  if (!localMap) {
    if (!expanded) return null;
    return <div className="flex h-full min-h-64 flex-col items-center justify-center gap-3 p-6 text-center">
      <MapPin size={24} className="text-muted-foreground" aria-hidden="true" />
      <p role={error ? 'alert' : 'status'} className="text-sm text-muted-foreground">
        {error ? 'Could not load the local map.' : loading ? 'Loading your location…' : 'A local map is available when the party is inside a location.'}
      </p>
      {error && <button type="button" onClick={() => void refetch()} className="min-h-11 rounded-md border border-border px-4 text-sm">Try again</button>}
    </div>;
  }

  return (
    <div className={cn(expanded ? 'mx-auto grid max-w-7xl gap-5 p-4 sm:p-6 lg:grid-cols-[minmax(0,1fr)_18rem]' : 'space-y-2', className)}>
      {!expanded && <div className="flex items-center gap-1.5">
        <MapPin size={12} className="text-rose-400" />
        <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Local Map
        </h4>
      </div>}

      <div className="overflow-hidden rounded-lg border border-border/50 bg-card/50">
        <div className="border-b border-border/50 px-3 py-2">
          <p className="truncate text-sm font-medium text-foreground">{localMap.locationName}</p>
          <p className={cn(expanded ? 'text-sm' : 'text-[11px]', 'text-muted-foreground')}>
            {current ? current.name : localMap.currentAreaName || 'Unknown area'}
            <span className="text-muted-foreground/70">
              {' '}· {exploredCount} of {localMap.areas.length} areas explored
            </span>
          </p>
        </div>

        <svg
          viewBox="-6 -6 112 112"
          className={expanded ? 'aspect-square max-h-[min(65vh,42rem)] w-full' : 'h-44 w-full'}
          role="img"
          aria-label={`Layout of ${localMap.locationName}`}
        >
          {localMap.connections.map((connection) => {
            const from = localMap.areas.find((area) => area.localAreaId === connection.fromAreaId);
            const to = localMap.areas.find((area) => area.localAreaId === connection.toAreaId);
            // Fog of war: a passage is only drawn once both ends have been seen.
            if (!from || !to) return null;
            if (!visibleIds.has(from.localAreaId) || !visibleIds.has(to.localAreaId)) return null;
            return (
              <line
                key={connection.connectionId}
                x1={from.x}
                y1={from.y}
                x2={to.x}
                y2={to.y}
                stroke="hsl(var(--border))"
                strokeWidth={1}
                vectorEffect="non-scaling-stroke"
              >
                <title>{connection.label || 'passage'}</title>
              </line>
            );
          })}

          {visibleAreas.map((area) => {
            const isCurrent = area.localAreaId === localMap.currentAreaId;
            return (
              <g key={area.localAreaId}>
                <circle
                  cx={area.x}
                  cy={area.y}
                  r={isCurrent ? 4.6 : 3.6}
                  fill={
                    isCurrent
                      ? 'hsl(var(--primary))'
                      : area.isGateway
                        ? 'transparent'
                        : 'hsl(var(--muted))'
                  }
                  stroke={isCurrent ? 'hsl(var(--primary))' : 'hsl(var(--muted-foreground))'}
                  strokeWidth={isCurrent ? 1.2 : 1}
                  strokeDasharray={!isCurrent && area.isGateway ? '2 1.5' : undefined}
                  vectorEffect="non-scaling-stroke"
                >
                  <title>{area.description || area.name}</title>
                </circle>
                <text
                  x={area.x}
                  y={area.y + 7.2}
                  textAnchor="middle"
                  fill={isCurrent ? 'hsl(var(--foreground))' : 'hsl(var(--muted-foreground))'}
                  fontWeight={isCurrent ? 600 : 400}
                  className={cn('select-none', expanded ? 'text-[4.2px]' : 'text-[3.4px]')}
                >
                  {area.name}
                </text>
              </g>
            );
          })}
        </svg>

        {current?.description && (
          <p className={cn('border-t border-border/50 px-3 py-2 leading-relaxed text-muted-foreground', expanded ? 'text-sm' : 'text-[11px]')}>
            {current.description}
          </p>
        )}
      </div>

      <div className={cn(expanded ? 'self-start py-2 lg:sticky lg:top-4' : 'rounded-lg border border-border/50 bg-card/50 p-3')}>
        <div className={cn('mb-2 flex items-center gap-1.5 font-medium text-muted-foreground', expanded ? 'text-sm' : 'text-[11px] uppercase tracking-wide')}>
          <DoorOpen size={11} />
          Exits
        </div>
        {exits.length === 0 ? (
          <p className="text-[11px] text-muted-foreground">No recorded exits from this area.</p>
        ) : (
          <ul className={expanded ? 'space-y-3' : 'space-y-1'}>
            {exits.map(({ area, label, direction }) => {
              const explored = visited.has(area.localAreaId);
              return (
                <li key={area.localAreaId} className={cn('flex items-start justify-between gap-2',expanded ? 'text-sm' : 'text-[11px]')}>
                  <span className="min-w-0 break-words text-foreground">
                    {area.name}
                    <span className="text-muted-foreground"> ({direction})</span>
                  </span>
                  <span className="shrink-0 text-muted-foreground">
                    {explored ? label ?? 'explored' : 'unexplored'}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
        {current?.isGateway && (
          <p className="mt-2 flex items-center gap-1 text-[11px] text-warning">
            <Compass size={11} />
            This area is a gateway to the wider map.
          </p>
        )}
      </div>
    </div>
  );
}
