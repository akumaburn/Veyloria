import { useRef, useMemo, useEffect } from 'react';
import { useFrame } from '@react-three/fiber';
import { Html } from '@react-three/drei';
import * as THREE from 'three';
import { cn } from '@/lib/utils';
import { toThreePos } from '../utils';
import type { MapCharacterPosition } from '@/graphql/types';

/** Sims-style colors for the plumbob by role */
const PLUMBOB_COLORS = {
  player: '#4ade80',   // emerald green
  character: '#a78bfa', // violet
  otherUser: '#60a5fa', // blue
} as const;

interface EntityTokenProps {
  position: MapCharacterPosition;
  isCurrentUser: boolean;
  terrainElevation?: number;
  label?: string;
  markerScale?: number;
}

const BOB_AMPLITUDE = 0.6;
const BOB_SPEED = 2.0; // radians per second
const ROTATE_SPEED = 1.2; // radians per second
const LERP_DURATION = 1.5; // seconds for position animation

/** Smooth ease-in-out curve */
function easeInOut(t: number): number {
  return t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
}

interface AnimState {
  startPos: THREE.Vector3;
  endPos: THREE.Vector3;
  progress: number;
  active: boolean;
}

/**
 * Sims-style floating plumbob indicator for entities on the map.
 * A diamond shape hovers and rotates above the entity's position,
 * with a vertical pole and a name label.
 * Smoothly animates between positions when the entity moves.
 */
export default function EntityToken({ position, isCurrentUser, terrainElevation, label, markerScale = 1.0 }: EntityTokenProps) {
  const rootRef = useRef<THREE.Group>(null);
  const plumbobRef = useRef<THREE.Group>(null);
  const poleRef = useRef<THREE.Mesh>(null);

  const role = isCurrentUser ? 'player' : position.characterId ? 'character' : 'otherUser';
  const color = PLUMBOB_COLORS[role];
  const displayLabel = label || (isCurrentUser ? 'You' : position.characterId ? 'Character' : 'Player');

  const diamondSize = markerScale * 0.4;
  const hoverHeight = markerScale * 2.0;
  const ringInner = markerScale * 0.3;
  const ringOuter = markerScale * 0.4;
  const labelOffset = markerScale * 1.5;

  // Shared geometries — disposed on unmount
  const poleGeometry = useMemo(() => new THREE.CylinderGeometry(0.08, 0.08, hoverHeight, 4), [hoverHeight]);
  const diamondGeometry = useMemo(() => new THREE.OctahedronGeometry(diamondSize, 0), [diamondSize]);

  useEffect(() => {
    return () => {
      poleGeometry.dispose();
      diamondGeometry.dispose();
    };
  }, [poleGeometry, diamondGeometry]);

  // --- Position animation state ---
  const lastPosRef = useRef<[number, number, number] | null>(null);
  const animRef = useRef<AnimState>({
    startPos: new THREE.Vector3(),
    endPos: new THREE.Vector3(),
    progress: 1,
    active: false,
  });

  // Compute target position from props
  const targetPos = useMemo((): [number, number, number] | null => {
    if (position.x == null || position.y == null) return null;
    const base = toThreePos(position.x, position.y, position.z ?? 0);
    return terrainElevation != null
      ? [base[0], terrainElevation + 0.1, base[2]]
      : base;
  }, [position.x, position.y, position.z, terrainElevation]);

  // Detect position changes and trigger animation
  useEffect(() => {
    if (!targetPos) {
      lastPosRef.current = null;
      return;
    }
    const last = lastPosRef.current;
    if (last && (last[0] !== targetPos[0] || last[1] !== targetPos[1] || last[2] !== targetPos[2])) {
      // Position changed — start lerp animation
      const anim = animRef.current;
      // If mid-animation, start from current interpolated position
      if (anim.active && rootRef.current) {
        anim.startPos.copy(rootRef.current.position);
      } else {
        anim.startPos.set(last[0], last[1], last[2]);
      }
      anim.endPos.set(targetPos[0], targetPos[1], targetPos[2]);
      anim.progress = 0;
      anim.active = true;
    } else if (!last && rootRef.current) {
      // First mount — snap to position
      rootRef.current.position.set(targetPos[0], targetPos[1], targetPos[2]);
    }
    lastPosRef.current = targetPos;
  }, [targetPos]);

  useFrame((_state, delta) => {
    // Animate root position
    const anim = animRef.current;
    if (anim.active && rootRef.current) {
      anim.progress = Math.min(1, anim.progress + delta / LERP_DURATION);
      const t = easeInOut(anim.progress);
      rootRef.current.position.lerpVectors(anim.startPos, anim.endPos, t);
      if (anim.progress >= 1) {
        anim.active = false;
      }
    }

    // Plumbob bobbing and rotation (independent of root animation)
    if (!plumbobRef.current) return;
    const bob = Math.sin(_state.clock.elapsedTime * BOB_SPEED) * BOB_AMPLITUDE;
    plumbobRef.current.position.y = hoverHeight + bob;
    plumbobRef.current.rotation.y += ROTATE_SPEED * delta;

    // Stretch pole to match bobbing plumbob
    if (poleRef.current) {
      const scaledHeight = hoverHeight + bob;
      poleRef.current.position.y = scaledHeight / 2;
      poleRef.current.scale.y = scaledHeight / hoverHeight;
    }
  });

  // Null-coordinate guard — AFTER all hooks to respect Rules of Hooks
  if (!targetPos) return null;

  // Initial position (before animation takes over in useFrame)
  const initialPos = lastPosRef.current ?? targetPos;

  return (
    <group ref={rootRef} position={initialPos}>
      {/* Ground ring — marks the entity's exact position */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.05, 0]}>
        <ringGeometry args={[ringInner, ringOuter, 16]} />
        <meshBasicMaterial color={color} transparent opacity={0.5} side={THREE.DoubleSide} />
      </mesh>

      {/* Vertical pole */}
      <mesh ref={poleRef} position={[0, hoverHeight / 2, 0]} geometry={poleGeometry}>
        <meshBasicMaterial color={color} transparent opacity={0.25} />
      </mesh>

      {/* Floating plumbob diamond + label (bob together) */}
      <group ref={plumbobRef} position={[0, hoverHeight, 0]}>
        <mesh geometry={diamondGeometry}>
          <meshStandardMaterial
            color={color}
            emissive={color}
            emissiveIntensity={isCurrentUser ? 0.7 : 0.4}
            transparent
            opacity={0.85}
          />
        </mesh>
        {/* Inner glow core */}
        <mesh geometry={diamondGeometry} scale={[0.5, 0.5, 0.5]}>
          <meshBasicMaterial color="white" transparent opacity={0.3} />
        </mesh>

        {/* Name label — inside plumbob group so it bobs along */}
        <Html
          position={[0, labelOffset, 0]}
          center
          distanceFactor={50}
          style={{ pointerEvents: 'none' }}
        >
          <div
            className={cn(
              'whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold',
              'shadow-lg backdrop-blur-sm border',
            )}
            style={{
              backgroundColor: `${color}22`,
              borderColor: `${color}66`,
              color: 'white',
              textShadow: '0 1px 3px rgba(0,0,0,0.8)',
            }}
          >
            {displayLabel}
          </div>
        </Html>
      </group>
    </group>
  );
}
