/**
 * 2D canvas icon drawing functions for each location type.
 * Each icon is drawn centered at (cx, cy) with the given size and color.
 * Icons are designed to be recognizable at 10-16px.
 */

type IconDrawFn = (ctx: CanvasRenderingContext2D, cx: number, cy: number, s: number, color: string) => void;

/* ─────────────── settlements ─────────────── */

const drawVillage: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // House body
  ctx.fillRect(cx - s * 0.35, cy - s * 0.05, s * 0.7, s * 0.45);
  // Roof
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.45, cy - s * 0.05);
  ctx.lineTo(cx, cy - s * 0.5);
  ctx.lineTo(cx + s * 0.45, cy - s * 0.05);
  ctx.closePath();
  ctx.fill();
};

const drawTown: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Left house
  ctx.fillRect(cx - s * 0.5, cy, s * 0.42, s * 0.4);
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.55, cy);
  ctx.lineTo(cx - s * 0.29, cy - s * 0.32);
  ctx.lineTo(cx - s * 0.03, cy);
  ctx.closePath();
  ctx.fill();
  // Right house (taller)
  ctx.fillRect(cx + s * 0.05, cy - s * 0.1, s * 0.38, s * 0.5);
  ctx.beginPath();
  ctx.moveTo(cx, cy - s * 0.1);
  ctx.lineTo(cx + s * 0.24, cy - s * 0.45);
  ctx.lineTo(cx + s * 0.48, cy - s * 0.1);
  ctx.closePath();
  ctx.fill();
};

const drawCity: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Three buildings of varying height (skyline)
  ctx.fillRect(cx - s * 0.4, cy - s * 0.2, s * 0.22, s * 0.6);
  ctx.fillRect(cx - s * 0.12, cy - s * 0.45, s * 0.2, s * 0.85);
  ctx.fillRect(cx + s * 0.14, cy - s * 0.08, s * 0.22, s * 0.48);
  // Spire on tallest
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.12, cy - s * 0.45);
  ctx.lineTo(cx - s * 0.02, cy - s * 0.58);
  ctx.lineTo(cx + s * 0.08, cy - s * 0.45);
  ctx.closePath();
  ctx.fill();
};

/* ─────────────── fortifications ─────────────── */

const drawFortress: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Thick walls (wide rectangle)
  ctx.fillRect(cx - s * 0.45, cy - s * 0.05, s * 0.9, s * 0.35);
  // 4 corner towers
  const r = s * 0.1;
  for (const [dx, dy] of [[-0.38, -0.15], [0.38, -0.15], [-0.38, 0.2], [0.38, 0.2]]) {
    ctx.beginPath();
    ctx.arc(cx + s * dx, cy + s * dy, r, 0, Math.PI * 2);
    ctx.fill();
  }
};

const drawCastle: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Round tower
  ctx.beginPath();
  ctx.arc(cx, cy + s * 0.1, s * 0.28, 0, Math.PI * 2);
  ctx.fill();
  // Pointed roof
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.32, cy - s * 0.15);
  ctx.lineTo(cx, cy - s * 0.55);
  ctx.lineTo(cx + s * 0.32, cy - s * 0.15);
  ctx.closePath();
  ctx.fill();
  // Flag
  ctx.fillRect(cx - s * 0.01, cy - s * 0.55, s * 0.02, s * -0.15);
  ctx.fillRect(cx + s * 0.01, cy - s * 0.68, s * 0.14, s * 0.08);
};

const drawKeep: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Square body
  ctx.fillRect(cx - s * 0.32, cy - s * 0.25, s * 0.64, s * 0.6);
  // 4 crenellations
  const cs = s * 0.12;
  for (const [dx, dy] of [[-0.25, -0.35], [0.25, -0.35], [-0.25, 0.25], [0.25, 0.25]]) {
    ctx.fillRect(cx + s * dx - cs / 2, cy + s * dy - cs / 2, cs, cs);
  }
};

/* ─────────────── underground ─────────────── */

const drawDungeon: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Gate arch
  ctx.fillRect(cx - s * 0.35, cy - s * 0.35, s * 0.1, s * 0.7);
  ctx.fillRect(cx + s * 0.25, cy - s * 0.35, s * 0.1, s * 0.7);
  ctx.fillRect(cx - s * 0.35, cy - s * 0.4, s * 0.7, s * 0.1);
  // Bars
  ctx.fillRect(cx - s * 0.08, cy - s * 0.3, s * 0.03, s * 0.55);
  ctx.fillRect(cx + s * 0.08, cy - s * 0.3, s * 0.03, s * 0.55);
};

const drawCave: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Semicircle
  ctx.beginPath();
  ctx.arc(cx, cy + s * 0.1, s * 0.4, Math.PI, 0);
  ctx.closePath();
  ctx.fill();
};

const drawMine: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.strokeStyle = color;
  ctx.lineWidth = s * 0.1;
  ctx.lineCap = 'round';
  // Crossed picks (X shape)
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.35, cy - s * 0.35);
  ctx.lineTo(cx + s * 0.35, cy + s * 0.35);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(cx + s * 0.35, cy - s * 0.35);
  ctx.lineTo(cx - s * 0.35, cy + s * 0.35);
  ctx.stroke();
  // A-frame top
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.3, cy + s * 0.1);
  ctx.lineTo(cx, cy - s * 0.4);
  ctx.lineTo(cx + s * 0.3, cy + s * 0.1);
  ctx.closePath();
  ctx.fill();
};

/* ─────────────── nature ─────────────── */

const drawForest: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Pine tree (triangle)
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.38, cy + s * 0.4);
  ctx.lineTo(cx, cy - s * 0.45);
  ctx.lineTo(cx + s * 0.38, cy + s * 0.4);
  ctx.closePath();
  ctx.fill();
  // Trunk
  ctx.fillRect(cx - s * 0.06, cy + s * 0.25, s * 0.12, s * 0.15);
};

const drawGrove: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Round canopy
  ctx.beginPath();
  ctx.arc(cx, cy - s * 0.12, s * 0.3, 0, Math.PI * 2);
  ctx.fill();
  // Trunk
  ctx.fillRect(cx - s * 0.05, cy + s * 0.15, s * 0.1, s * 0.2);
};

const drawMountain: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Large peak
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.45, cy + s * 0.4);
  ctx.lineTo(cx - s * 0.05, cy - s * 0.5);
  ctx.lineTo(cx + s * 0.15, cy + s * 0.4);
  ctx.closePath();
  ctx.fill();
  // Smaller peak
  ctx.globalAlpha = 0.7;
  ctx.beginPath();
  ctx.moveTo(cx + s * 0.0, cy + s * 0.4);
  ctx.lineTo(cx + s * 0.25, cy - s * 0.15);
  ctx.lineTo(cx + s * 0.48, cy + s * 0.4);
  ctx.closePath();
  ctx.fill();
  ctx.globalAlpha = 1;
};

const drawLake: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Horizontal oval
  ctx.beginPath();
  ctx.ellipse(cx, cy, s * 0.42, s * 0.25, 0, 0, Math.PI * 2);
  ctx.fill();
};

const drawRiverCrossing: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.strokeStyle = color;
  ctx.lineWidth = s * 0.08;
  ctx.lineCap = 'round';
  // Bridge: three horizontal lines
  for (const dy of [-0.15, 0, 0.15]) {
    ctx.beginPath();
    ctx.moveTo(cx - s * 0.4, cy + s * dy);
    ctx.lineTo(cx + s * 0.4, cy + s * dy);
    ctx.stroke();
  }
  // Two vertical supports
  ctx.lineWidth = s * 0.06;
  for (const dx of [-0.2, 0.2]) {
    ctx.beginPath();
    ctx.moveTo(cx + s * dx, cy - s * 0.25);
    ctx.lineTo(cx + s * dx, cy + s * 0.25);
    ctx.stroke();
  }
};

const drawWater: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.strokeStyle = color;
  ctx.lineWidth = s * 0.1;
  ctx.lineCap = 'round';
  // Two wavy lines
  for (const dy of [-0.12, 0.12]) {
    ctx.beginPath();
    ctx.moveTo(cx - s * 0.4, cy + s * dy);
    ctx.quadraticCurveTo(cx - s * 0.15, cy + s * dy - s * 0.15, cx, cy + s * dy);
    ctx.quadraticCurveTo(cx + s * 0.15, cy + s * dy + s * 0.15, cx + s * 0.4, cy + s * dy);
    ctx.stroke();
  }
};

const drawSwamp: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.strokeStyle = color;
  ctx.lineWidth = s * 0.06;
  ctx.lineCap = 'round';
  // Dead tree trunk
  ctx.beginPath();
  ctx.moveTo(cx, cy + s * 0.4);
  ctx.lineTo(cx, cy - s * 0.2);
  ctx.stroke();
  // Bare branches
  ctx.beginPath();
  ctx.moveTo(cx, cy - s * 0.1);
  ctx.lineTo(cx + s * 0.25, cy - s * 0.35);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(cx, cy - s * 0.18);
  ctx.lineTo(cx - s * 0.2, cy - s * 0.38);
  ctx.stroke();
  // Water line at base
  ctx.lineWidth = s * 0.04;
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.3, cy + s * 0.35);
  ctx.quadraticCurveTo(cx, cy + s * 0.25, cx + s * 0.3, cy + s * 0.35);
  ctx.stroke();
};

const drawOasis: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Palm trunk
  ctx.fillRect(cx - s * 0.04, cy - s * 0.1, s * 0.08, s * 0.5);
  // Palm fronds (circle)
  ctx.beginPath();
  ctx.arc(cx, cy - s * 0.25, s * 0.22, 0, Math.PI * 2);
  ctx.fill();
  // Water pool at base
  ctx.globalAlpha = 0.5;
  ctx.beginPath();
  ctx.ellipse(cx, cy + s * 0.35, s * 0.2, s * 0.08, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.globalAlpha = 1;
};

/* ─────────────── religious / magical ─────────────── */

const drawShrine: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Diamond / obelisk
  ctx.beginPath();
  ctx.moveTo(cx, cy - s * 0.5);
  ctx.lineTo(cx + s * 0.18, cy);
  ctx.lineTo(cx, cy + s * 0.4);
  ctx.lineTo(cx - s * 0.18, cy);
  ctx.closePath();
  ctx.fill();
};

const drawTemple: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Roof
  ctx.fillRect(cx - s * 0.45, cy - s * 0.3, s * 0.9, s * 0.08);
  // Columns (4 vertical bars)
  for (const dx of [-0.32, -0.11, 0.11, 0.32]) {
    ctx.fillRect(cx + s * dx - s * 0.035, cy - s * 0.22, s * 0.07, s * 0.55);
  }
  // Base
  ctx.fillRect(cx - s * 0.45, cy + s * 0.28, s * 0.9, s * 0.06);
};

/* ─────────────── ancient ─────────────── */

const drawRuins: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Broken columns
  ctx.fillRect(cx - s * 0.28, cy - s * 0.15, s * 0.12, s * 0.5);
  ctx.fillRect(cx + s * 0.08, cy + s * 0.05, s * 0.12, s * 0.3);
  // Fallen block
  ctx.save();
  ctx.translate(cx + s * 0.1, cy - s * 0.2);
  ctx.rotate(0.3);
  ctx.fillRect(-s * 0.12, -s * 0.05, s * 0.24, s * 0.1);
  ctx.restore();
};

/* ─────────────── structures ─────────────── */

const drawTower: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Tall thin rectangle
  ctx.fillRect(cx - s * 0.14, cy - s * 0.2, s * 0.28, s * 0.6);
  // Pointed roof
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.2, cy - s * 0.2);
  ctx.lineTo(cx, cy - s * 0.55);
  ctx.lineTo(cx + s * 0.2, cy - s * 0.2);
  ctx.closePath();
  ctx.fill();
};

const drawPort: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Dock: L-shape
  ctx.fillRect(cx - s * 0.4, cy + s * 0.05, s * 0.75, s * 0.12);
  ctx.fillRect(cx + s * 0.2, cy - s * 0.35, s * 0.12, s * 0.52);
  // Post
  ctx.beginPath();
  ctx.arc(cx - s * 0.32, cy - s * 0.05, s * 0.06, 0, Math.PI * 2);
  ctx.fill();
};

const drawCamp: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Tent (wide triangle)
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.4, cy + s * 0.3);
  ctx.lineTo(cx, cy - s * 0.35);
  ctx.lineTo(cx + s * 0.4, cy + s * 0.3);
  ctx.closePath();
  ctx.fill();
  // Campfire dot
  ctx.globalAlpha = 0.6;
  ctx.beginPath();
  ctx.arc(cx + s * 0.35, cy + s * 0.2, s * 0.06, 0, Math.PI * 2);
  ctx.fill();
  ctx.globalAlpha = 1;
};

/* ─────────────── infrastructure ─────────────── */

const drawRoadSign: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Vertical pole
  ctx.fillRect(cx - s * 0.03, cy - s * 0.35, s * 0.06, s * 0.7);
  // Two signs pointing different directions
  ctx.beginPath();
  ctx.moveTo(cx + s * 0.03, cy - s * 0.25);
  ctx.lineTo(cx + s * 0.35, cy - s * 0.3);
  ctx.lineTo(cx + s * 0.35, cy - s * 0.15);
  ctx.lineTo(cx + s * 0.03, cy - s * 0.2);
  ctx.closePath();
  ctx.fill();
  ctx.beginPath();
  ctx.moveTo(cx - s * 0.03, cy - s * 0.05);
  ctx.lineTo(cx - s * 0.32, cy - s * 0.1);
  ctx.lineTo(cx - s * 0.32, cy + s * 0.05);
  ctx.lineTo(cx - s * 0.03, cy + s * 0.0);
  ctx.closePath();
  ctx.fill();
};

/* ─────────────── default ─────────────── */

const drawDefault: IconDrawFn = (ctx, cx, cy, s, color) => {
  ctx.fillStyle = color;
  // Diamond shape
  ctx.beginPath();
  ctx.moveTo(cx, cy - s * 0.4);
  ctx.lineTo(cx + s * 0.3, cy);
  ctx.lineTo(cx, cy + s * 0.4);
  ctx.lineTo(cx - s * 0.3, cy);
  ctx.closePath();
  ctx.fill();
};

/* ─────────────── dispatch ─────────────── */

const ICON_DRAWERS: Record<string, IconDrawFn> = {
  village: drawVillage,
  town: drawTown,
  city: drawCity,
  fortress: drawFortress,
  castle: drawCastle,
  keep: drawKeep,
  dungeon: drawDungeon,
  cave: drawCave,
  mine: drawMine,
  forest: drawForest,
  grove: drawGrove,
  mountain: drawMountain,
  lake: drawLake,
  river_crossing: drawRiverCrossing,
  water: drawWater,
  swamp: drawSwamp,
  oasis: drawOasis,
  shrine: drawShrine,
  temple: drawTemple,
  ruins: drawRuins,
  tower: drawTower,
  port: drawPort,
  camp: drawCamp,
  road: drawRoadSign,
};

/**
 * Draw a location-type icon on a 2D canvas.
 * @param ctx  - Canvas rendering context
 * @param type - Location type (from LOCATION_COLORS keys)
 * @param cx   - Center X in canvas coordinates
 * @param cy   - Center Y in canvas coordinates
 * @param size - Icon diameter in canvas pixels
 * @param color - Fill/stroke color
 */
export function drawLocationIcon(
  ctx: CanvasRenderingContext2D,
  type: string,
  cx: number,
  cy: number,
  size: number,
  color: string,
): void {
  ctx.save();
  const drawer = ICON_DRAWERS[type] ?? drawDefault;
  drawer(ctx, cx, cy, size, color);
  ctx.restore();
}
