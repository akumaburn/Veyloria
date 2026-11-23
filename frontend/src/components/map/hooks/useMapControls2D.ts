import { useCallback, useEffect, useRef, useState } from 'react';
import { MAP_CONTROLS } from '../constants';

const {
  MIN_ZOOM_2D,
  MAX_ZOOM_2D,
  ZOOM_FACTOR,
  ZOOM_FACTOR_FAST,
  PAN_STEP_2D,
  PAN_MOMENTUM_FRICTION,
  MIN_MOMENTUM_SPEED,
  DRAG_PIXEL_THRESHOLD,
} = MAP_CONTROLS;

interface Offset {
  readonly x: number;
  readonly y: number;
}

export interface MapControls2DResult {
  containerRef: React.RefObject<HTMLDivElement>;
  transformRef: React.RefObject<HTMLDivElement>;
  canvasRef: React.RefObject<HTMLCanvasElement>;
  /** React state for displayed zoom and drawing detail. */
  zoomLevel: number;
  getZoom: () => number;
  getOffset: () => Offset;
  isDraggingRef: React.RefObject<boolean>;
  hasDragged: () => boolean;
  handlePointerDown: (e: React.PointerEvent) => void;
  handlePointerMove: (e: React.PointerEvent) => void;
  handlePointerUp: (e: React.PointerEvent) => void;
  handleKeyDown: (e: React.KeyboardEvent) => void;
  resetView: () => void;
  centerOnCanvas: (canvasX: number, canvasY: number) => void;
  zoomIn: () => void;
  zoomOut: () => void;
}

function clampZoom(z: number): number {
  return Math.max(MIN_ZOOM_2D, Math.min(MAX_ZOOM_2D, z));
}

/**
 * High-performance pan/zoom hook for the 2D map canvas.
 * All hot-path operations use refs + direct DOM style writes — zero React re-renders
 * during drag. Zoom state updates only when the displayed percentage changes.
 *
 * @param canvasLogicalSize The canvas draw-space width/height (e.g. CANVAS_SIZE=800).
 *   Used to convert canvas coordinates to CSS display coordinates for centering.
 */
export function useMapControls2D(canvasLogicalSize: number = 800, enabled = true): MapControls2DResult {
  const containerRef = useRef<HTMLDivElement>(null!);
  const transformRef = useRef<HTMLDivElement>(null!);
  const canvasRef = useRef<HTMLCanvasElement>(null!);

  const zoomRef = useRef(1);
  const offsetRef = useRef<Offset>({ x: 0, y: 0 });
  const isDraggingRef = useRef(false);
  const pointersRef = useRef(new Map<number, { x: number; y: number }>());
  const pinchRef = useRef<{ distance: number; zoom: number; anchorX: number; anchorY: number } | null>(null);
  const dragStartRef = useRef({ x: 0, y: 0 });
  const dragMovedRef = useRef(false);
  const lastMoveTimeRef = useRef(0);
  const velocityRef = useRef({ x: 0, y: 0 });
  const prevMouseRef = useRef({ x: 0, y: 0 });
  const momentumRafRef = useRef(0);

  // Zoom state drives the toolbar and drawing detail.
  const [zoomLevel, setZoomLevel] = useState(1);

  const applyTransform = useCallback(() => {
    const el = transformRef.current;
    if (!el) return;
    const { x, y } = offsetRef.current;
    const z = zoomRef.current;
    el.style.transform = `translate(${x}px, ${y}px) scale(${z})`;
  }, []);

  /** Updates React state for toolbar display and label-skip threshold (zoom < 0.5).
   *  Debounced: only triggers a re-render when crossing the label threshold or
   *  when the displayed percentage would visually change (>1% delta). */
  const updateZoomLevel = useCallback((z: number) => {
    setZoomLevel(prev => {
      const prevBelow = prev < 0.5;
      const newBelow = z < 0.5;
      if (prevBelow !== newBelow || Math.abs(prev - z) > 0.01) return z;
      return prev;
    });
  }, []);

  const cancelMomentum = useCallback(() => {
    if (momentumRafRef.current) {
      cancelAnimationFrame(momentumRafRef.current);
      momentumRafRef.current = 0;
    }
  }, []);

  /** Zoom toward the center of the container by the given factor (>1 = zoom in). */
  const zoomTowardCenter = useCallback((factor: number) => {
    cancelMomentum();
    const oldZ = zoomRef.current;
    const nz = clampZoom(oldZ * factor);
    if (nz === oldZ) return;
    const el = containerRef.current;
    if (el) {
      const rect = el.getBoundingClientRect();
      const cx = rect.width / 2;
      const cy = rect.height / 2;
      const off = offsetRef.current;
      offsetRef.current = {
        x: cx - (cx - off.x) * (nz / oldZ),
        y: cy - (cy - off.y) * (nz / oldZ),
      };
    }
    zoomRef.current = nz;
    applyTransform();
    updateZoomLevel(nz);
  }, [applyTransform, updateZoomLevel, cancelMomentum]);

  // --- Pointer pan and two-finger pinch ---

  const handlePointerDown = useCallback((e: React.PointerEvent) => {
    if (e.button !== 0 && e.button !== 1) return; // LMB or MMB only
    if (pointersRef.current.size >= 2) return;
    pointersRef.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    e.currentTarget.setPointerCapture(e.pointerId);
    containerRef.current?.focus({ preventScroll: true });
    cancelMomentum();
    isDraggingRef.current = true;
    if (pointersRef.current.size === 2) {
      const [a, b] = [...pointersRef.current.values()];
      const rect = e.currentTarget.getBoundingClientRect();
      pinchRef.current = {
        distance: Math.max(1, Math.hypot(b.x - a.x, b.y - a.y)),
        zoom: zoomRef.current,
        anchorX: ((a.x + b.x) / 2 - rect.left - offsetRef.current.x) / zoomRef.current,
        anchorY: ((a.y + b.y) / 2 - rect.top - offsetRef.current.y) / zoomRef.current,
      };
      dragMovedRef.current = true;
      velocityRef.current = { x: 0, y: 0 };
      return;
    }
    dragMovedRef.current = false;
    const off = offsetRef.current;
    dragStartRef.current = { x: e.clientX - off.x, y: e.clientY - off.y };
    prevMouseRef.current = { x: e.clientX, y: e.clientY };
    lastMoveTimeRef.current = performance.now();
    velocityRef.current = { x: 0, y: 0 };
    if (containerRef.current) containerRef.current.style.cursor = 'grabbing';
  }, [cancelMomentum]);

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    if (!isDraggingRef.current || !pointersRef.current.has(e.pointerId)) return;
    pointersRef.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pinchRef.current && pointersRef.current.size === 2) {
      const [a, b] = [...pointersRef.current.values()];
      const rect = e.currentTarget.getBoundingClientRect();
      const pinch = pinchRef.current;
      const zoom = clampZoom(pinch.zoom * Math.hypot(b.x - a.x, b.y - a.y) / pinch.distance);
      zoomRef.current = zoom;
      offsetRef.current = {
        x: (a.x + b.x) / 2 - rect.left - pinch.anchorX * zoom,
        y: (a.y + b.y) / 2 - rect.top - pinch.anchorY * zoom,
      };
      applyTransform();
      updateZoomLevel(zoom);
      return;
    }

    const dx = e.clientX - dragStartRef.current.x;
    const dy = e.clientY - dragStartRef.current.y;

    // Check pixel threshold before committing to drag
    if (!dragMovedRef.current) {
      const movedX = e.clientX - (dragStartRef.current.x + offsetRef.current.x);
      const movedY = e.clientY - (dragStartRef.current.y + offsetRef.current.y);
      if (Math.abs(movedX) < DRAG_PIXEL_THRESHOLD && Math.abs(movedY) < DRAG_PIXEL_THRESHOLD) return;
      dragMovedRef.current = true;
    }

    // Track velocity for momentum
    const now = performance.now();
    const dt = now - lastMoveTimeRef.current;
    if (dt > 0) {
      velocityRef.current = {
        x: (e.clientX - prevMouseRef.current.x) / dt * 16,
        y: (e.clientY - prevMouseRef.current.y) / dt * 16,
      };
    }
    lastMoveTimeRef.current = now;
    prevMouseRef.current = { x: e.clientX, y: e.clientY };

    offsetRef.current = { x: dx, y: dy };
    applyTransform();
  }, [applyTransform, updateZoomLevel]);

  const handlePointerUp = useCallback((e: React.PointerEvent) => {
    if (!pointersRef.current.delete(e.pointerId)) return;
    pinchRef.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    const remaining = [...pointersRef.current.values()][0];
    if (remaining) {
      dragStartRef.current = { x: remaining.x - offsetRef.current.x, y: remaining.y - offsetRef.current.y };
      prevMouseRef.current = remaining;
      lastMoveTimeRef.current = performance.now();
      velocityRef.current = { x: 0, y: 0 };
      return;
    }
    isDraggingRef.current = false;
    if (containerRef.current) containerRef.current.style.cursor = 'grab';

    if (e.type === 'pointercancel') return;

    // Start momentum decay
    const vel = { ...velocityRef.current };
    const speed = Math.sqrt(vel.x * vel.x + vel.y * vel.y);
    if (speed < MIN_MOMENTUM_SPEED || !dragMovedRef.current ||
        performance.now() - lastMoveTimeRef.current > 80 ||
        window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    const tick = () => {
      vel.x *= PAN_MOMENTUM_FRICTION;
      vel.y *= PAN_MOMENTUM_FRICTION;
      if (Math.sqrt(vel.x * vel.x + vel.y * vel.y) < MIN_MOMENTUM_SPEED) {
        momentumRafRef.current = 0;
        return;
      }
      const off = offsetRef.current;
      offsetRef.current = { x: off.x + vel.x, y: off.y + vel.y };
      applyTransform();
      momentumRafRef.current = requestAnimationFrame(tick);
    };
    momentumRafRef.current = requestAnimationFrame(tick);
  }, [applyTransform]);

  // --- Scroll zoom (non-passive, toward cursor) ---

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY === 0) return;
      e.preventDefault();
      cancelMomentum();
      const rect = el.getBoundingClientRect();
      const cursorX = e.clientX - rect.left;
      const cursorY = e.clientY - rect.top;

      const factor = e.shiftKey ? ZOOM_FACTOR_FAST : ZOOM_FACTOR;
      const direction = e.deltaY < 0 ? factor : 1 / factor;
      const oldZoom = zoomRef.current;
      const newZoom = clampZoom(oldZoom * direction);
      if (newZoom === oldZoom) return;

      const off = offsetRef.current;
      // Zoom-toward-cursor: keep the point under the cursor fixed
      const newOffX = cursorX - (cursorX - off.x) * (newZoom / oldZoom);
      const newOffY = cursorY - (cursorY - off.y) * (newZoom / oldZoom);

      zoomRef.current = newZoom;
      offsetRef.current = { x: newOffX, y: newOffY };
      applyTransform();
      updateZoomLevel(newZoom);
    };

    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [applyTransform, updateZoomLevel, cancelMomentum, enabled]);

  // Cleanup momentum rAF on unmount
  useEffect(() => cancelMomentum, [cancelMomentum]);

  const resetView = useCallback(() => {
    cancelMomentum();
    const el = containerRef.current;
    if (!el) {
      zoomRef.current = 1;
      offsetRef.current = { x: 0, y: 0 };
      applyTransform();
      updateZoomLevel(1);
      return;
    }
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    // Fit the entire square canvas within the container
    const fitZoom = clampZoom(Math.min(1, rect.height / rect.width));
    const displaySize = rect.width * fitZoom;
    zoomRef.current = fitZoom;
    offsetRef.current = {
      x: (rect.width - displaySize) / 2,
      y: (rect.height - displaySize) / 2,
    };
    applyTransform();
    updateZoomLevel(fitZoom);
  }, [applyTransform, updateZoomLevel, cancelMomentum]);

  // --- Keyboard nav (attached to container, not window) ---

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    cancelMomentum();
    const key = e.key.toLowerCase();
    const step = PAN_STEP_2D;

    switch (key) {
      case 'w':
      case 'arrowup': {
        const off = offsetRef.current;
        offsetRef.current = { x: off.x, y: off.y + step };
        applyTransform();
        e.preventDefault();
        break;
      }
      case 's':
      case 'arrowdown': {
        const off = offsetRef.current;
        offsetRef.current = { x: off.x, y: off.y - step };
        applyTransform();
        e.preventDefault();
        break;
      }
      case 'a':
      case 'arrowleft': {
        const off = offsetRef.current;
        offsetRef.current = { x: off.x + step, y: off.y };
        applyTransform();
        e.preventDefault();
        break;
      }
      case 'd':
      case 'arrowright': {
        const off = offsetRef.current;
        offsetRef.current = { x: off.x - step, y: off.y };
        applyTransform();
        e.preventDefault();
        break;
      }
      case '+':
      case '=': {
        zoomTowardCenter(ZOOM_FACTOR);
        e.preventDefault();
        break;
      }
      case '-': {
        zoomTowardCenter(1 / ZOOM_FACTOR);
        e.preventDefault();
        break;
      }
      case 'home': {
        resetView();
        e.preventDefault();
        break;
      }
    }
  }, [applyTransform, cancelMomentum, resetView, zoomTowardCenter]);

  // --- Utility functions ---

  const getZoom = useCallback(() => zoomRef.current, []);
  const getOffset = useCallback(() => offsetRef.current, []);
  const hasDragged = useCallback(() => dragMovedRef.current, []);

  // The canvas may mount only after its query finishes. Fit and attach the
  // resize observer when it becomes available, rather than only on first render.
  useEffect(() => {
    if (!enabled || !containerRef.current) return;
    resetView();
    const observer = new ResizeObserver(resetView);
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [enabled, resetView]);

  const centerOnCanvas = useCallback((canvasX: number, canvasY: number) => {
    cancelMomentum();
    const el = containerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const z = zoomRef.current;
    // Convert canvas draw coords to CSS display coords: canvas width: 100% of container
    const displayScale = rect.width / canvasLogicalSize;
    offsetRef.current = {
      x: rect.width / 2 - canvasX * displayScale * z,
      y: rect.height / 2 - canvasY * displayScale * z,
    };
    applyTransform();
  }, [applyTransform, canvasLogicalSize, cancelMomentum]);

  const zoomIn = useCallback(() => zoomTowardCenter(ZOOM_FACTOR), [zoomTowardCenter]);
  const zoomOut = useCallback(() => zoomTowardCenter(1 / ZOOM_FACTOR), [zoomTowardCenter]);

  return {
    containerRef,
    transformRef,
    canvasRef,
    zoomLevel,
    getZoom,
    getOffset,
    isDraggingRef,
    hasDragged,
    handlePointerDown,
    handlePointerMove,
    handlePointerUp,
    handleKeyDown,
    resetView,
    centerOnCanvas,
    zoomIn,
    zoomOut,
  };
}
