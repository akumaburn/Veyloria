import { useRef } from 'react';
import { useFrame } from '@react-three/fiber';
import { Html } from '@react-three/drei';
import * as THREE from 'three';
import { LOCATION_COLORS } from '../constants';
import { toThreePos } from '../utils';
import type { MapLocation } from '@/graphql/types';

interface LocationMarkerProps {
  location: MapLocation;
  isSelected: boolean;
  onClick: () => void;
  terrainElevation?: number;
}

export default function LocationMarker({ location, isSelected, onClick, terrainElevation }: LocationMarkerProps) {
  const basePos = toThreePos(location.x, location.y, location.z);
  // If terrain elevation is provided, use it to lift the marker above terrain
  const pos: [number, number, number] = terrainElevation != null
    ? [basePos[0], terrainElevation + 1, basePos[2]]
    : basePos;
  const color = LOCATION_COLORS[location.locationType || 'town'] || '#9ca3af';
  const meshRef = useRef<THREE.Mesh>(null);

  useFrame((_, delta) => {
    if (meshRef.current && isSelected) {
      meshRef.current.rotation.y += delta * 0.5;
    }
  });

  // Fog of war: undiscovered locations render as wireframe "?" markers
  if (!location.isDiscovered) {
    return (
      <group position={pos}>
        <mesh>
          <sphereGeometry args={[1.5, 8, 8]} />
          <meshBasicMaterial color="#6b7280" wireframe transparent opacity={0.3} />
        </mesh>
        <Html
          position={[0, 3, 0]}
          center
          distanceFactor={40}
          style={{ pointerEvents: 'none' }}
        >
          <span
            style={{
              color: '#9ca3af',
              fontSize: '16px',
              fontWeight: 700,
              textShadow: '0 0 4px black',
              userSelect: 'none',
            }}
          >
            ?
          </span>
        </Html>
      </group>
    );
  }

  return (
    <group position={pos}>
      <mesh ref={meshRef} onClick={onClick}>
        <sphereGeometry args={[isSelected ? 2.5 : 1.8, 16, 16]} />
        <meshStandardMaterial
          color={color}
          emissive={color}
          emissiveIntensity={isSelected ? 0.6 : 0.2}
        />
      </mesh>
      {isSelected && (
        <mesh rotation={[-Math.PI / 2, 0, 0]}>
          <ringGeometry args={[3, 3.5, 32]} />
          <meshBasicMaterial color={color} transparent opacity={0.4} side={THREE.DoubleSide} />
        </mesh>
      )}
      <Html
        position={[0, 4, 0]}
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
          {location.name}
        </span>
      </Html>
    </group>
  );
}
