import { Html } from '@react-three/drei';
import { CULTURE_COLORS } from '../constants';
import type { CulturalRegion } from '@/graphql/types';

interface CulturalRegionOverlay3DProps {
  regions: CulturalRegion[];
  maxElevation?: number;
}

/**
 * Large cartographic-style text labels for cultural regions.
 * Terrain tinting is handled by TerrainMesh via the showEmpires prop.
 */
export default function CulturalRegionOverlay3D({
  regions,
  maxElevation = 30,
}: CulturalRegionOverlay3DProps) {
  if (regions.length === 0) return null;

  return (
    <>
      {regions.map((region, i) => (
        <Html
          key={`empire-label-${i}`}
          position={[region.worldX, maxElevation * 0.5, region.worldY]}
          center
          distanceFactor={80}
          style={{ pointerEvents: 'none' }}
        >
          <span
            style={{
              color: CULTURE_COLORS[region.culture] || '#9ca3af',
              fontSize: '28px',
              fontWeight: 700,
              textShadow: '0 0 8px rgba(0,0,0,0.9), 0 0 4px rgba(0,0,0,0.7), 0 2px 4px rgba(0,0,0,0.5)',
              whiteSpace: 'nowrap',
              userSelect: 'none',
              letterSpacing: '4px',
              textTransform: 'uppercase',
            }}
          >
            {region.name}
          </span>
        </Html>
      ))}
    </>
  );
}
