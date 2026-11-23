import { useState, useMemo, useCallback, useEffect, useRef, Suspense } from 'react';
import { Canvas, useThree } from '@react-three/fiber';
import { Stars } from '@react-three/drei';
import * as THREE from 'three';
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib';

import {
  useEntityPositions,
  useMapItemPositions,
  useMapEntityMoved,
  useMapItemDiscovered,
  useLocationDiscovered,
  useCulturalRegions,
} from './hooks/useMapData';

import { useTerrainData, useMapRoads } from './hooks/useTerrainData';
import { CAMERA_DEFAULTS, CAMERA_FALLBACK, getMarkerScale } from './constants';
import { toThreePos, scaleToWorldSize, MAX_ELEVATION_FACTOR } from './utils';

import RayMarchedTerrain from './terrain/RayMarchedTerrain';
import LocationMarkersInstanced from './markers/LocationMarkersInstanced';
import EntityToken from './markers/EntityToken';
import ItemMarker from './markers/ItemMarker';
import CameraController from './CameraController';
import MapControls3D from './MapControls3D';
import CulturalRegionOverlay3D from './overlays/CulturalRegionOverlay3D';
import LocationInfoPanel from './overlays/LocationInfoPanel';

import type {
  MapLocation,
  MapCharacterPosition,
  MapItemPosition,
  WorldMap,
  CulturalRegion,
} from '@/graphql/types';

import { Crosshair, Maximize2 } from 'lucide-react';

interface WorldMap3DProps {
  map: WorldMap;
  conversationId: string;
  userId: string;
  selectedLocation?: MapLocation | null;
  onSelectLocation?: (loc: MapLocation | null) => void;
  /** Called when map data may have changed (journey complete, discovery, etc.) */
  onDataChanged?: () => void;
}

interface WorldMetrics {
  isGenerated: boolean;
  worldSize: number;
  maxElevation: number;
}

interface SceneContentProps {
  map: WorldMap;
  locations: MapLocation[];
  userId: string;
  conversationId: string;
  selectedLocation: MapLocation | null;
  setSelectedLocation: (loc: MapLocation | null) => void;
  cameraTarget: [number, number, number] | null;
  fitAll: boolean;
  onFocusComplete: () => void;
  onDoubleClickLocation: () => void;
  showEmpires: boolean;
  culturalRegions: CulturalRegion[];
  markerScale: number;
  worldMetrics: WorldMetrics;
  entityPositions: MapCharacterPosition[];
}

/* ========================================================= */
/* Scene Content                                              */
/* ========================================================= */

function SceneContent({
  map,
  locations,
  userId,
  conversationId,
  selectedLocation,
  setSelectedLocation,
  cameraTarget,
  fitAll,
  onFocusComplete,
  onDoubleClickLocation,
  showEmpires,
  culturalRegions,
  markerScale,
  worldMetrics,
  entityPositions,
}: SceneContentProps) {
  const { terrain } = useTerrainData(map.mapId);
  const { roads } = useMapRoads(map.mapId);

  const { itemPositions, refetch: refetchItems } = useMapItemPositions(map.mapId);

  const { discoveredItem } = useMapItemDiscovered(conversationId);

  const orbitRef = useRef<OrbitControlsImpl>(null);
  const { camera } = useThree();

  // Sync camera near/far/position on mount and when worldMetrics change.
  // Canvas camera prop only applies on initial creation — if the component
  // mounted before terrain data arrived, the camera config may be stale.
  const cameraSynced = useRef(false);
  const prevMapIdRef = useRef<string>(map.mapId);
  useEffect(() => {
    if (prevMapIdRef.current !== map.mapId) {
      cameraSynced.current = false;
      prevMapIdRef.current = map.mapId;
    }
  }, [map.mapId]);
  useEffect(() => {
    if (!cameraSynced.current) {
      const cam = camera as THREE.PerspectiveCamera;
      const near = Math.max(0.5, worldMetrics.worldSize * 0.002);
      const far = worldMetrics.worldSize * 20;
      cam.near = near;
      cam.far = far;
      cam.updateProjectionMatrix();

      // Ensure OrbitControls picks up the updated projection
      if (orbitRef.current) {
        orbitRef.current.update();
      }
      cameraSynced.current = true;
    }
  }, [camera, worldMetrics]);

  useEffect(() => {
    if (discoveredItem) {
      refetchItems();
    }
  }, [discoveredItem, refetchItems]);

  const showStars = map.mapScale === 'solar_system' || map.mapScale === 'galaxy';
  const resolvedMapScale = map.mapScale ?? 'region';

  const isGenerated = worldMetrics.isGenerated && terrain != null;

  return (
    <>
      <ambientLight intensity={0.3} />
      <directionalLight position={[80, 120, 60]} intensity={0.9} />
      <directionalLight position={[-40, 60, -30]} intensity={0.25} color="#b0c4ff" />

      <MapControls3D
        mapScale={resolvedMapScale}
        controlsRef={orbitRef}
        onDoubleClickLocation={onDoubleClickLocation}
      />

      <CameraController
        targetPosition={cameraTarget}
        fitAll={fitAll}
        mapScale={resolvedMapScale}
        orbitRef={orbitRef}
        onFocusComplete={onFocusComplete}
      />

      {showStars && <Stars radius={worldMetrics.worldSize * 10} depth={100} />}

      {isGenerated ? (
        <RayMarchedTerrain
          terrain={terrain}
          worldSize={worldMetrics.worldSize}
          culturalRegions={culturalRegions}
          showEmpires={showEmpires}
          roads={roads}
        />
      ) : (
        <gridHelper
          args={[worldMetrics.worldSize, worldMetrics.worldSize / 10, '#374151', '#1f2937']}
          position={[0, -0.01, 0]}
        />
      )}

      {!isGenerated && showEmpires && culturalRegions.length > 0 && (
        <CulturalRegionOverlay3D
          regions={culturalRegions}
          maxElevation={worldMetrics.maxElevation}
        />
      )}

      <LocationMarkersInstanced
        locations={locations}
        selectedLocation={selectedLocation}
        onSelectLocation={setSelectedLocation}
        maxElevation={worldMetrics.maxElevation}
        isGenerated={isGenerated}
        markerScale={markerScale}
      />

      {entityPositions.map((pos: MapCharacterPosition) => (
        <EntityToken
          key={pos.id}
          position={pos}
          isCurrentUser={pos.userId === userId}
          terrainElevation={isGenerated ? (pos.z ?? 0) * worldMetrics.maxElevation : undefined}
          markerScale={markerScale}
        />
      ))}

      {itemPositions.map((item: MapItemPosition) => (
        <ItemMarker
          key={item.id}
          item={item}
          terrainElevation={isGenerated ? (item.z ?? 0) * worldMetrics.maxElevation : undefined}
          markerScale={markerScale}
        />
      ))}
    </>
  );
}

/* ========================================================= */
/* Main Component                                             */
/* ========================================================= */

export default function WorldMap3D({
  map,
  conversationId,
  userId,
  selectedLocation: externalSelection,
  onSelectLocation,
  onDataChanged,
}: WorldMap3DProps) {
  const [internalSelection, setInternalSelection] = useState<MapLocation | null>(null);

  const rawSelectedLocation = externalSelection !== undefined ? externalSelection : internalSelection;

  const setSelectedLocation = onSelectLocation ?? setInternalSelection;

  const [cameraTarget, setCameraTarget] = useState<[number, number, number] | null>(null);

  const [fitAll, setFitAll] = useState(false);
  const [showEmpires, setShowEmpires] = useState(false);

  const { entityPositions: baseEntityPositions, refetch: refetchPositions } = useEntityPositions(map.mapId);
  const { culturalRegions } = useCulturalRegions(map.mapId);
  const { latestPosition } = useMapEntityMoved(map.mapId);
  const { discoveredLocation } = useLocationDiscovered(conversationId);

  // ─── Discovery overrides: apply subscription events directly ───
  // This avoids waiting for a full refetch round-trip to update location markers.
  const discoveryOverridesRef = useRef<Map<string, MapLocation>>(new Map());
  useEffect(() => {
    if (discoveredLocation && discoveredLocation.locationId) {
      discoveryOverridesRef.current.set(discoveredLocation.locationId, discoveredLocation);
      // Also trigger a full refetch for eventual consistency
      onDataChanged?.();
    }
  }, [discoveredLocation, onDataChanged]);
  // Clear overrides when base locations change (refetch completed)
  const baseLocations = map.locations;
  useEffect(() => {
    discoveryOverridesRef.current.clear();
  }, [baseLocations]);

  // Merge base locations with discovery subscription overrides
  const locations = useMemo(() => {
    const base = map.locations || [];
    // Include the current event directly (ref hasn't been updated during render yet)
    const overrides = new Map(discoveryOverridesRef.current);
    if (discoveredLocation && discoveredLocation.locationId) {
      overrides.set(discoveredLocation.locationId, discoveredLocation);
    }
    if (overrides.size === 0) return base;
    return base.map(loc => {
      const override = overrides.get(loc.locationId);
      return override ? { ...loc, isDiscovered: true, ...(override.name != null && { name: override.name }), ...(override.description != null && { description: override.description }), ...(override.icon != null && { icon: override.icon }), ...(override.locationType != null && { locationType: override.locationType }) } : loc;
    });
  }, [map.locations, discoveredLocation]);

  // Resolve selected location against merged locations so the info panel
  // updates immediately when a discovery event arrives (e.g. arrival at destination).
  const selectedLocation = useMemo(() => {
    if (!rawSelectedLocation) return null;
    return locations.find(l => l.locationId === rawSelectedLocation.locationId) ?? null;
  }, [rawSelectedLocation, locations]);

  // Track subscription-based position overrides for smooth animation.
  // The ref accumulates overrides from PREVIOUS subscription events;
  // the current latestPosition is merged directly in the useMemo
  // to avoid a one-render-behind lag (useEffect runs after render,
  // but useMemo runs during render).
  const moveOverridesRef = useRef<Map<string, MapCharacterPosition>>(new Map());
  useEffect(() => {
    if (latestPosition && latestPosition.id) {
      moveOverridesRef.current.set(latestPosition.id, latestPosition);
    }
  }, [latestPosition]);
  // Clear overrides when base query refreshes (it already includes latest positions)
  useEffect(() => {
    moveOverridesRef.current.clear();
  }, [baseEntityPositions]);

  // Merge base query positions with subscription overrides + current event
  const entityPositions = useMemo(() => {
    // Start with previously accumulated overrides from the ref
    const overrides = new Map(moveOverridesRef.current);
    // Always include the current subscription event (ref hasn't been updated yet during render)
    if (latestPosition && latestPosition.id) {
      overrides.set(latestPosition.id, latestPosition);
    }
    if (overrides.size === 0) return baseEntityPositions;
    const merged = baseEntityPositions.map(pos => {
      const override = overrides.get(pos.id);
      return override ? { ...pos, x: override.x, y: override.y, z: override.z, locationId: override.locationId } : pos;
    });
    const known = new Set(merged.map(pos => pos.id));
    return [...merged, ...Array.from(overrides.values()).filter(pos => !known.has(pos.id) && pos.mapId === map.mapId)];
  }, [baseEntityPositions, latestPosition, map.mapId]);

  /* ================= WORLD METRICS ================= */

  const worldMetrics = useMemo(() => {
    const isGenerated = map.mapType === 'generated';
    const worldSize = scaleToWorldSize(map.mapScale || 'region');

    return {
      isGenerated,
      worldSize,
      maxElevation: worldSize * MAX_ELEVATION_FACTOR,
    };
  }, [map.mapType, map.mapScale]);

  /** Camera start position sized for generated maps. */
  const generatedCameraPosition = useMemo((): [number, number, number] => {
    const scale = map.mapScale || 'region';
    switch (scale) {
      case 'room':         return [0, 15, 15];
      case 'building':     return [0, 30, 30];
      case 'city':         return [0, 80, 80];
      case 'continent':    return [0, 500, 500];
      case 'planet':       return [0, 2000, 2000];
      case 'solar_system': return [0, 10000, 10000];
      case 'galaxy':       return [0, 20000, 20000];
      default:             return [0, 200, 200]; // region
    }
  }, [map.mapScale]);

  /* ================= USER ENTITY ================= */

  const userEntity = useMemo(
    () => entityPositions.find((p: MapCharacterPosition) => p.userId === userId),
    [entityPositions, userId],
  );

  const toVisualPos = useMemo(() => {
    return (x: number, y: number, z: number) => {
      if (worldMetrics.isGenerated) {
        return [x, z * worldMetrics.maxElevation, y] as [number, number, number];
      }
      return toThreePos(x, y, z);
    };
  }, [worldMetrics]);

  const userPosition = useMemo(() => {
    if (!userEntity) return null;
    return toVisualPos(userEntity.x ?? 0, userEntity.y ?? 0, userEntity.z ?? 0);
  }, [userEntity, toVisualPos]);

  /* ================= CAMERA SETTINGS ================= */

  const near = Math.max(0.5, worldMetrics.worldSize * 0.002);
  const far = worldMetrics.worldSize * 20;

  const markerScale = useMemo(
    () => getMarkerScale(worldMetrics.worldSize, map.locations?.length || 100),
    [worldMetrics.worldSize, map.locations?.length],
  );

  /* ================= HANDLERS ================= */

  const handleCenterOnMe = useCallback(() => {
    setFitAll(false);
    if (userPosition) setCameraTarget([...userPosition]);
  }, [userPosition]);

  const handleFitAll = useCallback(() => {
    setFitAll(true);
    setCameraTarget([0, worldMetrics.isGenerated ? worldMetrics.maxElevation / 2 : 0, 0]);
  }, [worldMetrics]);

  const handleDoubleClickLocation = useCallback(() => {
    if (!selectedLocation) return;
    setFitAll(false);
    const pos = toVisualPos(selectedLocation.x, selectedLocation.y, selectedLocation.z);
    setCameraTarget(pos);
  }, [selectedLocation, toVisualPos]);

  /* ========================================================= */

  return (
    <div className="relative h-full w-full">
      <Canvas
        key={map.mapId}
        onCreated={({ gl }) => {
          gl.domElement.tabIndex = 0;
          gl.domElement.setAttribute('aria-label', '3D world map. Drag to pan, right drag to orbit, scroll to zoom, and use arrow keys to move.');
        }}
        aria-label="3D world map. Drag to pan, right drag to orbit, scroll to zoom, and use arrow keys to move."
        camera={{
          position: worldMetrics.isGenerated
            ? generatedCameraPosition
            : (CAMERA_DEFAULTS[map.mapScale || 'region'] ?? CAMERA_FALLBACK).position,
          fov: 55,
          near,
          far,
        }}
        dpr={[1, 2]}
        gl={{
          antialias: true,
          powerPreference: 'high-performance',
          logarithmicDepthBuffer: true,
        }}
        style={{
          background: worldMetrics.isGenerated ? '#000' : '#8ca6c7',
        }}
      >
        <Suspense fallback={null}>
          <SceneContent
            map={map}
            locations={locations}
            userId={userId}
            conversationId={conversationId}
            selectedLocation={selectedLocation}
            setSelectedLocation={setSelectedLocation}
            cameraTarget={cameraTarget}
            fitAll={fitAll}
            onFocusComplete={() => setCameraTarget(null)}
            onDoubleClickLocation={handleDoubleClickLocation}
            showEmpires={showEmpires}
            culturalRegions={culturalRegions}
            markerScale={markerScale}
            worldMetrics={worldMetrics}
            entityPositions={entityPositions}
          />
        </Suspense>
      </Canvas>

      {/* Controls */}
      <div className="absolute left-4 top-4 flex flex-col gap-2">
        <button
          onClick={handleCenterOnMe}
          disabled={!userPosition}
          className="inline-flex items-center gap-1.5 rounded-lg border border-border/60 bg-card/90 px-3 py-2 text-xs font-medium text-foreground shadow-sm backdrop-blur-sm transition-colors hover:bg-accent"
        >
          <Crosshair size={14} /> Center on me
        </button>

        <button
          onClick={handleFitAll}
          className="inline-flex items-center gap-1.5 rounded-lg border border-border/60 bg-card/90 px-3 py-2 text-xs font-medium text-foreground shadow-sm backdrop-blur-sm transition-colors hover:bg-accent"
        >
          <Maximize2 size={14} /> Fit all
        </button>

        {culturalRegions.length > 0 && (
          <button
            onClick={() => setShowEmpires((prev) => !prev)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-primary/50 bg-primary px-3 py-2 text-xs font-medium text-primary-foreground shadow-sm transition-colors hover:bg-primary-hover"
          >
            {showEmpires ? 'Hide Empires' : 'Show Empires'}
          </button>
        )}
      </div>

      <LocationInfoPanel
        selectedLocation={selectedLocation}
        onSelectLocation={setSelectedLocation}
        mapId={map.mapId}
        conversationId={conversationId}
        userId={userId}
        entityPositions={entityPositions}
        onDataChanged={() => { void refetchPositions(); onDataChanged?.(); }}
      />
    </div>
  );
}
