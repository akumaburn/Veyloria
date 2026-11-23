# World and Local Maps

The map system answers two different questions. Keep them separate:

1. **World map** — which node of the world the party occupies. Nodes are
   settlements, castles, dungeons, forests, and other points on the generated
   terrain.
2. **Local map** — where inside that node the scene happens. A castle has a
   great hall, kitchen, courtyard, and gatehouse; a dungeon has an entrance,
   corridors, chambers, and a vault.

Narration, character dialogue, and state tracking all read the same
authoritative stage. A model cannot move the party to a room or place that the
map does not contain.

## Map page

Opening **Map** starts with the **Local** 2D layout of the party's current location. The **Global** switch shows the world map with its existing **2D View** and **3D View**. Editor, generation and location deletion controls require **Narrator mode**, whose per-conversation preference is shared with chat and defaults off after reload. In narrator mode the local map reveals the full layout; player mode retains visited-area fog. **Back to conversation** returns directly to the relevant chat without opening the conversation list. Switching back to Local preserves the selected global view. Local layout follows recorded visits and connections, retains fog of war, and shows current-room descriptions and exits. During travel between world nodes there may be no local layout; the Global view remains available. Loading, unavailable-layout and retry states are explicit on the full map page.

## Arrival cast and progress

A confirmed arrival establishes the local scene and a stable resident where the area is staffed (for example, an innkeeper at an inn). Residents stay anchored to their area and do not travel with the party. Returning reuses the character instead of creating another host. Area metadata can set `residentRole`, `populated:false` or `abandoned:true`.

Arrival narration is persisted first, followed by a separate local character greeting. Each has an idempotent story-event key; a paused, moved or superseded round cannot append an old greeting. This order applies to conversation-driven local movement, fast travel and completed waypoint journeys. Resident greetings are character dialogue and use the character voice; travel/arrival blocks retain the narrator voice.

The chat displays the actual processing phase while the server resolves travel, updates state, writes/reviews narration or prepares a greeting. It stops attributing that work to the last character speaker. Reconnects read the current progress snapshot; completion/cancellation clears it. See [the developer guide](DEVELOPER_GUIDE.md#progress-and-arrival-residents) for the event and round-fencing contracts.

## Tables

`world_map` and `map_location` are unchanged. Two tables hold the interior
layout:

- `local_area` — one room, hall, street, or clearing. Fields include
  `localAreaId`, `locationId` (the world node), `name`, `description`,
  `areaType`, `x`, `y`, `isGateway`, `isEntry`, and `sortOrder`.
- `local_area_connection` — a passable connection between two areas in the
  same node. The connection is bidirectional. The `label` names the passage
  (`service door`, `stone stair`, `main doors`).

`world_state` gains two fields:

- `currentArea` — display name of the current interior area.
- `currentAreaId` — the matching `local_area` row.

`currentLocation` and `currentLocationId` keep their existing meaning: the
world node. The pair `currentArea` / `currentAreaId` is always inside that
node.

## Layout generation

`LocalLayoutFactory` produces a deterministic layout from the node id, name,
type, and description.

- World type chooses the base template: castle, academy, inn, temple,
  dungeon, cave, mine, town, village, port, forest, mountain, camp, ruins,
  tower, water, swamp, oasis, or a generic interior.
- The node name and description can select a more specific template. A city
  node renamed to “Haylon Academy” gets lecture halls and a dormitory instead
  of a market square.
- A variant chosen from the node id adds optional rooms such as a library,
  guest wing, prison, or observatory. The same node always produces the same
  layout.

`LocalMapService` stores the layout on first entry. Area ids derive from the
node id and the area key, so concurrent requests write the same rows instead
of duplicating the layout. The service also provides the connected-area
search and shortest route used by movement validation.

## The stage

`StageService` resolves the one place the scene occupies:

- world node from `currentLocationId`, or by name, or a discovered settlement
  as the fallback;
- interior area from `currentAreaId`, or by name, or the node's entry area;
- adjacent areas and the passage that connects them;
- the route to the nearest gateway when the party is not at one;
- reachable world destinations when the party is at a gateway. A destination
  must share a road with the current node or lie within the gateway radius for
  the map scale.

The result is a `StageContext`. `StagePrompt` formats it for each audience:

- the Narrator sees the room, the exits, and the gateway route;
- a character sees the same room and may move only through a listed exit;
- the Accountant sees the exact names it may write to `currentArea` and
  `currentLocation`;
- the Muse, Casting Director, and Skeptic receive the same spatial frame.

## Movement rules

- A move inside a node must follow a path in `local_area_connection`. A model
  can walk through two connected rooms in one message; it cannot cross to a
  disconnected room.
- Leaving a node requires the party to reach a gateway area and the
  destination to be reachable by road or proximity.
- `StageService.move` records the change in `world_state` and moves the
  onstage cast and the player on the world map only when the node changes.
- Rejected moves leave the stage unchanged. The attempt is logged, and the
  next prompt still describes the real place.
- Newly introduced roster members are placed at the current stage before they
  speak.

## Dialogue-to-movement handoff

An ordinary roleplay group round runs in this order:

1. The player commits an action or speaks. Characters respond from the current stage. They may react or begin following, but cannot announce an arrival before state resolution.
2. A separate **encounter decision** checks only for established immediate hostile resistance; confirmed encounters go through the battle service. Otherwise a focused **travel decision** request examines only the player action, completed exchange and authoritative stage. It returns a destination, an established blocker, or no travel. It does not also decide inventory, stats, interactions or prose. Invalid/missing decision fields are retried once rather than treated as a successful “nothing happened.”
3. The server validates and applies the destination through the existing movement service, then refreshes the actual stage. Exact area IDs/names identify rooms; a passage label names its connected endpoint. Disconnected destinations, active battles and active journeys retain their restrictions. Comparing the original and refreshed stages also recovers a committed move if subsequent visit/event bookkeeping failed before returning its movement result.
4. If there was no travel or blocked travel, a **separate interaction decision** request asks whether a concrete interaction needs a result block. Pure discussion, future plans, incidental gestures and NPC-only suggestions do not. The decision identifies the grounded interaction/attempt; it cannot invent payment, loot, damage or success. Travel and interaction are never bundled into one model request.
5. The Accountant asks one inventory, stat, relationship or world-field question per request, collecting one operation at a time until an explicit no-change result. These domains run sequentially; the Muse generates one optional suggestion separately. Completed scene resolution is authoritative: later Accountant output cannot move the party again or overwrite the confirmed scene. Inventory, stat and relationship services validate and finish their operations before narration; failed bookkeeping cannot silently erase an already confirmed event.
6. The Narrator writes the confirmed passage/arrival, blocked-travel explanation, or interaction result. The Skeptic is told both the NPC roster and the player, plus the server-confirmed event. Revisions, empty/control-tag-only model output and editorial transport errors cannot erase a required result block. A short factual fallback survives through the final pipeline boundary. Ordinary conversation skips the narrator call entirely; the writer never decides whether a block is needed.
7. The final text is saved as an idempotent `NARRATION` story message and delivered as a completed stream event. Only then does the round return to the player. No extra character chain starts from the narrator message.

A conservative server fallback also recognizes explicit player commands to a single existing local area, such as “Let's check the Inn first.” It runs only after the character round and supplies a missed destination to the same movement service; it cannot invent an area or bypass connections. Conditional plans, questions, quoted NPC suggestions, multiple destinations and remote map inspection remain with the focused semantic decision. An explicit `movementBlockedReason` preserves an established obstacle.

The round remains in `CHARACTER_RESPONDING` while it settles. Work is fenced against the triggering player message, final character message, current speaker and group state, so pausing, interjecting or replacing the round invalidates delayed results before subsequent effects. Opening narration remains a separate initialization pass. The focused agents use the existing agent/default model configuration and log independent decision runs for diagnosis. See [Atomic LLM decisions](DEVELOPER_GUIDE.md#atomic-llm-decisions) for exact wire contracts, error semantics, operation budgets and generation boundaries.

Stopping, pausing, or replacing a streaming reply aborts its SSE buffer **and terminates its generation task**. Closing the network connection alone is insufficient: the unfinished task would retain the shared model queue and block subsequent turns until the absolute request timeout. Aborts notify the task exactly once, drop late transport events, and cancel its timeout timer. Backend shutdown also discards queued work and closes active streams rather than replaying old jobs after a reload.

This is a shared narrative stage, not a new tactical movement system. State writes across movement, inventory and other agent effects are not one distributed transaction; the boundary guards do not undo effects already committed before an interruption. Explicit map journeys continue using their waypoint, time and encounter flow.

## Scene start

Roleplay initialization generates the world map before the agent pipeline
runs. The starting node is chosen from the story's preferred types:

- “Dungeon Diving” prefers `dungeon`, `cave`, `mine`, and `ruins`;
- a castle story prefers `castle`, `keep`, `fortress`, and `tower`;
- an academy story prefers a `city`, `town`, or `village`, and the renamed
  node selects the academy layout.

After the starting node is customized, `StageService.ensureStage` creates the
interior layout and records the entry area. The Narrator and Accountant then
open the scene in that exact room. This removes the old race where the
selector invented a place name before the map existed.

## GraphQL

`localMap(conversationId: String!): LocalMap` returns the current node's
interior layout and the current area. `LocalMapPanel` renders it in the World
tab: areas are nodes, connections are lines, the current area is highlighted,
and gateways are dashed.

## 3D terrain rendering

`WorldMap3D` draws a generated map with one full-screen fragment pass
(`terrain/rayMarchShaders.ts`): a ray march over the heightmap that also shades
terrain, water, sky, clouds, and fog, and writes `gl_FragDepth` so markers and
roads depth-test against it.

The cost splits three ways for a low camera angle: the march is about 55% of the
frame, shading about 30%, and the rest is the sky path. Anything that reduces the
sample count or the work per sample is worth measuring, but the texture fetch is
almost free — dimming the heightmap to 128 pixels across changed the frame time
by nothing at all. The rules that survive:

- The heightmap texture holds heights in world units with the map-border fade
  already applied. `terrain/bakeHeightmap.ts` builds it once per terrain change;
  the shader's `SampleHeightmap` is then one clamp and one fetch. Do not move
  the fade or the elevation scale back into the shader.
- Values that depend only on uniforms (water level, the 140 and 280 height
  scales, the world half extent, the inverse world size) are derived once at the
  top of `main` into `g*` globals. Helpers read those instead of repeating
  divisions per sample.
- `Map` marches the bare heightmap and does not add the canopy. The canopy is a
  13-cycle-per-unit noise worth at most 1% of the elevation range: at the scale
  the map is viewed, it is smaller than a pixel, so marching it only spends three
  noise fields per sample and forces short steps. `Terrain2` keeps it for the
  shaded normal, so forests still look bumpy. Removing it from the march was
  worth 30 to 43% on the low-angle views.
- `MARCH_STEP` is the sample-density knob. Hoskins tuned 0.3 against a
  canopy-bumped surface; the smooth surface takes 0.45 without skipping ridges,
  which removes about a third of the samples. It is a deliberate trade of
  silhouette crispness for frame time — raising it further coarsens the grass
  texture, and dropping it back to 0.3 restores the old look.

`uHeightCeiling` is the highest surface the shader can produce, computed from
the baked peak plus the canopy bump and the 0.5 hit threshold. Rays above it
that never descend skip the march.

Measurements for a 1200×858 canvas on an Intel Arrow Lake iGPU, comparing the
shader as it was before this work with the current one at identical camera
positions: `ground` 12.1 → 4.2 ms, `grazing` 16.4 → 7.7 ms, `default` 5.2 →
2.7 ms, `sky-up` 2.1 → 0.9 ms, `wide` 1.6 → 1.0 ms. A quarter of the pixels at
the default camera differ by about 12 levels out of 255, and the low-angle views
differ more because the canopy bump no longer moves the silhouette.

### Where the time goes now

On the same iGPU the frame splits about 27% march, 48% shading, 25% sky. Every
remaining shader component was measured against the current shader and left
alone, because none is worth its visible cost: ambient occlusion (three extra
heightmap samples) and the fine material noise are each a few percent,
disabling cloud shadows is 8 to 12%, and halving the cloud octaves is 5% with a
plainly different sky. Replacing the small-exponent `pow` calls with
multiplications changed nothing, because they run on dedicated units.

The shader is not the bottleneck on capable hardware: the whole pass is 0.6 to
1.0 ms at 2560x1440 on a discrete GPU. What is left is structural, and the two
biggest items are not in the shader:

- The map renders every frame even when nothing moves, because the clouds and
  grass are time-animated. On the integrated GPU that is 2.7 to 4 ms per frame,
  a quarter of the GPU at 60 fps while the map sits idle. Switching to
  `frameloop="demand"` would recover it and freeze the animation; that trade has
  not been taken.
- The map Canvas uses `dpr={[1, 2]}`. On a high-density display that is four
  times the raymarched pixels, and the view is a noisy 3D render rather than
  text, so a lower cap would cost little.

### Map data fetching

Terrain rows are insert-only: `generateProceduralMap` creates a new `world_map`
row with a new id, and nothing updates one afterwards. Terrain and road queries
are therefore immutable per `mapId` and use `cache-first`.

`terrainData` is one object that two views read through different query
documents — the 3D map takes the heightmap, the 2D map also takes the biome
grid, and each field is about 1.4 MB of base64. `TerrainData` has no id, so
Apollo stores it embedded and the default merge would replace it, letting one
view evict the other's half. `Query.terrainData` therefore has a merge policy in
`lib/apolloCachePolicies.ts`, with a test that also documents the eviction.

The biome grid has its own query, so the 3D map never pays for it.

An earlier version of this note claimed the output was unchanged. It was not
verified: the benchmark never requested `OES_texture_float_linear`, so the
32-bit float heightmap sampled as zero and every variant rendered the same flat
plane. The harness now requests the extension and asserts that different
heightmap data produces different pixels.

## Viewer and travel invariants

Both viewers use `scaleToWorldSize`, matching `MapScaleUtils.worldSizeForScale`:
room 30, building 60, city/region 200, continent 1,000, and planet 4,000.
Canvas drawing, location picking, centering, and cultural overlays use that same
extent. The 2D viewport fits on mount and resize, supports captured pointer
panning and two-finger pinch zoom, and exposes a location selector for keyboard
use. Its toolbar stays outside the map viewport; symbols and collision-aware
labels retain a readable screen size across zoom levels, with an optional grid. 3D navigation keys
apply only while its canvas has focus.

The ray marcher clips each ray to the map's exact horizontal entry/exit interval
before sampling. Height zero outside the texture is not a renderable surface.
Water has its own bounded plane intersection, and the nearest valid surface
owns the depth buffer. Padded bounds and unbounded march steps previously drew
large outward wings at the corners. The march distance follows the camera's far
plane; fog distances scale with the world extent. Camera input runs before orbit
updates and focus animation, followed by scene projection. The terrain shader
flushes and snapshots the current camera matrices before rendering, keeping
points of interest anchored during movement rather than one frame ahead of terrain.

Map API operations require conversation ownership or active participation.
Travel endpoints must belong to the requested map; supplied travel characters
must belong to its active roster. Road search weights each segment by its speed
bonus and reconstructs the selected segments, including parallel roads.

Journey results use zero-based step numbers. Progress derives from those numbers,
rather than the number of received events, so reconnect backfills can be partial.
A cancellation is terminal, and delayed narration cannot restart it. Arrival
comes from the destination waypoint, never a model's `[ARRIVED]` tag. Each step
moves forward along the path; time advances by differences of cumulative periods
so rounding does not add extra time.

## Encounters and combat

Travel encounters, paused routes, battle narration, party voting, map-to-chat links and recovery rules are documented in [Narrative combat and travel](narrative-combat.md).
