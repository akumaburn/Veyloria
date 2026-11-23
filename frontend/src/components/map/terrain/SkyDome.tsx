import { useMemo, useRef } from 'react';
import { useFrame, useThree } from '@react-three/fiber';
import * as THREE from 'three';
import { SUN_DIR, FOG_COLOR } from './shaderConstants';

const skyVertexShader = /* glsl */ `
  varying vec3 vDir;

  void main() {
    // Since sphere is centered on camera, position is already direction
    vDir = normalize(position);

    vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
    gl_Position = projectionMatrix * mvPosition;
  }
`;

const skyFragmentShader = /* glsl */ `
  uniform vec3 uSunDirection;
  uniform vec3 uFogColor;

  varying vec3 vDir;

  void main() {
    vec3 dir = normalize(vDir);

    // --------------------------------------------------
    // Vertical gradient (atmospheric density curve)
    // --------------------------------------------------

    float elevation = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);

    vec3 zenith  = vec3(0.12, 0.22, 0.50);
    vec3 horizon = uFogColor;   // Seamless terrain blend

    // Smooth atmospheric falloff
    float skyMix = pow(elevation, 0.45);
    vec3 color = mix(horizon, zenith, skyMix);

    // Darken below horizon smoothly
    float below = smoothstep(0.0, -0.2, dir.y);
    color *= mix(1.0, 0.65, below);

    // --------------------------------------------------
    // Sun disk + atmospheric scattering
    // --------------------------------------------------

    float sunDot = max(dot(dir, normalize(uSunDirection)), 0.0);

    // Soft atmospheric halo
    float halo = pow(sunDot, 6.0) * 0.35;

    // Bright sun disk
    float disk = pow(sunDot, 400.0);

    vec3 sunColorOuter = vec3(1.0, 0.8, 0.5);
    vec3 sunColorInner = vec3(1.0, 0.95, 0.85);

    color += sunColorOuter * halo;
    color += sunColorInner * disk;

    // Mild exposure control
    color = clamp(color, 0.0, 1.0);

    gl_FragColor = vec4(color, 1.0);
  }
`;

/**
 * Large inverted sphere that renders a sky gradient with sun glow.
 * Horizon color matches fog color for seamless terrain-to-sky blending.
 */
export default function SkyDome() {
  const { camera } = useThree();
  const skyRef = useRef<THREE.Mesh>(null);

  const uniforms = useMemo(
    () => ({
      uSunDirection: { value: SUN_DIR.clone().normalize() },
      uFogColor: { value: FOG_COLOR },
    }),
    [],
  );

  // Keep sky centered on camera
  useFrame(() => {
    // Move sky to camera position (no rotation)
    skyRef.current?.position.copy(camera.position);
  });

  return (
    <mesh ref={skyRef}>
      <sphereGeometry args={[5000, 64, 32]} />
      <shaderMaterial
        vertexShader={skyVertexShader}
        fragmentShader={skyFragmentShader}
        uniforms={uniforms}
        side={THREE.BackSide}
        depthWrite={false}
        depthTest={false}
      />
    </mesh>
  );
}
