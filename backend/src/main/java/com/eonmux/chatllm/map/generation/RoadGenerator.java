package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.RoadSegment;
import com.eonmux.chatllm.map.models.TerrainData;
import com.eonmux.chatllm.models.MapLocation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Generates roads between settlements using MST + A* pathfinding on terrain grid.
 * Cost function penalizes steep slopes and water crossings.
 *
 * Optimizations for large maps (>256 grid, >500 connectable):
 * - Caps connectable subset to 500 random settlements before MST
 * - Downsamples A* pathfinding grid to 256x256 for grids >256
 * - Trails use straight-line interpolation instead of full A*
 * - Waypoint subsampling rate increases for large grids
 */
@Slf4j
public class RoadGenerator {

    private static final Set<String> CONNECTABLE_TYPES = Set.of(
        "town", "city", "village", "castle", "fortress", "keep", "camp", "port"
    );
    private static final int MAX_CONNECTABLE = 1500;
    private static final int PATHFINDING_MAX_GRID = 384;
    /** Min trail interpolation points; scales with distance to maintain ~20-unit spacing. */
    private static final int TRAIL_INTERPOLATION_POINTS_MIN = 3;
    private static final float TRAIL_MAX_SPACING = 20f;

    public List<RoadSegment> generate(TerrainData terrain, List<MapLocation> locations) {
        // Find settlements to connect with roads
        List<MapLocation> connectable = locations.stream()
            .filter(loc -> CONNECTABLE_TYPES.contains(loc.getLocationType()))
            .toList();

        if (connectable.size() < 2) return List.of();

        // Cap connectable subset for MST performance
        Random rng = new Random(terrain.getSeed() + 99999);
        if (connectable.size() > MAX_CONNECTABLE) {
            int originalCount = connectable.size();
            List<MapLocation> shuffled = new ArrayList<>(connectable);
            Collections.shuffle(shuffled, rng);
            connectable = shuffled.subList(0, MAX_CONNECTABLE);
            log.debug("Capped connectable locations from {} to {} for MST", originalCount, MAX_CONNECTABLE);
        }

        // Build MST using Prim's algorithm on Euclidean distances
        List<int[]> mstEdges = buildMST(connectable);

        // Add a few extra edges for connectivity variety (up to 20% more)
        int extraEdges = Math.max(1, mstEdges.size() / 5);
        Set<String> edgeSet = new HashSet<>();
        for (int[] edge : mstEdges) {
            edgeSet.add(edgeKey(edge[0], edge[1]));
        }
        for (int i = 0; i < extraEdges * 10 && extraEdges > 0; i++) {
            int a = rng.nextInt(connectable.size());
            int b = rng.nextInt(connectable.size());
            if (a == b) continue;
            String key = edgeKey(a, b);
            if (edgeSet.contains(key)) continue;
            // Only add if not too far apart
            MapLocation la = connectable.get(a);
            MapLocation lb = connectable.get(b);
            double dist = Math.sqrt(Math.pow(la.getX() - lb.getX(), 2) + Math.pow(la.getY() - lb.getY(), 2));
            if (dist < 80) { // reasonable distance
                mstEdges.add(new int[]{a, b});
                edgeSet.add(key);
                extraEdges--;
            }
        }

        // Prepare downsampled heightmap for A* if grid is large
        int gridSize = terrain.getWidth();
        float[] pathfindingHeightmap;
        int[] pathfindingBiomeGrid;
        int pathfindingSize;

        if (gridSize > PATHFINDING_MAX_GRID) {
            pathfindingSize = PATHFINDING_MAX_GRID;
            pathfindingHeightmap = downsampleHeightmap(terrain.getHeightmap(), gridSize, pathfindingSize);
            pathfindingBiomeGrid = downsampleBiomeGrid(terrain.getBiomeGrid(), gridSize, pathfindingSize);
        } else {
            pathfindingSize = gridSize;
            pathfindingHeightmap = terrain.getHeightmap();
            pathfindingBiomeGrid = terrain.getBiomeGrid();
        }

        // Subsample rate scales with grid size
        int subsampleRate = gridSize > 256 ? 6 : 3;

        // Generate paths for each edge
        List<RoadSegment> roads = new ArrayList<>();
        int roadNum = 0;
        for (int[] edge : mstEdges) {
            MapLocation from = connectable.get(edge[0]);
            MapLocation to = connectable.get(edge[1]);

            boolean isMainRoad = isMainRoadEndpoint(from) && isMainRoadEndpoint(to);
            String roadType = isMainRoad ? "main" : "trail";

            List<float[]> waypoints;
            if (isMainRoad) {
                // Full A* pathfinding for main roads (on potentially downsampled grid)
                waypoints = findPath(pathfindingHeightmap, pathfindingBiomeGrid, pathfindingSize,
                                      terrain.getWaterLevel(), from, to, subsampleRate,
                                      worldSizeForScale(terrain.getMapScale()));
            } else {
                // Straight-line interpolation for trails
                waypoints = interpolateTrail(terrain, from, to);
            }

            roads.add(RoadSegment.builder()
                .roadId("road-" + (roadNum++))
                .fromLocationId(from.getLocationId() != null ? from.getLocationId().toString() : "")
                .toLocationId(to.getLocationId() != null ? to.getLocationId().toString() : "")
                .waypoints(waypoints)
                .roadType(roadType)
                .build());
        }

        return roads;
    }

    /**
     * Straight-line interpolation with elevation sampling for trails.
     * Scales the number of intermediate points with distance so long trails
     * don't skip over terrain features.
     */
    private List<float[]> interpolateTrail(TerrainData terrain, MapLocation from, MapLocation to) {
        int gridSize = terrain.getWidth();
        float[] heightmap = terrain.getHeightmap();
        float ws = worldSizeForScale(terrain.getMapScale());
        List<float[]> waypoints = new ArrayList<>();

        float dx = to.getX() - from.getX();
        float dy = to.getY() - from.getY();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        int numPoints = Math.max(TRAIL_INTERPOLATION_POINTS_MIN, (int) (dist / TRAIL_MAX_SPACING));

        waypoints.add(new float[]{from.getX(), from.getY(), from.getZ()});
        for (int i = 1; i <= numPoints; i++) {
            float t = (float) i / (numPoints + 1);
            float wx = from.getX() + dx * t;
            float wy = from.getY() + dy * t;
            int gx = clamp(toGrid(wx, gridSize, ws), 0, gridSize - 1);
            int gy = clamp(toGrid(wy, gridSize, ws), 0, gridSize - 1);
            float wz = heightmap[gy * gridSize + gx];
            waypoints.add(new float[]{wx, wy, wz});
        }
        waypoints.add(new float[]{to.getX(), to.getY(), to.getZ()});

        return waypoints;
    }

    /**
     * Prim's MST on the connectable locations using Euclidean distance.
     */
    private List<int[]> buildMST(List<MapLocation> locations) {
        int n = locations.size();
        boolean[] inMST = new boolean[n];
        double[] minDist = new double[n];
        int[] parent = new int[n];
        Arrays.fill(minDist, Double.MAX_VALUE);
        Arrays.fill(parent, -1);
        minDist[0] = 0;

        List<int[]> edges = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            // Find min vertex not in MST
            int u = -1;
            double best = Double.MAX_VALUE;
            for (int v = 0; v < n; v++) {
                if (!inMST[v] && minDist[v] < best) {
                    best = minDist[v];
                    u = v;
                }
            }
            if (u == -1) break;
            inMST[u] = true;

            if (parent[u] != -1) {
                edges.add(new int[]{parent[u], u});
            }

            MapLocation lu = locations.get(u);
            for (int v = 0; v < n; v++) {
                if (inMST[v]) continue;
                MapLocation lv = locations.get(v);
                double dist = Math.sqrt(Math.pow(lu.getX() - lv.getX(), 2) + Math.pow(lu.getY() - lv.getY(), 2));
                if (dist < minDist[v]) {
                    minDist[v] = dist;
                    parent[v] = u;
                }
            }
        }

        return edges;
    }

    /**
     * A* pathfinding on terrain grid. Cost = 1 + slope_penalty + water_penalty.
     */
    private List<float[]> findPath(float[] heightmap, int[] biomeGrid, int size,
                                    float waterLevel, MapLocation from, MapLocation to,
                                    int subsampleRate, float worldScale) {
        int sx = clamp(toGrid(from.getX(), size, worldScale), 0, size - 1);
        int sy = clamp(toGrid(from.getY(), size, worldScale), 0, size - 1);
        int ex = clamp(toGrid(to.getX(), size, worldScale), 0, size - 1);
        int ey = clamp(toGrid(to.getY(), size, worldScale), 0, size - 1);

        // A* with closed set
        int totalCells = size * size;
        double[] gScore = new double[totalCells];
        Arrays.fill(gScore, Double.MAX_VALUE);
        int[] cameFrom = new int[totalCells];
        Arrays.fill(cameFrom, -1);
        boolean[] closed = new boolean[totalCells];

        gScore[sy * size + sx] = 0;

        // Priority queue: [cost, x, y]
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        open.add(new double[]{heuristic(sx, sy, ex, ey), sx, sy});

        int[][] dirs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};

        while (!open.isEmpty()) {
            double[] current = open.poll();
            int cx = (int) current[1];
            int cy = (int) current[2];
            int ci = cy * size + cx;

            if (cx == ex && cy == ey) break;
            if (closed[ci]) continue;
            closed[ci] = true;

            float currentElev = heightmap[ci];

            for (int[] dir : dirs) {
                int nx = cx + dir[0];
                int ny = cy + dir[1];
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;

                int ni = ny * size + nx;
                float nextElev = heightmap[ni];

                // Cost calculation
                double stepCost = (dir[0] != 0 && dir[1] != 0) ? 1.414 : 1.0;
                double slopePenalty = Math.abs(nextElev - currentElev) * 10;
                double waterPenalty = nextElev < waterLevel ? 20 : 0;
                int nextBiome = biomeGrid[ni];
                double riverPenalty = (nextBiome == Biome.RIVER.getId()) ? 8 : 0;
                double lakePenalty = (nextBiome == Biome.LAKE.getId()) ? 15 : 0;
                double deepWaterPenalty = (nextBiome == Biome.DEEP_WATER.getId()) ? 50 : 0;
                double cost = stepCost + slopePenalty + waterPenalty + riverPenalty + lakePenalty + deepWaterPenalty;

                double tentativeG = gScore[ci] + cost;
                if (tentativeG < gScore[ni]) {
                    gScore[ni] = tentativeG;
                    cameFrom[ni] = ci;
                    double f = tentativeG + heuristic(nx, ny, ex, ey);
                    open.add(new double[]{f, nx, ny});
                }
            }
        }

        // Reconstruct path and subsample
        List<int[]> gridPath = new ArrayList<>();
        int ci = ey * size + ex;
        while (ci != -1) {
            gridPath.add(new int[]{ci % size, ci / size});
            ci = cameFrom[ci];
        }
        Collections.reverse(gridPath);

        // Subsample to reduce waypoint count
        List<float[]> waypoints = new ArrayList<>();
        for (int i = 0; i < gridPath.size(); i++) {
            if (i == 0 || i == gridPath.size() - 1 || i % subsampleRate == 0) {
                int[] gp = gridPath.get(i);
                float wx = toWorld(gp[0], size, worldScale);
                float wy = toWorld(gp[1], size, worldScale);
                float wz = heightmap[gp[1] * size + gp[0]];
                waypoints.add(new float[]{wx, wy, wz});
            }
        }

        // If A* failed (no path found), fall back to straight line
        if (waypoints.size() <= 1) {
            log.warn("A* pathfinding failed for road {} → {}, using straight-line fallback",
                     from.getName(), to.getName());
            waypoints.clear();
            waypoints.add(new float[]{from.getX(), from.getY(), from.getZ()});
            waypoints.add(new float[]{to.getX(), to.getY(), to.getZ()});
        }

        return waypoints;
    }

    /**
     * Downsample a heightmap from sourceSize to targetSize using nearest-neighbor sampling.
     */
    private float[] downsampleHeightmap(float[] source, int sourceSize, int targetSize) {
        float[] target = new float[targetSize * targetSize];
        float ratio = (float) sourceSize / targetSize;

        for (int ty = 0; ty < targetSize; ty++) {
            for (int tx = 0; tx < targetSize; tx++) {
                int sx = Math.min((int) (tx * ratio), sourceSize - 1);
                int sy = Math.min((int) (ty * ratio), sourceSize - 1);
                target[ty * targetSize + tx] = source[sy * sourceSize + sx];
            }
        }
        return target;
    }

    /**
     * Downsample a biome grid from sourceSize to targetSize using nearest-neighbor.
     */
    private int[] downsampleBiomeGrid(int[] source, int sourceSize, int targetSize) {
        int[] target = new int[targetSize * targetSize];
        float ratio = (float) sourceSize / targetSize;

        for (int ty = 0; ty < targetSize; ty++) {
            for (int tx = 0; tx < targetSize; tx++) {
                int sx = Math.min((int) (tx * ratio), sourceSize - 1);
                int sy = Math.min((int) (ty * ratio), sourceSize - 1);
                target[ty * targetSize + tx] = source[sy * sourceSize + sx];
            }
        }
        return target;
    }

    private double heuristic(int x1, int y1, int x2, int y2) {
        return Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
    }

    private int toGrid(float worldCoord, int size, float worldScale) {
        float worldOffset = worldScale * 0.5f;
        return Math.round((worldCoord + worldOffset) / worldScale * size);
    }

    private float toWorld(int gridCoord, int size, float worldScale) {
        float worldOffset = worldScale * 0.5f;
        return (gridCoord / (float) size) * worldScale - worldOffset;
    }

    private static float worldSizeForScale(String scale) {
        return com.eonmux.chatllm.map.MapScaleUtils.worldSizeForScale(scale);
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private boolean isMainRoadEndpoint(MapLocation loc) {
        String type = loc.getLocationType();
        return "town".equals(type) || "city".equals(type) || "port".equals(type);
    }

    private String edgeKey(int a, int b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }
}
