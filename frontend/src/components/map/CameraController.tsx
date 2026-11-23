import { useRef, useEffect } from 'react';
import { useFrame, useThree } from '@react-three/fiber';
import * as THREE from 'three';
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib';
import { scaleToWorldSize } from './utils';
import { MAP_CONTROLS } from './constants';

const { FOCUS_LERP_BASE, FOCUS_LERP_MAX, FOCUS_COMPLETE_DIST } = MAP_CONTROLS;

/** Max frames before the animation force-completes (~2 s at 60 fps). */
const MAX_ANIMATION_FRAMES = 120;

interface CameraControllerProps {
  targetPosition: [number, number, number] | null;
  mapScale: string;
  fitAll?: boolean;
  orbitRef?: React.RefObject<OrbitControlsImpl | null>;
  onFocusComplete?: () => void;
}

export default function CameraController({ targetPosition, mapScale, fitAll = false, orbitRef, onFocusComplete }: CameraControllerProps) {
  const { camera, gl } = useThree();
  const targetRef = useRef<[number, number, number] | null>(null);
  const frameCountRef = useRef(0);
  // Pre-allocated to avoid per-frame GC pressure
  const cameraGoalRef = useRef(new THREE.Vector3());
  const orbitGoalRef = useRef(new THREE.Vector3());
  // Stable ref so event listeners never use a stale callback
  const onFocusCompleteRef = useRef(onFocusComplete);
  onFocusCompleteRef.current = onFocusComplete;

  if (targetPosition && targetPosition !== targetRef.current) {
    targetRef.current = targetPosition;
    frameCountRef.current = 0;
  }

  // Cancel the fly-to animation whenever the user starts interacting
  // (pointer for pan/rotate, wheel for zoom) so the map never feels locked.
  useEffect(() => {
    const canvas = gl.domElement;
    const cancelAnimation = () => {
      if (targetRef.current) {
        targetRef.current = null;
        onFocusCompleteRef.current?.();
      }
    };

    canvas.addEventListener('pointerdown', cancelAnimation);
    const cancelOnNavigation = (event: KeyboardEvent) => {
      if (['w', 'a', 's', 'd', 'q', 'e', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'home'].includes(event.key.toLowerCase())) cancelAnimation();
    };
    canvas.addEventListener('keydown', cancelOnNavigation);
    // capture phase so we hear the event before MapControls3D's stopPropagation
    const wheelTarget = canvas.parentElement ?? canvas;
    wheelTarget.addEventListener('wheel', cancelAnimation, { capture: true });
    return () => {
      canvas.removeEventListener('pointerdown', cancelAnimation);
      canvas.removeEventListener('keydown', cancelOnNavigation);
      wheelTarget.removeEventListener('wheel', cancelAnimation, { capture: true } as EventListenerOptions);
    };
  }, [gl]);

  useFrame(() => {
    if (targetRef.current) {
      frameCountRef.current++;

      const [tx, ty, tz] = targetRef.current;
      const size = scaleToWorldSize(mapScale);
      const cam = camera as THREE.PerspectiveCamera;
      // Fit a bounding sphere around the world, accounting for narrow viewports.
      const verticalFov = THREE.MathUtils.degToRad(cam.fov) / 2;
      const halfFov = Math.atan(Math.tan(verticalFov) * Math.min(1, cam.aspect));
      const offset = fitAll
        ? size * 0.75 / Math.sin(halfFov) / Math.hypot(0.7, 1)
        : size * 0.5;
      const cameraGoal = cameraGoalRef.current.set(tx, ty + offset * 0.7, tz + offset);
      const orbitGoal = orbitGoalRef.current.set(tx, ty, tz);

      const dist = camera.position.distanceTo(cameraGoal);
      const done = (dist < FOCUS_COMPLETE_DIST && (!orbitRef?.current || orbitRef.current.target.distanceTo(orbitGoal) < FOCUS_COMPLETE_DIST)) || frameCountRef.current >= MAX_ANIMATION_FRAMES || window.matchMedia('(prefers-reduced-motion: reduce)').matches;

      if (done) {
        // Snap to exact final position so orbit controls adopt a clean state
        camera.position.copy(cameraGoal);
        if (orbitRef?.current) {
          orbitRef.current.target.copy(orbitGoal);
          orbitRef.current.update();
        }
        targetRef.current = null;
        onFocusComplete?.();
      } else {
        // Distance-adaptive lerp: fast when far away, decelerates as we approach
        const lerpFactor = FOCUS_LERP_BASE + (FOCUS_LERP_MAX - FOCUS_LERP_BASE) * (1 - Math.exp(-dist * 0.005));

        camera.position.lerp(cameraGoal, lerpFactor);

        // Lerp the orbit controls target so the pivot follows the camera
        if (orbitRef?.current) {
          orbitRef.current.target.lerp(orbitGoal, lerpFactor);
          orbitRef.current.update();
        }
      }
    }
  }, -0.5); // After OrbitControls (-1), before terrain and HTML projection (0).

  return null;
}
