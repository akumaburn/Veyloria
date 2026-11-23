/**
 * Rasterizes road waypoints into a single-channel DataTexture for the
 * ray-march shader.  The texture is sampled in TerrainColour() to paint
 * terrain-appropriate road surfaces directly on the heightmap.
 *
 * Encoding (RED channel, 0-1):
 *   0.0        = no road
 *   ~0.5       = trail  (drawn first, narrower)
 *   ~1.0       = main road (drawn on top, wider)
 *
 * Anti-aliased edges come for free from Canvas2D line rendering +
 * THREE.LinearFilter on the resulting texture.
 */

import * as THREE from 'three';
import type { RoadSegment } from '@/graphql/types';

/** Road width in world units */
const MAIN_ROAD_WIDTH = 2.5;
const TRAIL_WIDTH = 1.2;

/**
 * Draws a single road path onto the 2D context.
 * Waypoints are [worldX, worldY, elevation] — only X/Y are used here.
 */
function drawRoad(
  ctx: CanvasRenderingContext2D,
  waypoints: number[][],
  halfWorld: number,
  worldSize: number,
  texWidth: number,
  texHeight: number,
): void {
  if (waypoints.length < 2) return;

  ctx.beginPath();
  for (let i = 0; i < waypoints.length; i++) {
    const wp = waypoints[i];
    const px = ((wp[0] + halfWorld) / worldSize) * texWidth;
    const py = ((wp[1] + halfWorld) / worldSize) * texHeight;
    if (i === 0) ctx.moveTo(px, py);
    else ctx.lineTo(px, py);
  }
  ctx.stroke();
}

/**
 * Creates a road-map DataTexture from an array of RoadSegments.
 *
 * @param roads      Road segments with waypoints in world coordinates
 * @param worldSize  World extent (e.g. 200 for [-100,100])
 * @param texWidth   Texture width  (should match heightmap width for alignment)
 * @param texHeight  Texture height (should match heightmap height for alignment)
 * @returns          THREE.DataTexture (RED_FORMAT, FLOAT) or null if no roads
 */
export function createRoadMapTexture(
  roads: RoadSegment[],
  worldSize: number,
  texWidth: number,
  texHeight: number,
): THREE.DataTexture | null {
  if (!roads || roads.length === 0) return null;

  const canvas = document.createElement('canvas');
  canvas.width = texWidth;
  canvas.height = texHeight;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;

  const halfWorld = worldSize * 0.5;
  const pixelsPerUnit = texWidth / worldSize;

  // Black background = no road
  ctx.fillStyle = '#000000';
  ctx.fillRect(0, 0, texWidth, texHeight);
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  // Pass 1: trails (lower intensity, narrower)
  ctx.strokeStyle = 'rgb(128, 128, 128)';
  ctx.lineWidth = Math.max(TRAIL_WIDTH * pixelsPerUnit, 1);
  for (const road of roads) {
    if (road.roadType === 'main') continue;
    drawRoad(ctx, road.waypoints, halfWorld, worldSize, texWidth, texHeight);
  }

  // Pass 2: main roads on top (full intensity, wider)
  ctx.strokeStyle = 'rgb(255, 255, 255)';
  ctx.lineWidth = Math.max(MAIN_ROAD_WIDTH * pixelsPerUnit, 1.5);
  for (const road of roads) {
    if (road.roadType !== 'main') continue;
    drawRoad(ctx, road.waypoints, halfWorld, worldSize, texWidth, texHeight);
  }

  // Extract R channel into float array
  const imageData = ctx.getImageData(0, 0, texWidth, texHeight);
  const floatData = new Float32Array(texWidth * texHeight);
  for (let i = 0; i < floatData.length; i++) {
    floatData[i] = imageData.data[i * 4] / 255.0;
  }

  // Release canvas GPU backing store
  canvas.width = 0;
  canvas.height = 0;

  const texture = new THREE.DataTexture(
    floatData,
    texWidth,
    texHeight,
    THREE.RedFormat,
    THREE.FloatType,
  );
  texture.minFilter = THREE.LinearFilter;
  texture.magFilter = THREE.LinearFilter;
  texture.wrapS = THREE.ClampToEdgeWrapping;
  texture.wrapT = THREE.ClampToEdgeWrapping;
  texture.needsUpdate = true;

  return texture;
}
