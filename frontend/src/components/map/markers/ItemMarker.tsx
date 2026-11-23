import { Html } from '@react-three/drei';
import { toThreePos } from '../utils';
import type { MapItemPosition } from '@/graphql/types';

interface ItemMarkerProps {
  item: MapItemPosition;
  terrainElevation?: number;
  markerScale?: number;
}

export default function ItemMarker({ item, terrainElevation, markerScale = 1.0 }: ItemMarkerProps) {
  const basePos = toThreePos(item.x, item.y, item.z);
  const pos: [number, number, number] = terrainElevation != null
    ? [basePos[0], terrainElevation + 0.5, basePos[2]]
    : basePos;

  if (!item.isDiscovered) return null;

  const boxSize = markerScale * 0.3;
  const labelOffsetY = markerScale * 1.0;

  return (
    <group position={pos}>
      <mesh position={[0, boxSize / 2, 0]} rotation={[0, Math.PI / 4, 0]}>
        <boxGeometry args={[boxSize, boxSize, boxSize]} />
        <meshStandardMaterial color="#fbbf24" emissive="#fbbf24" emissiveIntensity={0.3} />
      </mesh>
      <Html
        position={[0, labelOffsetY, 0]}
        center
        distanceFactor={40}
        style={{ pointerEvents: 'none' }}
      >
        <span
          style={{
            color: '#fbbf24',
            fontSize: '12px',
            fontWeight: 600,
            textShadow: '0 0 4px black, 0 0 2px black',
            whiteSpace: 'nowrap',
            userSelect: 'none',
          }}
        >
          {item.itemName}
        </span>
      </Html>
    </group>
  );
}
