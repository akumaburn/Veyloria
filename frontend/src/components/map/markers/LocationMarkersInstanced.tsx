import { useRef, useState, useMemo, useEffect, useCallback } from 'react';
import { useFrame, useThree, ThreeEvent } from '@react-three/fiber';
import { Html } from '@react-three/drei';
import * as THREE from 'three';
import { LOCATION_COLORS } from '../constants';
import { toThreePos } from '../utils';
import { getLocationGeometry, disposeGeometryCache } from './locationGeometries';
import type { MapLocation } from '@/graphql/types';

interface LocationMarkersInstancedProps {
  locations: MapLocation[];
  selectedLocation: MapLocation | null;
  onSelectLocation: (loc: MapLocation | null) => void;
  maxElevation: number;
  isGenerated: boolean;
  markerScale?: number;
}

const LABEL_DISTANCE_THRESHOLD = 120;
const CAMERA_MOVE_THRESHOLD_SQ = 25;
const UNDISCOVERED_SEGMENTS = 8;

/**
 * Per-type InstancedMesh location rendering.
 * Discovered locations are grouped by locationType, each rendered with a
 * distinct procedural 3D model. Undiscovered locations use a shared wireframe sphere.
 */
export default function LocationMarkersInstanced({
  locations,
  selectedLocation,
  onSelectLocation,
  maxElevation,
  isGenerated,
  markerScale = 1.0,
}: LocationMarkersInstancedProps) {
  const { camera } = useThree();

  const undiscoveredRadius = markerScale * 0.5;
  const ringInner = markerScale * 1.0;
  const ringOuter = markerScale * 1.2;

  /* ─── group locations by type + discovery ─── */

  const { discoveredByType, undiscovered } = useMemo(() => {
    const byType: Record<string, { loc: MapLocation; index: number }[]> = {};
    const undisc: { loc: MapLocation; index: number }[] = [];

    for (const loc of locations) {
      if (loc.isDiscovered) {
        const type = loc.locationType || 'town';
        if (!byType[type]) byType[type] = [];
        byType[type].push({ loc, index: byType[type].length });
      } else {
        undisc.push({ loc, index: undisc.length });
      }
    }

    return { discoveredByType: byType, undiscovered: undisc };
  }, [locations]);

  /* ─── stable list of types present (for consistent render order) ─── */
  const typeKeys = useMemo(
    () => Object.keys(discoveredByType).sort(),
    [discoveredByType],
  );

  /* ─── refs for each type's InstancedMesh ─── */
  const typeRefs = useRef<Map<string, THREE.InstancedMesh>>(new Map());
  const undiscoveredRef = useRef<THREE.InstancedMesh>(null);

  /* ─── dispose geometry cache on unmount ─── */
  useEffect(() => () => { disposeGeometryCache(); }, []);

  /* ─── undiscovered color attribute (stable, only rebuilt when count changes) ─── */
  const undiscoveredColorAttr = useMemo(() => {
    if (undiscovered.length === 0) return null;
    const c = new THREE.Color('#6b7280');
    const arr = new Float32Array(undiscovered.length * 3);
    for (let i = 0; i < undiscovered.length; i++) {
      arr[i * 3] = c.r;
      arr[i * 3 + 1] = c.g;
      arr[i * 3 + 2] = c.b;
    }
    return new THREE.InstancedBufferAttribute(arr, 3);
  }, [undiscovered.length]);

  /* ─── set instance transforms ─── */
  useEffect(() => {
    const dummy = new THREE.Object3D();

    // Discovered: one InstancedMesh per type
    for (const type of typeKeys) {
      const mesh = typeRefs.current.get(type);
      const locs = discoveredByType[type];
      if (!mesh || !locs) continue;

      for (let i = 0; i < locs.length; i++) {
        const { loc } = locs[i];
        const basePos = toThreePos(loc.x, loc.y, loc.z);
        const yPos = isGenerated ? loc.z * maxElevation + 1 : basePos[1];
        dummy.position.set(basePos[0], yPos, basePos[2]);
        dummy.rotation.set(0, 0, 0);
        const isSelected = selectedLocation?.locationId === loc.locationId;
        dummy.scale.setScalar(markerScale * (isSelected ? 1.4 : 1.0));
        dummy.updateMatrix();
        mesh.setMatrixAt(i, dummy.matrix);
      }

      mesh.instanceMatrix.needsUpdate = true;
      mesh.computeBoundingSphere();
    }

    // Undiscovered
    if (undiscoveredRef.current && undiscovered.length > 0) {
      const dummy2 = new THREE.Object3D();

      for (let i = 0; i < undiscovered.length; i++) {
        const { loc } = undiscovered[i];
        const basePos = toThreePos(loc.x, loc.y, loc.z);
        const yPos = isGenerated ? loc.z * maxElevation + 1 : basePos[1];
        dummy2.position.set(basePos[0], yPos, basePos[2]);
        const isSelected = selectedLocation?.locationId === loc.locationId;
        dummy2.scale.setScalar(isSelected ? 1.4 : 1);
        dummy2.updateMatrix();
        undiscoveredRef.current!.setMatrixAt(i, dummy2.matrix);
      }

      undiscoveredRef.current.instanceMatrix.needsUpdate = true;
      if (undiscoveredColorAttr) {
        undiscoveredRef.current.instanceColor = undiscoveredColorAttr;
      }
      undiscoveredRef.current.computeBoundingSphere();
    }
  }, [discoveredByType, typeKeys, undiscovered, maxElevation, isGenerated, selectedLocation, markerScale, undiscoveredColorAttr]);

  /* ─── click: discovered (uses userData.locationType to find the group) ─── */
  const handleDiscoveredClick = useCallback(
    (event: ThreeEvent<MouseEvent>) => {
      event.stopPropagation();
      const type = event.object.userData.locationType as string | undefined;
      const idx = event.instanceId;
      if (idx == null || !type) return;
      const locs = discoveredByType[type];
      if (!locs || idx >= locs.length) return;
      const loc = locs[idx].loc;
      onSelectLocation(selectedLocation?.locationId === loc.locationId ? null : loc);
    },
    [discoveredByType, selectedLocation, onSelectLocation],
  );

  /* ─── click: undiscovered ─── */
  const handleUndiscoveredClick = useCallback(
    (event: ThreeEvent<MouseEvent>) => {
      event.stopPropagation();
      const idx = event.instanceId;
      if (idx == null || idx >= undiscovered.length) return;
      const loc = undiscovered[idx].loc;
      onSelectLocation(selectedLocation?.locationId === loc.locationId ? null : loc);
    },
    [undiscovered, selectedLocation, onSelectLocation],
  );

  /* ─── camera-based label LOD ─── */
  const [cameraTick, setCameraTick] = useState(0);
  const lastCamPosRef = useRef(new THREE.Vector3());

  useFrame(() => {
    if (camera.position.distanceToSquared(lastCamPosRef.current) > CAMERA_MOVE_THRESHOLD_SQ) {
      lastCamPosRef.current.copy(camera.position);
      setCameraTick(prev => prev + 1);
    }
  });

  const visibleLabels = useMemo(() => {
    const cameraPos = camera.position;
    const labels: { loc: MapLocation; dist: number }[] = [];

    // Selected undiscovered
    if (selectedLocation && !selectedLocation.isDiscovered) {
      const isUndiscovered = undiscovered.some(({ loc }) => loc.locationId === selectedLocation.locationId);
      if (isUndiscovered) labels.push({ loc: selectedLocation, dist: 0 });
    }

    // All discovered locations
    for (const type of typeKeys) {
      for (const { loc } of discoveredByType[type]) {
        if (selectedLocation?.locationId === loc.locationId) {
          labels.push({ loc, dist: 0 });
          continue;
        }
        const basePos = toThreePos(loc.x, loc.y, loc.z);
        const yPos = isGenerated ? loc.z * maxElevation + 1 : basePos[1];
        const dx = basePos[0] - cameraPos.x;
        const dy = yPos - cameraPos.y;
        const dz = basePos[2] - cameraPos.z;
        const distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < LABEL_DISTANCE_THRESHOLD * LABEL_DISTANCE_THRESHOLD) {
          labels.push({ loc, dist: distSq });
        }
      }
    }

    labels.sort((a, b) => a.dist - b.dist);
    return labels.slice(0, 50).map(l => l.loc);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cameraTick, discoveredByType, typeKeys, undiscovered, selectedLocation, maxElevation, isGenerated]);

  /* ─── selection ring animation ─── */
  const selectedRef = useRef<THREE.Mesh>(null);
  useFrame((_, delta) => {
    if (selectedRef.current) {
      selectedRef.current.rotation.y += delta * 0.5;
    }
  });

  const labelOffsetY = markerScale * 2.0;

  /* ─── render ─── */
  return (
    <>
      {/* Per-type discovered InstancedMesh groups */}
      {typeKeys.map(type => {
        const locs = discoveredByType[type];
        if (!locs || locs.length === 0) return null;
        const geom = getLocationGeometry(type);
        const color = LOCATION_COLORS[type] || '#9ca3af';

        return (
          <instancedMesh
            key={`${type}-${locs.length}`}
            ref={(el: THREE.InstancedMesh | null) => {
              if (el) typeRefs.current.set(type, el);
              else typeRefs.current.delete(type);
            }}
            args={[geom, undefined, locs.length]}
            userData={{ locationType: type }}
            onClick={handleDiscoveredClick}
          >
            <meshStandardMaterial
              color={color}
              emissive={color}
              emissiveIntensity={0.25}
            />
          </instancedMesh>
        );
      })}

      {/* Undiscovered locations (shared wireframe sphere) */}
      {undiscovered.length > 0 && (
        <instancedMesh
          ref={undiscoveredRef}
          args={[undefined, undefined, undiscovered.length]}
          onClick={handleUndiscoveredClick}
        >
          <sphereGeometry args={[undiscoveredRadius, UNDISCOVERED_SEGMENTS, UNDISCOVERED_SEGMENTS]} />
          <meshBasicMaterial color="#6b7280" wireframe transparent opacity={0.3} />
        </instancedMesh>
      )}

      {/* Selected location highlight ring */}
      {selectedLocation && (() => {
        const basePos = toThreePos(selectedLocation.x, selectedLocation.y, selectedLocation.z);
        const yPos = isGenerated ? selectedLocation.z * maxElevation + 1 : basePos[1];
        return (
          <group position={[basePos[0], yPos, basePos[2]]}>
            <mesh ref={selectedRef} rotation={[-Math.PI / 2, 0, 0]}>
              <ringGeometry args={[ringInner, ringOuter, 32]} />
              <meshBasicMaterial color="#ffffff" transparent opacity={0.6} side={THREE.DoubleSide} />
            </mesh>
          </group>
        );
      })()}

      {/* LOD labels */}
      {visibleLabels.map(loc => {
        const basePos = toThreePos(loc.x, loc.y, loc.z);
        const yPos = isGenerated ? loc.z * maxElevation + 1 : basePos[1];
        return (
          <Html
            key={loc.locationId}
            position={[basePos[0], yPos + labelOffsetY, basePos[2]]}
            center
            distanceFactor={40}
            style={{ pointerEvents: 'none' }}
          >
            <span
              style={{
                color: 'white',
                fontSize: '14px',
                fontWeight: 600,
                textShadow: '0 0 4px black, 0 0 2px black',
                whiteSpace: 'nowrap',
                userSelect: 'none',
              }}
            >
              {loc.isDiscovered ? loc.name : '?'}
            </span>
          </Html>
        );
      })}
    </>
  );
}
