import { useEffect, useRef } from 'react';
import { useFrame, useThree } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib';
import * as THREE from 'three';
import { MAP_CONTROLS, CAMERA_DEFAULTS } from './constants';

const {
  PAN_SPEED_3D,
  ORBIT_SPEED_3D,
  ZOOM_SPEED_3D,
  ZOOM_SPEED_3D_FAST,
  MIN_DISTANCE_3D,
} = MAP_CONTROLS;

function isInputFocused(): boolean {
  const el = document.activeElement as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

interface MapControls3DProps {
  mapScale: string;
  /** External ref so CameraController can also lerp the orbit target during fly-to. */
  controlsRef?: React.RefObject<OrbitControlsImpl | null>;
  onDoubleClickLocation?: () => void;
}

export default function MapControls3D({ mapScale, controlsRef: externalRef, onDoubleClickLocation }: MapControls3DProps) {
  const { camera, gl } = useThree();
  const internalRef = useRef<OrbitControlsImpl>(null);
  const controlsRef = externalRef ?? internalRef;
  const pressedKeys = useRef(new Set<string>());

  // Pre-allocated refs to avoid per-frame / per-event GC pressure
  const sphericalRef = useRef(new THREE.Spherical());
  const raycaster = useRef(new THREE.Raycaster());
  const groundPlane = useRef(new THREE.Plane(new THREE.Vector3(0, 1, 0), 0));
  const mouseNDC = useRef(new THREE.Vector2());
  const intersectionRef = useRef(new THREE.Vector3());
  const camToTargetRef = useRef(new THREE.Vector3());
  const forwardRef = useRef(new THREE.Vector3());
  const rightRef = useRef(new THREE.Vector3());
  const orbitOffsetRef = useRef(new THREE.Vector3());

  const maxDistance = CAMERA_DEFAULTS[mapScale]?.far ?? 10000;

  // --- Custom scroll-to-cursor zoom ---
  useEffect(() => {
    const domElement = gl.domElement;

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY === 0) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const controls = controlsRef.current;
      if (!controls) return;

      // Convert mouse to NDC
      const rect = domElement.getBoundingClientRect();
      mouseNDC.current.set(
        ((e.clientX - rect.left) / rect.width) * 2 - 1,
        -((e.clientY - rect.top) / rect.height) * 2 + 1,
      );

      // Raycast to ground plane (Y=0)
      raycaster.current.setFromCamera(mouseNDC.current, camera);
      const intersection = intersectionRef.current;
      const hit = raycaster.current.ray.intersectPlane(groundPlane.current, intersection);

      // Determine zoom speed
      const speed = e.shiftKey ? ZOOM_SPEED_3D_FAST : ZOOM_SPEED_3D;
      const direction = e.deltaY > 0 ? 1 : -1; // positive = zoom out
      const currentDistance = camera.position.distanceTo(controls.target);
      const deltaDistance = currentDistance * speed * direction;
      const newDistance = currentDistance + deltaDistance;

      // Clamp
      if (newDistance < MIN_DISTANCE_3D || newDistance > maxDistance) return;

      // Move camera along camera->target vector
      const camToTarget = camToTargetRef.current.subVectors(controls.target, camera.position).normalize();
      camera.position.addScaledVector(camToTarget, -deltaDistance);

      // Lerp orbit target toward ground intersection for sticky-cursor feel
      if (hit) {
        const shiftStrength = Math.abs(deltaDistance) / currentDistance * 0.25;
        controls.target.lerp(intersection, shiftStrength);
      }

      controls.update();
    };

    // Capture phase so we get it before OrbitControls
    domElement.addEventListener('wheel', onWheel, { passive: false, capture: true });
    return () => domElement.removeEventListener('wheel', onWheel, { capture: true } as EventListenerOptions);
  }, [camera, gl, maxDistance, controlsRef]);

  // --- Keyboard input tracking ---
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (isInputFocused() || document.activeElement !== gl.domElement || e.ctrlKey || e.metaKey || e.altKey) return;
      if (!['w', 'a', 's', 'd', 'q', 'e', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'home'].includes(e.key.toLowerCase())) return;
      e.preventDefault();

      pressedKeys.current.add(e.key.toLowerCase());

      // Home = reset view
      if (e.key === 'Home') {
        const controls = controlsRef.current;
        if (controls) {
          const config = CAMERA_DEFAULTS[mapScale] || CAMERA_DEFAULTS.region;
          camera.position.set(...config.position);
          controls.target.set(0, 0, 0);
          controls.update();
        }
        e.preventDefault();
      }
    };

    const onKeyUp = (e: KeyboardEvent) => {
      pressedKeys.current.delete(e.key.toLowerCase());
    };

    const clearKeys = () => pressedKeys.current.clear();
    const focusCanvas = () => gl.domElement.focus({ preventScroll: true });
    gl.domElement.addEventListener('pointerdown', focusCanvas);
    gl.domElement.addEventListener('blur', clearKeys);
    window.addEventListener('blur', clearKeys);
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => {
      clearKeys();
      gl.domElement.removeEventListener('pointerdown', focusCanvas);
      gl.domElement.removeEventListener('blur', clearKeys);
      window.removeEventListener('blur', clearKeys);
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
    };
  }, [camera, gl, mapScale, controlsRef]);

  // --- Per-frame keyboard movement ---
  useFrame((_state, delta) => {
    const controls = controlsRef.current;
    if (!controls) return;
    const keys = pressedKeys.current;
    if (keys.size === 0) return;

    if (isInputFocused()) return;

    const speed = PAN_SPEED_3D * delta * 60; // normalize to ~60fps
    let moved = false;

    // WASD / Arrows: pan on XZ plane relative to camera facing
    const forward = forwardRef.current;
    camera.getWorldDirection(forward);
    forward.y = 0;
    forward.normalize();
    const right = rightRef.current.crossVectors(forward, camera.up).normalize();

    if (keys.has('w') || keys.has('arrowup')) {
      controls.target.addScaledVector(forward, speed);
      camera.position.addScaledVector(forward, speed);
      moved = true;
    }
    if (keys.has('s') || keys.has('arrowdown')) {
      controls.target.addScaledVector(forward, -speed);
      camera.position.addScaledVector(forward, -speed);
      moved = true;
    }
    if (keys.has('a') || keys.has('arrowleft')) {
      controls.target.addScaledVector(right, -speed);
      camera.position.addScaledVector(right, -speed);
      moved = true;
    }
    if (keys.has('d') || keys.has('arrowright')) {
      controls.target.addScaledVector(right, speed);
      camera.position.addScaledVector(right, speed);
      moved = true;
    }

    // Q / E: orbit left / right around target
    if (keys.has('q') || keys.has('e')) {
      const orbitDir = keys.has('q') ? 1 : -1;
      const offset = orbitOffsetRef.current.subVectors(camera.position, controls.target);
      sphericalRef.current.setFromVector3(offset);
      sphericalRef.current.theta += ORBIT_SPEED_3D * orbitDir * delta * 60;
      offset.setFromSpherical(sphericalRef.current);
      camera.position.copy(controls.target).add(offset);
      camera.lookAt(controls.target);
      moved = true;
    }

    if (moved) {
      controls.update();
    }
  }, -2); // Apply input before OrbitControls (-1) and scene projection (0).

  // --- Double-click handler ---
  useEffect(() => {
    const domElement = gl.domElement;
    const onDblClick = () => {
      onDoubleClickLocation?.();
    };
    domElement.addEventListener('dblclick', onDblClick);
    return () => domElement.removeEventListener('dblclick', onDblClick);
  }, [gl, onDoubleClickLocation]);

  return (
    <OrbitControls
      ref={controlsRef as React.RefObject<OrbitControlsImpl>}
      mouseButtons={{
        LEFT: THREE.MOUSE.PAN,
        MIDDLE: THREE.MOUSE.ROTATE,
        RIGHT: THREE.MOUSE.ROTATE,
      }}
      screenSpacePanning={false}
      enableDamping
      dampingFactor={0.08}
      panSpeed={0.8}
      rotateSpeed={0.5}
      minDistance={MIN_DISTANCE_3D}
      maxDistance={maxDistance}
      maxPolarAngle={Math.PI * 0.49}
    />
  );
}
