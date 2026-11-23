import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { cn } from '@/lib/utils';
import {
  useWorldMaps,
  useEntityPositions,
  useMapItemPositions,
  useMapEntityMoved,
  useMapItemDiscovered,
  useLocationDiscovered,
  useCulturalRegions,
} from './hooks/useMapData';
import { useTerrainData, useBiomeGrid, useMapRoads } from './hooks/useTerrainData';
import { useMapControls2D } from './hooks/useMapControls2D';
import { LOCATION_COLORS, CULTURE_COLORS, ROAD_COLORS, BIOME_GRADIENT, BIOME_GRADIENT_FALLBACK, WATER_BIOME_IDS } from './constants';
import { coordToCanvas, canvasToCoord, scaleToWorldSize } from './utils';
import type {
  MapLocation,
  MapCharacterPosition,
  MapItemPosition,
  WorldMap as WorldMapType,
  CulturalRegion,
  RoadSegment,
} from '@/graphql/types';
import { Crosshair, Maximize2, Loader2, ZoomIn, ZoomOut, Grid3X3 } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { drawLocationIcon } from './markers/locationIcons2D';
import LocationInfoPanel from './overlays/LocationInfoPanel';

/* ──────────────────────────── constants ──────────────────────────── */

const CANVAS_SIZE = 800;

const ROAD_WIDTHS: Record<string, number> = { main: 2.5, trail: 1.5, path: 1 };

/* ────────────────────── pure draw function ────────────────────── */

interface DrawData {
  readonly coordRange: number;
  readonly locations: MapLocation[];
  readonly entityPositions: MapCharacterPosition[];
  readonly itemPositions: MapItemPosition[];
  readonly roads: RoadSegment[];
  readonly culturalRegions: CulturalRegion[];
  readonly biomeOverlay: HTMLCanvasElement | null;
  readonly empireOverlay: HTMLCanvasElement | null;
  readonly showEmpires: boolean;
  readonly selectedLocationId: string | null;
  readonly userId: string | undefined;
  readonly skipLabels: boolean;
  readonly symbolScale: number;
  readonly showGrid: boolean;
}

/** Renders the full 2D map onto a canvas context. Pure — no React deps. */
function drawMap(ctx: CanvasRenderingContext2D, d: DrawData): void {
  const w = CANVAS_SIZE;
  const h = CANVAS_SIZE;

  // Background: biome terrain or solid fallback
  if (d.biomeOverlay) {
    ctx.drawImage(d.biomeOverlay, 0, 0);
    // Slight darken overlay so labels/dots remain readable
    ctx.fillStyle = 'rgba(24, 24, 27, 0.08)';
    ctx.fillRect(0, 0, w, h);
  } else {
    ctx.fillStyle = '#18181b';
    ctx.fillRect(0, 0, w, h);
  }

  // Optional survey grid stays quiet over the terrain.
  if (d.showGrid) {
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.12)';
    ctx.lineWidth = 0.5;
    const gridDivisions = 20;
    const gridStep = w / gridDivisions;
    for (let i = 0; i <= gridDivisions; i++) {
      const pos = i * gridStep;
      ctx.beginPath(); ctx.moveTo(pos, 0); ctx.lineTo(pos, h); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, pos); ctx.lineTo(w, pos); ctx.stroke();
    }

    // Origin axes
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.24)';
    ctx.lineWidth = 1;
    const ox = coordToCanvas(0, w, d.coordRange);
    const oy = coordToCanvas(0, h, d.coordRange);
    ctx.beginPath(); ctx.moveTo(ox, 0); ctx.lineTo(ox, h); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(0, oy); ctx.lineTo(w, oy); ctx.stroke();

  }

  // Empire overlay
  if (d.showEmpires && d.empireOverlay) {
    ctx.globalAlpha = 0.25;
    ctx.drawImage(d.empireOverlay, 0, 0);
    ctx.globalAlpha = 1;
    ctx.font = 'bold 14px sans-serif';
    ctx.textAlign = 'center';
    for (const region of d.culturalRegions) {
      const lx = coordToCanvas(region.worldX, w, d.coordRange);
      const ly = coordToCanvas(region.worldY, h, d.coordRange);
      const labelColor = CULTURE_COLORS[region.culture] || '#9ca3af';
      ctx.globalAlpha = 0.9;
      const metrics = ctx.measureText(region.name);
      const padH = 5;
      const padV = 3;
      ctx.fillStyle = 'rgba(0, 0, 0, 0.55)';
      ctx.fillRect(lx - metrics.width / 2 - padH, ly - 11 - padV, metrics.width + padH * 2, 14 + padV * 2);
      ctx.fillStyle = labelColor;
      ctx.fillText(region.name, lx, ly);
    }
    ctx.globalAlpha = 1;
  }

  // Roads
  ctx.save();
  ctx.globalAlpha = 0.6;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  for (const road of d.roads) {
    if (!road.waypoints || road.waypoints.length < 2) continue;
    ctx.strokeStyle = ROAD_COLORS[road.roadType] || ROAD_COLORS.path;
    ctx.lineWidth = (ROAD_WIDTHS[road.roadType] || 1) * d.symbolScale;
    ctx.beginPath();
    const [fx, fy] = road.waypoints[0];
    ctx.moveTo(coordToCanvas(fx, w, d.coordRange), coordToCanvas(fy, h, d.coordRange));
    for (let i = 1; i < road.waypoints.length; i++) {
      const [rx, ry] = road.waypoints[i];
      ctx.lineTo(coordToCanvas(rx, w, d.coordRange), coordToCanvas(ry, h, d.coordRange));
    }
    ctx.stroke();
  }
  ctx.restore();

  // Locations — single-pass with type-specific icons + inline labels
  const unit = d.symbolScale;
  const iconSize = 16 * unit;
  const undiscR = 4 * unit;
  const labelFont = `${12 * unit}px sans-serif`;
  const labelOffsetY = iconSize * 0.5 + 15 * unit;
  const occupied: { x: number; y: number; w: number; h: number }[] = [];
  const drawLabel = (text: string, x: number, y: number, color: string, priority = false) => {
    const width = ctx.measureText(text).width;
    x = Math.max(width / 2 + 4 * unit, Math.min(w - width / 2 - 4 * unit, x));
    y = Math.min(h - 4 * unit, y);
    const box = { x: x - width / 2 - 3 * unit, y: y - 12 * unit, w: width + 6 * unit, h: 16 * unit };
    if (!priority && occupied.some(b => box.x < b.x + b.w && box.x + box.w > b.x && box.y < b.y + b.h && box.y + box.h > b.y)) return;
    occupied.push(box);
    ctx.save();
    ctx.lineJoin = 'round';
    ctx.lineWidth = 3 * unit;
    ctx.strokeStyle = 'rgba(15, 20, 15, 0.95)';
    ctx.strokeText(text, x, y);
    ctx.fillStyle = color;
    ctx.fillText(text, x, y);
    ctx.restore();
  };

  ctx.font = labelFont;
  ctx.textAlign = 'center';

  const sortedLocations = [...d.locations].sort((a, b) => Number(b.locationId === d.selectedLocationId) - Number(a.locationId === d.selectedLocationId));
  for (const loc of sortedLocations) {
    const cx = coordToCanvas(loc.x, w, d.coordRange);
    const cy = coordToCanvas(loc.y, h, d.coordRange);
    if (cx < -20 || cx > w + 20 || cy < -20 || cy > h + 20) continue;

    const color = LOCATION_COLORS[loc.locationType ?? 'default'] ?? '#facc15';
    const selected = d.selectedLocationId === loc.locationId;

    if (!loc.isDiscovered) {
      ctx.globalAlpha = 0.25;
      ctx.fillStyle = '#52525b';
      ctx.beginPath(); ctx.arc(cx, cy, undiscR, 0, Math.PI * 2); ctx.fill();
      if (selected) {
        ctx.globalAlpha = 0.6;
        ctx.strokeStyle = '#9ca3af';
        ctx.lineWidth = 2 * unit;
        ctx.beginPath(); ctx.arc(cx, cy, undiscR + 4 * unit, 0, Math.PI * 2); ctx.stroke();
      }
      ctx.globalAlpha = 1;
      if (!d.skipLabels) {
        ctx.globalAlpha = 0.5;
        ctx.fillStyle = '#9ca3af';
        ctx.font = `bold ${12 * unit}px sans-serif`;
        ctx.fillText('?', cx, cy + 4 * unit);
        ctx.globalAlpha = 1;
        ctx.font = labelFont;
      }
      continue;
    }

    // Draw type-specific icon
    drawLocationIcon(ctx, loc.locationType ?? 'town', cx, cy, iconSize, color);

    if (selected) {
      ctx.strokeStyle = color;
      ctx.lineWidth = 2 * unit;
      ctx.beginPath(); ctx.arc(cx, cy, iconSize * 0.5 + 4 * unit, 0, Math.PI * 2); ctx.stroke();
    }
    if (!d.skipLabels || selected) drawLabel(loc.name, cx, cy + labelOffsetY, '#fafafa', selected);
  }

  // Items
  for (const item of d.itemPositions) {
    if (!item.isDiscovered) continue;
    const ix = coordToCanvas(item.x, w, d.coordRange);
    const iy = coordToCanvas(item.y, h, d.coordRange);
    ctx.fillStyle = '#fbbf24';
    ctx.save();
    ctx.translate(ix, iy);
    ctx.rotate(Math.PI / 4);
    ctx.fillRect(-5 * unit, -5 * unit, 10 * unit, 10 * unit);
    ctx.restore();
    if (!d.skipLabels) {
      ctx.fillStyle = '#fbbf24';
      ctx.font = `${11 * unit}px sans-serif`;
      ctx.textAlign = 'center';
      drawLabel(item.itemName, ix, iy + 18 * unit, '#fbbf24');
    }
  }

  // Entities
  for (const pos of d.entityPositions) {
    const ex = coordToCanvas(pos.x ?? 0, w, d.coordRange);
    const ey = coordToCanvas(pos.y ?? 0, h, d.coordRange);
    const isMe = !!d.userId && pos.userId === d.userId;

    if (isMe) {
      ctx.fillStyle = '#4ade80';
      ctx.beginPath(); ctx.arc(ex, ey, 7 * unit, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 1.5 * unit;
      ctx.beginPath(); ctx.arc(ex, ey, 7 * unit, 0, Math.PI * 2); ctx.stroke();
      if (!d.skipLabels) {
        ctx.fillStyle = '#4ade80';
        ctx.font = `bold ${11 * unit}px sans-serif`;
        ctx.textAlign = 'center';
        drawLabel('You', ex, ey - 14 * unit, '#4ade80', true);
      }
    } else {
      ctx.fillStyle = '#60a5fa';
      ctx.beginPath();
      ctx.moveTo(ex, ey - 8 * unit);
      ctx.lineTo(ex - 6 * unit, ey + 5 * unit);
      ctx.lineTo(ex + 6 * unit, ey + 5 * unit);
      ctx.closePath();
      ctx.fill();
      if (!d.skipLabels) {
        ctx.fillStyle = '#60a5fa';
        ctx.font = `${11 * unit}px sans-serif`;
        ctx.textAlign = 'center';
        drawLabel(pos.characterId ? 'Character' : 'Player', ex, ey - 14 * unit, '#60a5fa');
      }
    }
  }
}

/* ────────────────────────── component ────────────────────────── */

interface WorldMap2DProps {
  mapId?: string;
  conversationId: string;
  userId?: string;
  selectedLocation?: MapLocation | null;
  onSelectLocation?: (loc: MapLocation | null) => void;
}

export default function WorldMap2D({
  mapId,
  conversationId,
  userId,
  selectedLocation: externalSelection,
  onSelectLocation,
}: WorldMap2DProps) {
  /* ── data hooks ── */
  const { maps, loading, refetch: refetchMaps } = useWorldMaps(conversationId);
  const effectiveMapId = mapId ?? maps?.[maps.length - 1]?.mapId;
  const map = maps.find((m: WorldMapType) => m.mapId === effectiveMapId);
  const locations = useMemo(() => map?.locations ?? [], [map?.locations]);
  const coordRange = scaleToWorldSize(map?.mapScale ?? 'region');
  const { entityPositions, refetch: refetchPositions } = useEntityPositions(effectiveMapId || '');
  const { itemPositions, refetch: refetchItems } = useMapItemPositions(effectiveMapId || '');
  const { roads } = useMapRoads(effectiveMapId || '');
  const { culturalRegions } = useCulturalRegions(effectiveMapId || '');
  const { terrain } = useTerrainData(effectiveMapId || '');
  // The biome grid is a separate field, so it is fetched only here.
  const { biomeGrid } = useBiomeGrid(effectiveMapId || '');

  /* ── subscriptions ── */
  const { latestPosition } = useMapEntityMoved(effectiveMapId || '');
  const { discoveredItem } = useMapItemDiscovered(conversationId);
  const { discoveredLocation } = useLocationDiscovered(conversationId);

  /* ── pan/zoom controls ── */
  const {
    containerRef, transformRef, canvasRef,
    zoomLevel, getZoom, hasDragged,
    handlePointerDown, handlePointerMove, handlePointerUp,
    handleKeyDown: controlsKeyDown,
    resetView, centerOnCanvas, zoomIn, zoomOut,
  } = useMapControls2D(CANVAS_SIZE, !loading || locations.length > 0);

  /* ── selection state (controlled/uncontrolled) ── */
  const [internalSelection, setInternalSelection] = useState<MapLocation | null>(null);
  const selectedLocation = externalSelection !== undefined ? externalSelection : internalSelection;
  const setSelectedLocation = onSelectLocation ?? setInternalSelection;

  const [showEmpires, setShowEmpires] = useState(false);
  const [showGrid, setShowGrid] = useState(false);
  const locationSelectId = useId();

  /* ── subscription side-effects ── */
  useEffect(() => {
    if (discoveredLocation) {
      refetchMaps();
      toast.success(`Discovered: ${discoveredLocation.name}`);
    }
  }, [discoveredLocation, refetchMaps]);

  useEffect(() => { if (latestPosition) refetchPositions(); }, [latestPosition, refetchPositions]);
  useEffect(() => { if (discoveredItem) refetchItems(); }, [discoveredItem, refetchItems]);

  // Re-sync selection with fresh data after refetch (compare by value, not reference)
  useEffect(() => {
    if (!selectedLocation || !map) return;
    const fresh = locations.find(l => l.locationId === selectedLocation.locationId);
    if (!fresh) {
      setSelectedLocation(null);
    } else if (fresh !== selectedLocation) {
      setSelectedLocation(fresh);
    }
  }, [locations, selectedLocation, setSelectedLocation, map]);

  /* ── biome overlay (gradient colors + hillshade) ── */
  const biomeOverlayImage = useMemo(() => {
    if (!terrain || !biomeGrid) return null;
    const { width, height, heightmap, waterLevel: wl } = terrain;
    const offscreen = document.createElement('canvas');
    offscreen.width = CANVAS_SIZE;
    offscreen.height = CANVAS_SIZE;
    const offCtx = offscreen.getContext('2d');
    if (!offCtx) return null;

    // Pre-compute hillshade map from heightmap (directional lighting from NW)
    const shadeMap = new Float32Array(width * height);
    const SHADE_AMPLIFY = 14;
    for (let gy = 1; gy < height - 1; gy++) {
      for (let gx = 1; gx < width - 1; gx++) {
        const idx = gy * width + gx;
        const dzdx = (heightmap[idx + 1] - heightmap[idx - 1]) * 0.5;
        const dzdy = (heightmap[idx + width] - heightmap[idx - width]) * 0.5;
        // NW light: surfaces facing upper-left are lit
        const lightDot = (-dzdx - dzdy) * 0.707;
        shadeMap[idx] = Math.max(0.55, Math.min(1.2, 0.85 + lightDot * SHADE_AMPLIFY));
      }
    }
    // Edge cells: neutral shade
    for (let gx = 0; gx < width; gx++) {
      shadeMap[gx] = 0.85;
      shadeMap[(height - 1) * width + gx] = 0.85;
    }
    for (let gy = 0; gy < height; gy++) {
      shadeMap[gy * width] = 0.85;
      shadeMap[gy * width + width - 1] = 0.85;
    }

    const imgData = offCtx.createImageData(CANVAS_SIZE, CANVAS_SIZE);
    for (let py = 0; py < CANVAS_SIZE; py++) {
      for (let px = 0; px < CANVAS_SIZE; px++) {
        const gx = Math.floor((px / CANVAS_SIZE) * width);
        const gy = Math.floor((py / CANVAS_SIZE) * height);
        const gridIdx = gy * width + gx;
        const biomeId = biomeGrid[gridIdx];
        const h = heightmap[gridIdx];
        const grad = BIOME_GRADIENT[biomeId] || BIOME_GRADIENT_FALLBACK;

        // Normalize height within biome's elevation range
        const isWater = WATER_BIOME_IDS.has(biomeId);
        const t = Math.max(0, Math.min(1,
          isWater
            ? (wl > 0 ? h / wl : 0)
            : (wl < 1 ? (h - wl) / (1 - wl) : 0)
        ));

        // Interpolate gradient color
        const r = grad.dark[0] + (grad.light[0] - grad.dark[0]) * t;
        const g = grad.dark[1] + (grad.light[1] - grad.dark[1]) * t;
        const b = grad.dark[2] + (grad.light[2] - grad.dark[2]) * t;

        // Apply hillshade (skip for water — keep it smooth)
        const shade = isWater ? 1.0 : shadeMap[gridIdx];

        const i = (py * CANVAS_SIZE + px) * 4;
        imgData.data[i] = Math.round(r * shade);
        imgData.data[i + 1] = Math.round(g * shade);
        imgData.data[i + 2] = Math.round(b * shade);
        imgData.data[i + 3] = 255;
      }
    }
    offCtx.putImageData(imgData, 0, 0);
    return offscreen;
  }, [terrain, biomeGrid]);

  /* ── empire overlay (cached offscreen canvas) ── */
  const empireOverlayImage = useMemo(() => {
    if (culturalRegions.length === 0) return null;
    const blockSize = 4;
    const offscreen = document.createElement('canvas');
    offscreen.width = CANVAS_SIZE;
    offscreen.height = CANVAS_SIZE;
    const offCtx = offscreen.getContext('2d');
    if (!offCtx) return null;
    for (let by = 0; by < CANVAS_SIZE; by += blockSize) {
      for (let bx = 0; bx < CANVAS_SIZE; bx += blockSize) {
        const wx = canvasToCoord(bx + blockSize / 2, CANVAS_SIZE, coordRange);
        const wy = canvasToCoord(by + blockSize / 2, CANVAS_SIZE, coordRange);
        let nearestIdx = 0;
        let nearestDist = Infinity;
        for (let r = 0; r < culturalRegions.length; r++) {
          const dx = wx - culturalRegions[r].worldX;
          const dy = wy - culturalRegions[r].worldY;
          const dist = dx * dx + dy * dy;
          if (dist < nearestDist) { nearestDist = dist; nearestIdx = r; }
        }
        offCtx.fillStyle = CULTURE_COLORS[culturalRegions[nearestIdx].culture] || '#9ca3af';
        offCtx.fillRect(bx, by, blockSize, blockSize);
      }
    }
    return offscreen;
  }, [culturalRegions, coordRange]);

  /* ── ref-based draw ── */
  const drawDataRef = useRef<DrawData>({
    coordRange: 200, locations: [], entityPositions: [], itemPositions: [], roads: [],
    culturalRegions: [], biomeOverlay: null, empireOverlay: null, showEmpires: false,
    selectedLocationId: null, userId: undefined, skipLabels: false, symbolScale: 1, showGrid: false,
  });

  drawDataRef.current = {
    coordRange,
    locations,
    entityPositions: entityPositions as MapCharacterPosition[],
    itemPositions: itemPositions as MapItemPosition[],
    roads,
    culturalRegions,
    biomeOverlay: biomeOverlayImage,
    empireOverlay: empireOverlayImage,
    showEmpires,
    selectedLocationId: selectedLocation?.locationId ?? null,
    userId,
    skipLabels: zoomLevel < 0.5,
    symbolScale: 1,
    showGrid,
  };

  const redraw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    const zoom = getZoom();
    const renderScale = Math.min(dpr * Math.max(1, Math.ceil(zoom)), 4);
    const renderSize = Math.round(CANVAS_SIZE * renderScale);

    if (canvas.width !== renderSize || canvas.height !== renderSize) {
      canvas.width = renderSize;
      canvas.height = renderSize;
    }

    ctx.setTransform(renderScale, 0, 0, renderScale, 0, 0);
    const displayWidth = canvas.getBoundingClientRect().width;
    const symbolScale = displayWidth > 0 ? CANVAS_SIZE / displayWidth : 1;
    drawMap(ctx, { ...drawDataRef.current, symbolScale, skipLabels: displayWidth < 260 });
  }, [canvasRef, getZoom]);

  useEffect(() => { redraw(); },

    [redraw, locations, entityPositions, itemPositions, roads, selectedLocation, showEmpires, biomeOverlayImage, empireOverlayImage, zoomLevel, coordRange, userId, showGrid],
  );

  useEffect(() => {
    if (!containerRef.current) return;
    const observer = new ResizeObserver(redraw);
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [containerRef, redraw, loading]);

  /* ── click handling (instant — no setTimeout delay) ── */
  const findNearestLocation = useCallback((clientX: number, clientY: number): MapLocation | null => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    const canvasX = (clientX - rect.left) * (CANVAS_SIZE / rect.width);
    const canvasY = (clientY - rect.top) * (CANVAS_SIZE / rect.height);
    const coordX = canvasToCoord(canvasX, CANVAS_SIZE, coordRange);
    const coordY = canvasToCoord(canvasY, CANVAS_SIZE, coordRange);
    const threshold = (coordRange / rect.width) * 20;
    let closest: MapLocation | null = null;
    let closestDist = Infinity;
    for (const loc of drawDataRef.current.locations) {
      const dx = loc.x - coordX;
      const dy = loc.y - coordY;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist < threshold && dist < closestDist) { closest = loc; closestDist = dist; }
    }
    return closest;
  }, [canvasRef, coordRange]);

  const handleCanvasClick = useCallback((e: React.MouseEvent) => {
    if (hasDragged()) return;
    setSelectedLocation(findNearestLocation(e.clientX, e.clientY));
  }, [hasDragged, findNearestLocation, setSelectedLocation]);

  const handleDoubleClick = useCallback((e: React.MouseEvent) => {
    const loc = findNearestLocation(e.clientX, e.clientY);
    if (loc) {
      setSelectedLocation(loc);
      centerOnCanvas(coordToCanvas(loc.x, CANVAS_SIZE, coordRange), coordToCanvas(loc.y, CANVAS_SIZE, coordRange));
    }
  }, [findNearestLocation, setSelectedLocation, centerOnCanvas, coordRange]);

  /** F key → focus on player position */
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key.toLowerCase() === 'f') {
      const up = drawDataRef.current.entityPositions.find(p => !!userId && p.userId === userId);
      if (up) centerOnCanvas(coordToCanvas(up.x ?? 0, CANVAS_SIZE, coordRange), coordToCanvas(up.y ?? 0, CANVAS_SIZE, coordRange));
      e.preventDefault();
      return;
    }
    controlsKeyDown(e);
  }, [userId, centerOnCanvas, controlsKeyDown, coordRange]);

  /* ── derived helpers ── */
  const userPos = useMemo(
    () => (entityPositions as MapCharacterPosition[]).find(p => !!userId && p.userId === userId),
    [entityPositions, userId],
  );
  const handleCenterOnMe = useCallback(() => {
    if (!userPos) return;
    centerOnCanvas(coordToCanvas(userPos.x ?? 0, CANVAS_SIZE, coordRange), coordToCanvas(userPos.y ?? 0, CANVAS_SIZE, coordRange));
  }, [userPos, centerOnCanvas, coordRange]);

  /* ── loading state ── */
  if (loading && locations.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  const controlClass = 'inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-40 disabled:pointer-events-none';

  /* ── render ── */
  return (
    <div className="relative flex h-full w-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-2 border-b border-border bg-card p-2">
        <div className="flex items-center gap-1" role="group" aria-label="Map navigation">
          <button type="button" onClick={handleCenterOnMe} disabled={!userPos} aria-label="Center on me" title="Center on me (F)" className={controlClass}>
            <Crosshair size={18} />
          </button>
          <button type="button" onClick={resetView} aria-label="Fit all" title="Fit all (Home)" className={controlClass}>
            <Maximize2 size={18} />
          </button>
          <button type="button" onClick={zoomOut} aria-label="Zoom out" title="Zoom out (−)" className={controlClass}><ZoomOut size={18} /></button>
          <output aria-label="Map zoom" className="w-12 text-center text-xs tabular-nums text-muted-foreground">{Math.round(zoomLevel * 100)}%</output>
          <button type="button" onClick={zoomIn} aria-label="Zoom in" title="Zoom in (+)" className={controlClass}><ZoomIn size={18} /></button>
          <button type="button" onClick={() => setShowGrid(prev => !prev)} aria-label="Show grid" aria-pressed={showGrid} title="Toggle grid" className={cn(controlClass, showGrid && 'bg-accent text-foreground')}><Grid3X3 size={18} /></button>
        </div>
        {culturalRegions.length > 0 && (
          <button type="button" aria-pressed={showEmpires} onClick={() => setShowEmpires(prev => !prev)} className={cn(controlClass, 'w-auto px-3', showEmpires && 'bg-accent text-foreground')}>Empires</button>
        )}
        <div className="min-w-40 flex-1 sm:ml-auto sm:max-w-72">
          <label className="sr-only" htmlFor={locationSelectId}>Select map location</label>
          <select id={locationSelectId} className="h-11 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
            value={selectedLocation?.locationId ?? ''}
            onChange={event => {
              const location = locations.find(loc => loc.locationId === event.target.value) ?? null;
              setSelectedLocation(location);
              if (location) centerOnCanvas(coordToCanvas(location.x, CANVAS_SIZE, coordRange), coordToCanvas(location.y, CANVAS_SIZE, coordRange));
            }}>
            <option value="">Find a location…</option>
            {locations.map((location, index) => <option key={location.locationId} value={location.locationId}>{location.isDiscovered ? location.name : `Unknown location ${index + 1}`}</option>)}
          </select>
        </div>
      </div>

      {/* Canvas with pan/zoom */}
      <div
        ref={containerRef}
        tabIndex={0}
        aria-label="World map. Drag to pan, use arrow keys to move, plus and minus to zoom, Home to fit, and F to find your position."
        className="min-h-0 flex-1 w-full overflow-hidden bg-muted focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
        style={{ cursor: 'grab', touchAction: 'none' }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onLostPointerCapture={handlePointerUp}
        onKeyDown={handleKeyDown}
        onClick={handleCanvasClick}
        onDoubleClick={handleDoubleClick}
      >
        <div ref={transformRef} style={{ transform: 'translate(0px, 0px) scale(1)', transformOrigin: '0 0' }}>
          <canvas
            ref={canvasRef}
            width={CANVAS_SIZE}
            height={CANVAS_SIZE}
            style={{ width: '100%', height: 'auto', display: 'block' }}
          />
        </div>
      </div>

      {/* ── Location info panel + journey overlay ── */}
      <LocationInfoPanel
        selectedLocation={selectedLocation}
        onSelectLocation={setSelectedLocation}
        mapId={effectiveMapId || ''}
        conversationId={conversationId}
        userId={userId}
        entityPositions={entityPositions as MapCharacterPosition[]}
        onDataChanged={() => { void refetchPositions(); void refetchMaps(); }}
      />
    </div>
  );
}
