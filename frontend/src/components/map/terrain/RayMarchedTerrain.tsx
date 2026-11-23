import { useEffect, useMemo, useRef, useState } from 'react';
import { useFrame } from '@react-three/fiber';
import * as THREE from 'three';
import { CULTURE_COLORS } from '../constants';
import { MAX_ELEVATION_FACTOR } from '../utils';
import { rayMarchVertexShader, rayMarchFragmentShader } from './rayMarchShaders';
import {
  SUN_DIR,
  FOG_COLOR,
  FOG_DENSITY,
  RAY_MARCH_STEPS,
  BINARY_STEPS,
  MAX_RAY_DISTANCE,
  CLOUD_HEIGHT,
} from './shaderConstants';
import { createRoadMapTexture } from './roadTexture';
import { bakeHeightmap } from './bakeHeightmap';
import { updateCameraUniforms } from './updateCameraUniforms';
import type { CulturalRegion, RoadSegment } from '@/graphql/types';

interface DecodedTerrain {
  width: number;
  height: number;
  heightmap: Float32Array;
  waterLevel: number;
  theme?: string;
}

interface RayMarchedTerrainProps {
  terrain: DecodedTerrain;
  worldSize?: number;
  culturalRegions?: CulturalRegion[];
  showEmpires?: boolean;
  roads?: RoadSegment[];
}

/** Map theme string to shader integer uniform. */
function themeToInt(theme?: string): number {
  switch (theme) {
    case 'desert':   return 1;
    case 'arctic':   return 2;
    case 'tropical': return 3;
    default:         return 0; // temperate
  }
}

/**
 * Full-screen ray-marched terrain renderer.
 * Replaces TerrainMesh + WaterPlane + SkyDome with a single shader pass.
 * Writes gl_FragDepth so Three.js markers depth-test naturally against terrain.
 */
export default function RayMarchedTerrain({
  terrain,
  worldSize = 200,
  culturalRegions,
  showEmpires = false,
  roads,
}: RayMarchedTerrainProps) {
  const materialRef = useRef<THREE.ShaderMaterial>(null);
  const maxElevation = worldSize * MAX_ELEVATION_FACTOR;
  const { heightmap, width: gridWidth, height: gridHeight } = terrain;

  // Build the heightmap DataTexture (disposes the previous one when the terrain
  // data changes) and derive the highest surface the shader can produce.
  //
  // The texture carries heights in world units with the border fade baked in,
  // because the ray march reads it dozens of times per pixel. See bakeHeightmap.
  const { heightmapTexture, heightCeiling } = useMemo(() => {
    const { data, peak } = bakeHeightmap({
      heights: heightmap,
      width: gridWidth,
      height: gridHeight,
      worldSize,
    });

    const tex = new THREE.DataTexture(
      data,
      gridWidth,
      gridHeight,
      THREE.RedFormat,
      THREE.FloatType,
    );
    tex.minFilter = THREE.LinearFilter;
    tex.magFilter = THREE.LinearFilter;
    tex.wrapS = THREE.ClampToEdgeWrapping;
    tex.wrapT = THREE.ClampToEdgeWrapping;
    tex.needsUpdate = true;

    // Canopy bump plus the shader's 0.5 hit threshold.
    return { heightmapTexture: tex, heightCeiling: Math.max(peak + maxElevation * 0.01 + 0.5, terrain.waterLevel * maxElevation) };
  }, [heightmap, gridWidth, gridHeight, worldSize, maxElevation, terrain.waterLevel]);

  // Build road map DataTexture (disposes previous on roads change)
  const roadMapTexture = useMemo(() => {
    const tex = createRoadMapTexture(
      roads ?? [],
      worldSize,
      terrain.width,
      terrain.height,
    );
    if (tex) {
      return tex;
    }
    // 1×1 black fallback (no roads)
    const fallback = new THREE.DataTexture(
      new Float32Array([0]),
      1, 1,
      THREE.RedFormat,
      THREE.FloatType,
    );
    fallback.minFilter = THREE.LinearFilter;
    fallback.magFilter = THREE.LinearFilter;
    fallback.needsUpdate = true;
    return fallback;
  }, [roads, worldSize, terrain.width, terrain.height]);

  // Build culture uniform arrays
  const { cultureCount, cultureCenters, cultureColors } = useMemo(() => {
    if (!showEmpires || !culturalRegions || culturalRegions.length === 0) {
      return {
        cultureCount: 0,
        cultureCenters: new Array(16).fill(null).map(() => new THREE.Vector3()),
        cultureColors: new Array(16).fill(null).map(() => new THREE.Vector3()),
      };
    }

    const count = Math.min(culturalRegions.length, 16);
    const centers = new Array(16).fill(null).map(() => new THREE.Vector3());
    const colors = new Array(16).fill(null).map(() => new THREE.Vector3());

    for (let i = 0; i < count; i++) {
      const r = culturalRegions[i];
      centers[i] = new THREE.Vector3(r.worldX, r.worldY, 0);
      const c = new THREE.Color(CULTURE_COLORS[r.culture] || '#9ca3af');
      colors[i] = new THREE.Vector3(c.r, c.g, c.b);
    }

    return { cultureCount: count, cultureCenters: centers, cultureColors: colors };
  }, [culturalRegions, showEmpires]);

  // Shader uniforms — STABLE reference (state initializer). Individual values are
  // updated via useEffect / useFrame so the uniforms object identity never
  // changes, preventing R3F from recreating the ShaderMaterial and losing
  // the per-frame camera state (root cause of "controls move locations but
  // not terrain" on first generation).

  const [uniforms] = useState(() => ({
    uTime: { value: 0 },
    uCameraPosition: { value: new THREE.Vector3() },
    uInvProjectionMatrix: { value: new THREE.Matrix4() },
    uInvViewMatrix: { value: new THREE.Matrix4() },
    uProjectionMatrix: { value: new THREE.Matrix4() },
    uViewMatrix: { value: new THREE.Matrix4() },
    uLogDepthBufFC: { value: 0 },

    // Terrain — initial values set here, updated via useEffect when data changes
    uHeightmap: { value: heightmapTexture },
    uRoadMap: { value: roadMapTexture },
    uWorldSize: { value: worldSize },
    uMaxElevation: { value: maxElevation },
    uWaterLevel: { value: terrain.waterLevel },
    uTheme: { value: themeToInt(terrain.theme) },

    // Lighting
    uSunDirection: { value: SUN_DIR },
    uSunColor: { value: new THREE.Vector3(1.0, 0.95, 0.85) },

    // Fog
    uFogColor: { value: FOG_COLOR },
    uFogDensity: { value: FOG_DENSITY },

    // Cultural regions
    uShowEmpires: { value: false },
    uCultureCount: { value: 0 },
    uCultureCenters: { value: new Array(16).fill(null).map(() => new THREE.Vector3()) },
    uCultureColors: { value: new Array(16).fill(null).map(() => new THREE.Vector3()) },

    // Ray-march tuning
    uMaxRayDistance: { value: MAX_RAY_DISTANCE },
    uRayMarchSteps: { value: RAY_MARCH_STEPS },
    uBinarySteps: { value: BINARY_STEPS },
    uCloudHeight: { value: CLOUD_HEIGHT },
    uHeightCeiling: { value: heightCeiling },
  }));

  // Update terrain data uniforms when textures or config change (in-place,
  // so the uniforms object reference stays stable for R3F).
  useEffect(() => {
    uniforms.uHeightmap.value = heightmapTexture;
    uniforms.uRoadMap.value = roadMapTexture;
    uniforms.uWorldSize.value = worldSize;
    uniforms.uMaxElevation.value = maxElevation;
    uniforms.uWaterLevel.value = terrain.waterLevel;
    uniforms.uTheme.value = themeToInt(terrain.theme);
    uniforms.uHeightCeiling.value = heightCeiling;
    uniforms.uCloudHeight.value = CLOUD_HEIGHT * worldSize / 200;
  }, [uniforms, heightmapTexture, roadMapTexture, worldSize, maxElevation, terrain.waterLevel, terrain.theme, heightCeiling]);

  // Update per-frame uniforms
  useFrame(({ clock, camera }) => {
    const mat = materialRef.current;
    if (!mat) return;

    mat.uniforms.uTime.value = clock.getElapsedTime();
    updateCameraUniforms(mat.uniforms, camera);

    // Logarithmic depth buffer constant (matches Three.js internal computation)
    const cam = camera as THREE.PerspectiveCamera;
    mat.uniforms.uLogDepthBufFC.value = 2.0 / Math.log2(cam.far + 1.0);
    mat.uniforms.uMaxRayDistance.value = cam.far;
  });

  // Update cultural region uniforms reactively
  useEffect(() => {
    uniforms.uShowEmpires.value = showEmpires;
    uniforms.uCultureCount.value = cultureCount;
    uniforms.uCultureCenters.value = cultureCenters;
    uniforms.uCultureColors.value = cultureColors;
  }, [uniforms, showEmpires, cultureCount, cultureCenters, cultureColors]);

  // Dispose committed textures after replacement, never during render.
  useEffect(() => () => heightmapTexture.dispose(), [heightmapTexture]);
  useEffect(() => () => roadMapTexture.dispose(), [roadMapTexture]);

  // Full-screen clip-space quad
  const geometry = useMemo(() => new THREE.PlaneGeometry(2, 2), []);

  // Dispose geometry on unmount
  useEffect(() => {
    return () => {
      geometry.dispose();
    };
  }, [geometry]);

  return (
    <mesh
      geometry={geometry}
      renderOrder={-1}
      frustumCulled={false}
    >
      <shaderMaterial
        ref={materialRef}
        vertexShader={rayMarchVertexShader}
        fragmentShader={rayMarchFragmentShader}
        uniforms={uniforms}
        depthWrite={true}
        depthTest={true}
      />
    </mesh>
  );
}
