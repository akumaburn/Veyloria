import { useEffect, useMemo, useRef } from 'react';
import { useFrame } from '@react-three/fiber';
import * as THREE from 'three';
import { CULTURE_COLORS } from '../constants';
import { MAX_ELEVATION_FACTOR } from '../utils';
import { terrainVertexShader, terrainFragmentShader } from './terrainShaders';
import { SUN_DIR, FOG_COLOR, FOG_DENSITY } from './shaderConstants';
import { createRoadMapTexture } from './roadTexture';
import type { CulturalRegion, RoadSegment } from '@/graphql/types';

interface DecodedTerrain {
  width: number;
  height: number;
  heightmap: Float32Array;
  biomeGrid: Int32Array;
  waterLevel: number;
  theme?: string;
}

/** Convert theme string to integer matching rayMarchShaders.ts */
function themeToInt(theme?: string): number {
  switch (theme) {
    case 'desert':   return 1;
    case 'arctic':   return 2;
    case 'tropical': return 3;
    default:         return 0; // temperate
  }
}

interface TerrainMeshProps {
  terrain: DecodedTerrain;
  worldSize?: number;
  culturalRegions?: CulturalRegion[];
  showEmpires?: boolean;
  roads?: RoadSegment[];
}

const MAX_CULTURES = 16;

export default function TerrainMesh({
  terrain,
  worldSize = 200,
  culturalRegions = [],
  showEmpires = false,
  roads,
}: TerrainMeshProps) {
  const materialRef = useRef<THREE.ShaderMaterial>(null!);
  const roadTexRef = useRef<THREE.DataTexture | null>(null);
  const maxElevation = worldSize * MAX_ELEVATION_FACTOR;

  /* -------------------------------------------------- */
  /* Heightmap Texture - for per-pixel normal calc      */
  /* Uses NearestFilter to match backend biome grid cell boundaries exactly.
  * LinearFilter would interpolate between cells, causing cells classified as
  * BEACH in the backend to render as water in the shader at cell boundaries. */
  /* -------------------------------------------------- */

  const heightmapTexture = useMemo(() => {
    const { width, height, heightmap } = terrain;
    const dataTexture = new THREE.DataTexture(
      heightmap,
      width,
      height,
      THREE.RedFormat,
      THREE.FloatType
    );
    dataTexture.needsUpdate = true;
    // CRITICAL: Use NearestFilter to avoid interpolation artifacts at cell boundaries.
    // This ensures shader samples exact cell values matching backend biome classification.
    dataTexture.minFilter = THREE.NearestFilter;
    dataTexture.magFilter = THREE.NearestFilter;
    dataTexture.wrapS = THREE.ClampToEdgeWrapping;
    dataTexture.wrapT = THREE.ClampToEdgeWrapping;
    return dataTexture;
  }, [terrain]);

  /* -------------------------------------------------- */
  /* Road Map Texture                                    */
  /* -------------------------------------------------- */

  const roadMapTexture = useMemo(() => {
    if (roadTexRef.current) {
      roadTexRef.current.dispose();
      roadTexRef.current = null;
    }
    const tex = createRoadMapTexture(
      roads ?? [],
      worldSize,
      terrain.width,
      terrain.height,
    );
    if (tex) {
      roadTexRef.current = tex;
      return tex;
    }
    // 1x1 black fallback (no roads)
    const fallback = new THREE.DataTexture(
      new Float32Array([0]),
      1, 1,
      THREE.RedFormat,
      THREE.FloatType,
    );
    fallback.minFilter = THREE.LinearFilter;
    fallback.magFilter = THREE.LinearFilter;
    fallback.needsUpdate = true;
    roadTexRef.current = fallback;
    return fallback;
  }, [roads, worldSize, terrain.width, terrain.height]);

  /* -------------------------------------------------- */
  /* Geometry                                            */
  /* -------------------------------------------------- */

  const geometry = useMemo(() => {
    const { width, height, heightmap } = terrain;

    const geo = new THREE.PlaneGeometry(worldSize, worldSize, width - 1, height - 1);

    geo.rotateX(-Math.PI / 2);

    const pos = geo.attributes.position as THREE.BufferAttribute;
    const array = pos.array as Float32Array;

    // Direct buffer write (3 floats per vertex)
    for (let i = 0; i < heightmap.length; i++) {
      array[i * 3 + 1] = heightmap[i] * maxElevation;
    }

    pos.needsUpdate = true;
    // Note: We don't use computeVertexNormals() since we compute normals
    // from the heightmap texture in the shader for per-pixel accuracy

    return geo;
  }, [terrain, worldSize, maxElevation]);

  /* -------------------------------------------------- */
  /* Stable Culture Buffers                             */
  /* -------------------------------------------------- */

  const cultureCenters = useMemo(
    () => Array.from({ length: MAX_CULTURES }, () => new THREE.Vector3()),
    [],
  );

  const cultureColors = useMemo(
    () => Array.from({ length: MAX_CULTURES }, () => new THREE.Vector3()),
    [],
  );

  /* -------------------------------------------------- */
  /* Stable Uniform Object                              */
  /* -------------------------------------------------- */

  const uniforms = useMemo(
    () => ({
      uWaterLevel: { value: terrain.waterLevel },
      uMaxElevation: { value: maxElevation },
      uWorldSize: { value: worldSize },
      uHeightmap: { value: heightmapTexture },
      uRoadMap: { value: roadMapTexture },
      uSunDirection: { value: SUN_DIR.clone().normalize() },
      uSunColor: { value: new THREE.Vector3(1.0, 0.95, 0.85) },
      uAmbientColor: { value: new THREE.Vector3(0.35, 0.38, 0.45) },

      uTheme: { value: themeToInt(terrain.theme) },

      uShowEmpires: { value: false },
      uCultureCount: { value: 0 },
      uCultureCenters: { value: cultureCenters },
      uCultureColors: { value: cultureColors },

      uFogColor: { value: FOG_COLOR },
      uFogDensity: { value: FOG_DENSITY },

      uCameraPosition: { value: new THREE.Vector3() },
    }),
    [terrain.waterLevel, terrain.theme, maxElevation, worldSize, heightmapTexture, roadMapTexture, cultureCenters, cultureColors],
  );

  /* -------------------------------------------------- */
  /* Update Cultural Data (mutation, not replacement)  */
  /* -------------------------------------------------- */

  useEffect(() => {
    const mat = materialRef.current;
    if (!mat) return;

    const count =
      showEmpires && culturalRegions.length ? Math.min(culturalRegions.length, MAX_CULTURES) : 0;

    for (let i = 0; i < MAX_CULTURES; i++) {
      if (i < count) {
        const r = culturalRegions[i];

        cultureCenters[i].set(r.worldX, r.worldY, 0);

        const c = new THREE.Color(CULTURE_COLORS[r.culture] ?? '#9ca3af');
        cultureColors[i].set(c.r, c.g, c.b);
      } else {
        cultureCenters[i].set(0, 0, 0);
        cultureColors[i].set(0, 0, 0);
      }
    }

    mat.uniforms.uShowEmpires.value = showEmpires;
    mat.uniforms.uCultureCount.value = count;
  }, [culturalRegions, showEmpires, cultureCenters, cultureColors]);

  /* -------------------------------------------------- */
  /* Per-frame camera uniform update                    */
  /* -------------------------------------------------- */

  useFrame(({ camera }) => {
    materialRef.current?.uniforms.uCameraPosition.value.copy(camera.position);
  });

  /* -------------------------------------------------- */
  /* Cleanup                                            */
  /* -------------------------------------------------- */

  useEffect(() => {
    return () => {
      geometry.dispose();
      heightmapTexture.dispose();
      if (roadTexRef.current) {
        roadTexRef.current.dispose();
        roadTexRef.current = null;
      }
    };
  }, [geometry, heightmapTexture]);

  /* -------------------------------------------------- */

  return (
    <mesh geometry={geometry} receiveShadow>
      <shaderMaterial
        ref={materialRef}
        vertexShader={terrainVertexShader}
        fragmentShader={terrainFragmentShader}
        uniforms={uniforms}
      />
    </mesh>
  );
}
