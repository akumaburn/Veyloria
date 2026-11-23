import { useRef, useEffect } from 'react';
import * as THREE from 'three';
import { ROAD_COLORS } from '../constants';
import type { RoadSegment } from '@/graphql/types';

interface RoadLinesProps {
  roads: RoadSegment[];
  maxElevation?: number;
}

function RoadLine({ road, maxElevation = 30 }: { road: RoadSegment; maxElevation?: number }) {
  const ref = useRef<THREE.Group>(null);

  useEffect(() => {
    if (!ref.current) return;
    // Clear previous children
    while (ref.current.children.length) {
      ref.current.remove(ref.current.children[0]);
    }

    const points = road.waypoints.map(wp => {
      const elevation = (wp[2] ?? 0) * maxElevation + 0.5;
      return new THREE.Vector3(wp[0], elevation, wp[1]);
    });

    if (points.length < 2) return;

    const geometry = new THREE.BufferGeometry().setFromPoints(points);
    const color = ROAD_COLORS[road.roadType] || ROAD_COLORS.trail;
    const material = new THREE.LineBasicMaterial({ color });
    const line = new THREE.Line(geometry, material);
    ref.current.add(line);

    return () => {
      geometry.dispose();
      material.dispose();
    };
  }, [road, maxElevation]);

  return <group ref={ref} />;
}

/**
 * Renders road segments as colored lines that follow the terrain.
 * Uses imperative Three.js Line objects to avoid JSX type conflicts.
 */
export default function RoadLines({ roads, maxElevation }: RoadLinesProps) {
  return (
    <group>
      {roads.map((road) => (
        <RoadLine key={road.roadId} road={road} maxElevation={maxElevation} />
      ))}
    </group>
  );
}
