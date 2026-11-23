import { useRef, useMemo, useEffect } from 'react';
import { useFrame } from '@react-three/fiber';
import * as THREE from 'three';
import { waterVertexShader, waterFragmentShader } from './waterShaders';
import { SUN_DIR, FOG_COLOR, FOG_DENSITY } from './shaderConstants';
import { MAX_ELEVATION_FACTOR } from '../utils';

interface WaterPlaneProps {
  waterLevel: number;
  worldSize?: number;
  maxElevation?: number;
}

/**
 * Shader-based animated water plane with wave displacement,
 * Fresnel reflections, sun specular highlights, and depth transparency.
 * Geometry is rotated to XZ plane (Y-up) at the geometry level so the
 * vertex shader's Y-displacement works in world-space Y.
 */
export default function WaterPlane({ waterLevel, worldSize = 200, maxElevation }: WaterPlaneProps) {
  const materialRef = useRef<THREE.ShaderMaterial>(null);
  const maxElev = maxElevation ?? worldSize * MAX_ELEVATION_FACTOR;
  const waterY = waterLevel * maxElev;

  // Rotate geometry itself (not the mesh) so shader local coords match world coords
  const geometry = useMemo(() => {
    const plane = new THREE.PlaneGeometry(worldSize, worldSize, 64, 64);
    plane.rotateX(-Math.PI / 2);
    return plane;
  }, [worldSize]);

  const uniforms = useMemo(() => ({
    uTime: { value: 0 },
    uSunDirection: { value: SUN_DIR },
    uSunColor: { value: new THREE.Vector3(1.0, 0.95, 0.85) },
    uCameraPosition: { value: new THREE.Vector3() },
    uFogColor: { value: FOG_COLOR },
    uFogDensity: { value: FOG_DENSITY },
  }), []);

  useFrame(({ clock, camera }) => {
    if (materialRef.current) {
      materialRef.current.uniforms.uTime.value = clock.getElapsedTime();
      materialRef.current.uniforms.uCameraPosition.value.copy(camera.position);
    }
  });

  // Dispose geometry on unmount
  useEffect(() => {
    return () => {
      geometry.dispose();
    };
  }, [geometry]);

  return (
    <mesh geometry={geometry} position={[0, waterY, 0]} renderOrder={1}>
      <shaderMaterial
        ref={materialRef}
        vertexShader={waterVertexShader}
        fragmentShader={waterFragmentShader}
        uniforms={uniforms}
        transparent
        depthWrite={false}
        side={THREE.DoubleSide}
      />
    </mesh>
  );
}
