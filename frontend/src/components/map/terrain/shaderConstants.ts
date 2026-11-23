import * as THREE from 'three';

/** Sun direction matching scene lights: directionalLight at [80, 120, 60] */
export const SUN_DIR = new THREE.Vector3(80, 120, 60).normalize();

/** Fog color — matches sky horizon for seamless blending */
export const FOG_COLOR = new THREE.Vector3(0.55, 0.65, 0.78);

/** Fog density — used as exp(-distSqrd * density * altitudeScale) in the shader */
export const FOG_DENSITY = 0.000004;

// ---- Ray-march constants ----

/** Number of ray-march steps per pixel */
export const RAY_MARCH_STEPS = 120;

/** Binary subdivision steps for hit-point refinement */
export const BINARY_STEPS = 8;

/** Maximum ray distance before giving up (world units) */
export const MAX_RAY_DISTANCE = 600.0;

/** Cloud plane Y coordinate (world units) */
export const CLOUD_HEIGHT = 200.0;

