package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.map.models.TerrainData;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class TerrainGenerator {

    private static final float MAX_ELEVATION = 1.0f;
    private static final float MOUNTAIN_THRESHOLD = 0.65f;
    private static final float MAX_MOUNTAIN_HEIGHT = 0.82f;
    private static final float HILL_THRESHOLD = 0.45f;

    private static final float SHORELINE_SMOOTHING = 0.15f;
    private static final int MIN_LAKE_SIZE = 8;
    private static final float RIVER_WIDTH_VARIATION = 0.3f;
    private static final float MAX_ELEVATION_FACTOR = 0.30f;

    private static final int[][] DIRS_8 = {
            {-1,-1},{0,-1},{1,-1},
            {-1,0},       {1,0},
            {-1,1},{0,1},{1,1}
    };

    private static final int[][] DIRS_4 = {{0,1},{1,0},{0,-1},{-1,0}};

    public TerrainData generate(GenerationConfig config) {

        int size = config.getGridResolution();
        if (size <= 0) throw new IllegalArgumentException("gridResolution must be positive");

        long seed = config.getSeed();
        float targetWaterFraction = config.getWaterLevel();
        String theme = config.getTheme() != null ? config.getTheme() : "temperate";

        Random rng = new Random(seed);

        SimplexNoise elevationNoise = new SimplexNoise(seed);
        SimplexNoise moistureNoise = new SimplexNoise(seed + 31337);
        SimplexNoise shapeNoise = new SimplexNoise(seed + 99999);
        SimplexNoise riverNoise = new SimplexNoise(seed + 55555);

        int octaves = getOctaves(config.getMapScale());

        float[] heightmap = new float[size * size];
        int[] biomeGrid = new int[size * size];

        double centerX = 0.3 + rng.nextDouble() * 0.4;
        double centerY = 0.3 + rng.nextDouble() * 0.4;
        double falloffPower = 1.5 + rng.nextDouble() * 1.5;
        double distortionStrength = 0.1 + rng.nextDouble() * 0.25;

        float minH = Float.MAX_VALUE;
        float maxH = -Float.MAX_VALUE;

        // ===============================
        // PASS 1: Base Height Generation
        // ===============================
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {

                int idx = y * size + x;

                double nx = (double) x / size;
                double ny = (double) y / size;

                double wx = nx * 3.0 - 1.5;
                double wy = ny * 3.0 - 1.5;

                // Reduced amplitude → fewer extreme peaks
                double h = elevationNoise.hoskinsTerrain(wx * 20, wy * 20, octaves) * 0.5;

                double mask = islandMask(nx, ny, centerX, centerY,
                        falloffPower, distortionStrength, shapeNoise);

                // Reduce mask dominance → softer continental cores
                h = h * 0.7 + mask * 0.3;

                // Reduced detail noise
                double detail = elevationNoise.fbm2D(wx * 40, wy * 40,
                        2, 2.0, 0.5) * 0.05;

                h += detail;

                heightmap[idx] = (float) h;
                minH = Math.min(minH, heightmap[idx]);
                maxH = Math.max(maxH, heightmap[idx]);
            }
        }

        normalize(heightmap, minH, maxH);

        // ====================================
        // Bias Distribution Toward Low Elev.
        // ====================================
        for (int i = 0; i < heightmap.length; i++) {

            float h = heightmap[i];

            // Downward bias curve
            h = (float) Math.pow(h, 1.35f);

            if (h > HILL_THRESHOLD) {

                if (h > MOUNTAIN_THRESHOLD) {
                    float t = (h - MOUNTAIN_THRESHOLD) /
                              (1.0f - MOUNTAIN_THRESHOLD);
                    t = t * t * (3 - 2 * t);
                    h = MOUNTAIN_THRESHOLD +
                        t * (MAX_MOUNTAIN_HEIGHT - MOUNTAIN_THRESHOLD);
                } else {
                    float t = (h - HILL_THRESHOLD) /
                              (MOUNTAIN_THRESHOLD - HILL_THRESHOLD);
                    t = t * 0.6f + 0.4f * t * t;
                    h = HILL_THRESHOLD +
                        t * (MOUNTAIN_THRESHOLD - HILL_THRESHOLD);
                }
            }

            heightmap[i] = Math.min(h, MAX_MOUNTAIN_HEIGHT);
        }

        heightmap = smoothHeightmap(heightmap, size, 1);

        float waterLevel = computePercentile(heightmap, targetWaterFraction);

        applyShorelineSmoothing(heightmap, size, waterLevel);

        // Compute slope grid (normal.y per cell) — mirrors shader's TerrainNormal
        float worldSize = worldSizeForScale(config.getMapScale());
        float maxElevation = worldSize * MAX_ELEVATION_FACTOR;
        float[] slopeGrid = computeSlopeGrid(heightmap, size, worldSize);

        // ===============================
        // PASS 2: Biome Assignment
        // ===============================
        float halfWorld = worldSize * 0.5f;
        float waterWorldY = waterLevel * maxElevation;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {

                int idx = y * size + x;
                double elev = heightmap[idx];

                double nx = (double) x / size;
                double ny = (double) y / size;

                double moisture = moistureNoise.fbm2D(
                        nx * 3 + 100, ny * 3 + 100,
                        4, 2.0, 0.5);

                moisture = (moisture + 1.0) * 0.5;

                double temperature = 1.0 - Math.abs(ny - 0.5) * 1.2;
                temperature = Math.max(0.3, Math.min(1.0, temperature));

                float normalY = slopeGrid[idx];
                float pertNormalY = normalY;
                float worldX = (float) nx * worldSize - halfWorld;
                float worldZ = (float) ny * worldSize - halfWorld;
                float matPosX = worldX * 2.0f;
                float matPosZ = worldZ * 2.0f;

                // Compute tree contribution (shader's Map() adds treeCol * maxElevation * 0.01 to height)
                float treeCol = 0f;
                if (elev > waterLevel) {
                    float hWorld = heightmap[idx] * maxElevation;
                    treeCol = computeTreeCol(worldX, worldZ, hWorld, waterWorldY, maxElevation);
                    if (treeCol > 0f) {
                        pertNormalY = computePerturbedNormalY(
                                heightmap, x, y, size, worldX, worldZ,
                                normalY, maxElevation, worldSize);
                    }
                }

                biomeGrid[idx] = assignBiome(
                        elev, moisture, temperature,
                        waterLevel, theme, normalY,
                        pertNormalY, matPosX, matPosZ, treeCol
                ).getId();
            }
        }

        generateRivers(size, heightmap, biomeGrid, waterLevel, riverNoise);
        fillLakes(size, heightmap, biomeGrid, waterLevel);

        return TerrainData.builder()
                          .width(size)
                          .height(size)
                          .heightmap(heightmap)
                          .biomeGrid(biomeGrid)
                          .slopeGrid(slopeGrid)
                          .waterLevel(waterLevel)
                          .seed(seed)
                          .mapScale(config.getMapScale())
                          .theme(config.getTheme() != null ? config.getTheme() : "temperate")
                          .build();
    }

    // =========================================================
    // Helper Methods
    // =========================================================

    private double islandMask(double nx, double ny, double cx, double cy,
                              double power, double distortionStrength,
                              SimplexNoise noise) {

        double dx = nx - cx;
        double dy = ny - cy;

        double distortion = noise.fbm2D(nx * 3, ny * 3,
                3, 2.0, 0.5) * distortionStrength;

        double dist = Math.sqrt(dx * dx + dy * dy) *
                      (1.0 + distortion * 0.5);

        return Math.max(0, 1.0 - Math.pow(dist * 1.3, power));
    }

    private void generateRivers(int size, float[] heightmap,
                                int[] biomeGrid,
                                float waterLevel,
                                SimplexNoise riverNoise) {

        int total = size * size;
        int[] flowAccum = new int[total];
        int[] flowDir = new int[total];
        Arrays.fill(flowDir, -1);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {

                int idx = y * size + x;
                if (heightmap[idx] <= waterLevel) continue;

                float current = heightmap[idx];
                float lowest = current;
                int lowestIdx = -1;

                for (int[] d : DIRS_8) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                        int nidx = ny * size + nx;
                        if (heightmap[nidx] < lowest) {
                            lowest = heightmap[nidx];
                            lowestIdx = nidx;
                        }
                    }
                }

                if (lowestIdx != -1 && lowest < current) {
                    flowDir[idx] = lowestIdx;
                }
            }
        }

        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) order[i] = i;

        Arrays.sort(order,
                (a, b) -> Float.compare(heightmap[b], heightmap[a]));

        for (int i = 0; i < total; i++) {
            if (heightmap[i] > waterLevel) flowAccum[i] = 1;
        }

        for (int idx : order) {
            if (heightmap[idx] <= waterLevel) continue;
            int to = flowDir[idx];
            if (to != -1 && heightmap[to] > waterLevel) {
                flowAccum[to] += flowAccum[idx];
            }
        }

        int riverThreshold = (int) (total * 0.0015);

        for (int i = 0; i < total; i++) {
            if (heightmap[i] > waterLevel &&
                flowAccum[i] >= riverThreshold) {
                biomeGrid[i] = Biome.RIVER.getId();
            }
        }
    }

    /**
     * Identifies inland bodies of water (not connected to the ocean) and marks
     * them as LAKE. Only operates on cells that are actually below waterLevel
     * (true water cells). Uses a flood-fill from map edges + deep water to find
     * ocean-connected shallow water, then marks the remainder as inland lakes.
     */
    private void fillLakes(int size, float[] heightmap,
                           int[] biomeGrid,
                           float waterLevel) {

        int total = size * size;
        boolean[] isOcean = new boolean[total];
        Queue<Integer> queue = new ArrayDeque<>();

        // Seed: edge-connected shallow-water and any deep-water on the map edge
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int idx = y * size + x;
                int biome = biomeGrid[idx];
                boolean isEdge = (x == 0 || x == size - 1 || y == 0 || y == size - 1);

                if (isEdge && (biome == Biome.DEEP_WATER.getId() ||
                               biome == Biome.SHALLOW_WATER.getId())) {
                    isOcean[idx] = true;
                    queue.add(idx);
                }
            }
        }

        // BFS: spread "ocean" through connected shallow-water and deep-water cells
        while (!queue.isEmpty()) {
            int current = queue.poll();
            int cx = current % size;
            int cy = current / size;
            for (int[] d : DIRS_4) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;
                int nidx = ny * size + nx;
                if (!isOcean[nidx] &&
                    (biomeGrid[nidx] == Biome.SHALLOW_WATER.getId() ||
                     biomeGrid[nidx] == Biome.DEEP_WATER.getId())) {
                    isOcean[nidx] = true;
                    queue.add(nidx);
                }
            }
        }

        // Any water cell NOT reached by ocean BFS is an inland lake
        for (int i = 0; i < total; i++) {
            int b = biomeGrid[i];
            if ((b == Biome.SHALLOW_WATER.getId() || b == Biome.DEEP_WATER.getId()) && !isOcean[i]) {
                biomeGrid[i] = Biome.LAKE.getId();
            }
        }
    }

    private void applyShorelineSmoothing(float[] heightmap,
                                         int size,
                                         float waterLevel) {

        float[] copy = heightmap.clone();

        for (int y = 1; y < size - 1; y++) {
            for (int x = 1; x < size - 1; x++) {

                int idx = y * size + x;

                if (heightmap[idx] <= waterLevel) continue;

                boolean nearWater = false;

                for (int[] d : DIRS_4) {
                    int nidx = (y + d[1]) * size + (x + d[0]);
                    if (heightmap[nidx] <= waterLevel) {
                        nearWater = true;
                        break;
                    }
                }

                if (nearWater &&
                    heightmap[idx] < waterLevel + SHORELINE_SMOOTHING) {

                    copy[idx] = waterLevel +
                                (heightmap[idx] - waterLevel) * 0.7f;
                }
            }
        }

        System.arraycopy(copy, 0, heightmap, 0, heightmap.length);
    }

    private void normalize(float[] arr, float min, float max) {
        float range = max - min;
        if (range < 0.00001f) range = 1f;
        for (int i = 0; i < arr.length; i++) {
            arr[i] = (arr[i] - min) / range;
        }
    }

    private float computePercentile(float[] arr, float frac) {
        float[] copy = Arrays.copyOf(arr, arr.length);
        Arrays.sort(copy);
        int idx = (int) Math.floor(frac * (copy.length - 1));
        idx = Math.max(0, Math.min(idx, copy.length - 1));
        return copy[idx];
    }

    private float[] smoothHeightmap(float[] src,
                                    int size,
                                    int radius) {

        float[] dst = new float[src.length];

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {

                float sum = 0;
                int count = 0;

                for (int ny = Math.max(0, y - radius);
                     ny <= Math.min(size - 1, y + radius); ny++) {

                    for (int nx = Math.max(0, x - radius);
                         nx <= Math.min(size - 1, x + radius); nx++) {

                        sum += src[ny * size + nx];
                        count++;
                    }
                }

                dst[y * size + x] = sum / count;
            }
        }

        return dst;
    }

    /**
     * Computes per-cell slope as normal.y (0 = cliff, 1 = flat), mirroring the shader's
     * TerrainNormal function. Uses finite differences on the heightmap to compute the
     * gradient, then derives the Y component of the surface normal.
     */
    private float[] computeSlopeGrid(float[] heightmap, int size, float worldSize) {
        float[] slopeGrid = new float[size * size];
        // Scale factor must match the shader's normal computation.
        // The shader computes normals from Terrain2(p) = SampleHeightmap(p) * uMaxElevation,
        // where uMaxElevation = worldSize * MAX_ELEVATION_FACTOR.
        float heightScale = worldSize * MAX_ELEVATION_FACTOR;
        float cellSize = worldSize / size;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int idx = y * size + x;

                // Sample neighbors (clamped at edges)
                int xm = Math.max(x - 1, 0);
                int xp = Math.min(x + 1, size - 1);
                int ym = Math.max(y - 1, 0);
                int yp = Math.min(y + 1, size - 1);

                float hL = heightmap[y * size + xm] * heightScale;
                float hR = heightmap[y * size + xp] * heightScale;
                float hD = heightmap[ym * size + x] * heightScale;
                float hU = heightmap[yp * size + x] * heightScale;

                // Central differences for gradient
                float dx = (hR - hL) / (cellSize * (xp - xm));
                float dz = (hU - hD) / (cellSize * (yp - ym));

                // Normal.y = 1 / sqrt(dx^2 + dz^2 + 1)
                float normalY = (float) (1.0 / Math.sqrt(dx * dx + dz * dz + 1.0));
                slopeGrid[idx] = normalY;
            }
        }

        return slopeGrid;
    }

    // =========================================================
    //  Biome assignment — literal port of shader TerrainColour
    // =========================================================

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    /**
     * Computes the exact same blend factors as the shader's TerrainColour
     * (rayMarchShaders.ts lines 406-652), then picks the material with the
     * highest effective screen contribution as the biome.
     *
     * Shader applies materials as sequential mix() calls — each later material
     * partially overwrites the earlier ones. The effective weight of each
     * material in the final pixel is:
     *
     *   beach  = beachBlend
     *   snow   = snowBlend  * (1-beachBlend)
     *   grass  = grassBlend * (1-snowBlend) * (1-beachBlend)
     *   scree  = screeBlend * (1-grassBlend) * (1-snowBlend) * (1-beachBlend)
     *   cliff  = cliffBlend * (1-screeBlend) * (1-grassBlend) * (1-snowBlend) * (1-beachBlend)
     *   rock   = remainder
     *
     * @param normalY      original normal.y (used for cliff, scree, grass)
     * @param pertNormalY  tree-perturbed normal.y (used for snow, beach — shader
     *                     applies tree normal perturbation before these blends)
     * @param matPosX      Hoskins material-space X (worldX * 2.0)
     * @param matPosZ      Hoskins material-space Z (worldZ * 2.0)
     * @param treeCol      tree contribution value [0,1] from computeTreeCol(),
     *                     used to adjust matPosY to match shader's height calculation
     */
    private Biome assignBiome(double elevation,
                              double moisture,
                              double temperature,
                              float waterLevel,
                              String theme,
                              float normalY,
                              float pertNormalY,
                              float matPosX,
                              float matPosZ,
                              float treeCol) {

        // ---- Water (handled by RenderWater, not TerrainColour) ----
        if (elevation < waterLevel * 0.6) return Biome.DEEP_WATER;
        if (elevation < waterLevel) return Biome.SHALLOW_WATER;

        // Shader's Map() adds treeCol * maxElevation * 0.01 to terrain height,
        // and TerrainColour uses this height for matPos.y. We must include
        // tree contribution to match: normalized tree height = treeCol * 0.01
        float adjustedElevation = (float) elevation + treeCol * 0.01f;
        float landHeight = (adjustedElevation - waterLevel) / (1.0f - waterLevel);
        float matPosY = landHeight * 280.0f;

        // ---- 1. Cliff blend (shader line 437) ----
        float cliffBlend = 0f;
        if (normalY < 0.5f) {
            float c = (0.5f - normalY) * 4.0f;
            cliffBlend = clamp01(c * c);
            cliffBlend = Math.max(cliffBlend, 0.1f); // shader clamp min
        }

        // ---- 2. Scree blend (shader line 465) ----
        float screeBlend = 0f;
        if (normalY >= 0.50f && normalY < 0.68f) {
            screeBlend = smoothstep(0.50f, 0.56f, normalY)
                       * smoothstep(0.68f, 0.60f, normalY) * 0.7f;
        }

        // ---- 3. Grass blend (shader line 476) ----
        float grassBlend = 0f;
        if (matPosY < 78.0f && normalY > 0.65f) {
            grassBlend = clamp01((normalY - 0.65f) * 1.3f * (78.0f - matPosY) * 0.06f);
        }

        // ---- 4. Snow blend (shader line 565) ----
        // Uses pertNormalY (tree-perturbed) + noise offset, wind streaks, rock exposure
        float snowThreshold = switch (theme) {
            case "desert"   -> 180f;
            case "arctic"   ->  30f;
            case "tropical" -> 220f;
            default         ->  80f;
        };
        float snowBlend = 0f;
        if (matPosY > snowThreshold && pertNormalY > 0.35f) {
            // Noise offset matching shader (Noise(matPos.xz * 0.1) * 20.0)
            float noiseOffset = (float) hoskinsNoise(matPosX * 0.1, matPosZ * 0.1) * 20.0f;
            float elevFactor = clamp01((matPosY - snowThreshold - noiseOffset) * 0.04f);
            float slopeFactor = smoothstep(0.35f, 0.65f, pertNormalY);
            float snow = elevFactor * slopeFactor;

            // Wind-blown streaks (shader line 577)
            float windDotA = (matPosX * 0.7f + matPosZ * 0.7f) * 0.15f;
            float windDotB = (-matPosX * 0.7f + matPosZ * 0.7f) * 0.6f;
            float windStreak = (float) hoskinsNoise(windDotA, windDotB) * 0.3f + 0.7f;
            snow *= windStreak;

            // Exposed rock patches on steeper ground (shader line 583)
            float rockExposure = smoothstep(0.65f, 0.45f, pertNormalY)
                               * (float) hoskinsNoise(matPosX * 0.5, matPosZ * 0.5) * 0.5f;
            snow = Math.max(snow - rockExposure, 0f);

            snowBlend = snow;
        }

        // ---- 5. Beach blend (shader line 602) ----
        // Uses pertNormalY (tree-perturbed, matching shader order)
        float beachBlend = 0f;
        if (matPosY < 8.0f && pertNormalY > 0.4f) {
            float flatness = smoothstep(0.4f, 0.75f, pertNormalY);
            beachBlend = smoothstep(8.0f, 2.0f, matPosY)
                       * smoothstep(-1.0f, 1.0f, matPosY) * flatness;
        }

        // ---- Effective contribution of each material ----
        float beachW = beachBlend;
        float snowW  = snowBlend  * (1f - beachBlend);
        float grassW = grassBlend * (1f - snowBlend) * (1f - beachBlend);
        float screeW = screeBlend * (1f - grassBlend) * (1f - snowBlend) * (1f - beachBlend);
        float cliffW = cliffBlend * (1f - screeBlend) * (1f - grassBlend) * (1f - snowBlend) * (1f - beachBlend);
        float rockW  = (1f - cliffBlend) * (1f - screeBlend) * (1f - grassBlend) * (1f - snowBlend) * (1f - beachBlend);

        // ---- Pick dominant material ----
        //  0=rock, 1=cliff, 2=scree, 3=grass, 4=snow, 5=beach
        float maxW = rockW;  int dom = 0;
        if (cliffW > maxW) { maxW = cliffW; dom = 1; }
        if (screeW > maxW) { maxW = screeW; dom = 2; }
        if (grassW > maxW) { maxW = grassW; dom = 3; }
        if (snowW  > maxW) { maxW = snowW;  dom = 4; }
        if (beachW > maxW) { dom = 5; }

        return switch (dom) {
            case 5 -> Biome.BEACH;
            case 4 -> Biome.SNOW_PEAK;
            case 3 -> classifyVegetation(theme, moisture, temperature, landHeight);
            case 2 -> Biome.HILLS;
            default -> Biome.MOUNTAIN; // cliff or bare rock
        };
    }

    /**
     * Sub-classifies the grass/vegetation zone by climate.
     * The shader renders all grass identically — this adds gameplay variety.
     */
    private Biome classifyVegetation(String theme, double moisture,
                                     double temperature, float landHeight) {
        double adjMoisture = moisture;
        double adjTemp = temperature;
        switch (theme) {
            case "desert":
                adjMoisture *= 0.25;
                adjTemp = Math.min(1.0, adjTemp * 1.3);
                break;
            case "arctic":
                adjTemp *= 0.45;
                break;
            case "tropical":
                adjMoisture = Math.min(1.0, adjMoisture * 1.4 + 0.15);
                adjTemp = Math.min(1.0, adjTemp * 1.2);
                break;
        }

        if ("desert".equals(theme) && adjMoisture < 0.3) return Biome.DESERT;
        if ("arctic".equals(theme) && adjTemp < 0.4)      return Biome.TUNDRA;
        if (adjMoisture > 0.6)  return Biome.DENSE_FOREST;
        if (adjMoisture > 0.35) return Biome.FOREST;
        return Biome.GRASSLAND;
    }

    private int getOctaves(String scale) {
        if (scale == null) return 6;
        return switch (scale) {
            case "city" -> 4;
            case "continent" -> 7;
            default -> 6;
        };
    }

    private float worldSizeForScale(String scale) {
        return com.eonmux.chatllm.map.MapScaleUtils.worldSizeForScale(scale);
    }

    // =========================================================
    //  Hoskins value noise — exact port of shader's Hash12/Noise
    //  (rayMarchShaders.ts lines 83-105). Used for snow threshold
    //  noise, wind streaks, rock exposure, tree computation.
    // =========================================================

    private static double fractd(double x) {
        return x - Math.floor(x);
    }

    /** Port of shader's Hash12(vec2 p) — deterministic hash vec2 → float [0,1). */
    private static double hash12(double px, double py) {
        // vec3 p3 = fract(vec3(p.xyx) * 0.1031)
        double p3x = fractd(px * 0.1031);
        double p3y = fractd(py * 0.1031);
        double p3z = fractd(px * 0.1031); // p.xyx → z component is p.x
        // p3 += dot(p3, p3.yzx + 19.19)
        double d = p3x * (p3y + 19.19) + p3y * (p3z + 19.19) + p3z * (p3x + 19.19);
        p3x += d;
        p3y += d;
        p3z += d;
        // return fract((p3.x + p3.y) * p3.z)
        return fractd((p3x + p3y) * p3z);
    }

    /** Port of shader's Noise(vec2 x) — value noise with smoothstep interpolation. */
    private static double hoskinsNoise(double x, double y) {
        double px = Math.floor(x);
        double py = Math.floor(y);
        double fx = x - px;
        double fy = y - py;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fy = fy * fy * (3.0 - 2.0 * fy);
        double h00 = hash12(px, py);
        double h10 = hash12(px + 1.0, py);
        double h01 = hash12(px, py + 1.0);
        double h11 = hash12(px + 1.0, py + 1.0);
        double mixBot = h00 + (h10 - h00) * fx;
        double mixTop = h01 + (h11 - h01) * fx;
        return mixBot + (mixTop - mixBot) * fy;
    }

    /**
     * Computes the shader's treeLine * Trees(p) value for a world-space position.
     * Mirrors the Map() function in rayMarchShaders.ts (lines 176-187).
     */
    private float computeTreeCol(float worldX, float worldZ,
                                  float hWorld, float waterWorldY,
                                  float maxElevation) {
        if (hWorld <= waterWorldY) return 0f;
        float hForTree = (hWorld - waterWorldY) * 140.0f
                       / Math.max(maxElevation - waterWorldY, 1.0f);
        float ff = (float)(hoskinsNoise(worldX * 0.3, worldZ * 0.3)
                         + hoskinsNoise(worldX * 3.3, worldZ * 3.3) * 0.5);
        float treeLine = smoothstep(ff * 2.0f, ff * 2.0f + 10.0f, hForTree)
                       * smoothstep(ff * 3.0f + 70.0f, ff * 3.0f + 45.0f, hForTree);
        return (float) hoskinsNoise(worldX * 13.0, worldZ * 13.0) * treeLine;
    }

    /**
     * Computes the tree-perturbed normal.y used by the shader for snow/beach blends.
     * Trees add high-frequency noise to the normal's X/Z components (line 557),
     * which slightly reduces normal.y on tree-covered terrain.
     */
    private float computePerturbedNormalY(float[] heightmap,
                                           int x, int y, int size,
                                           float worldX, float worldZ,
                                           float normalY,
                                           float maxElevation, float worldSize) {
        // Reconstruct full 3D normal via finite differences
        int xm = Math.max(x - 1, 0);
        int xp = Math.min(x + 1, size - 1);
        int ym = Math.max(y - 1, 0);
        int yp = Math.min(y + 1, size - 1);
        float cellSize = worldSize / size;
        float hL = heightmap[y * size + xm] * maxElevation;
        float hR = heightmap[y * size + xp] * maxElevation;
        float hD = heightmap[ym * size + x] * maxElevation;
        float hU = heightmap[yp * size + x] * maxElevation;
        float dhdx = (hR - hL) / (cellSize * (xp - xm));
        float dhdz = (hU - hD) / (cellSize * (yp - ym));
        float nlen = (float) Math.sqrt(dhdx * dhdx + dhdz * dhdz + 1.0);
        float nx3 = -dhdx / nlen;
        float nz3 = -dhdz / nlen;

        // Tree normal perturbation (shader line 557)
        float matPosX = worldX * 2.0f;
        float matPosZ = worldZ * 2.0f;
        float pertX = (float)(hoskinsNoise(matPosX * 33.0, matPosZ * 33.0) - 0.5);
        float pertZ = (float)(hoskinsNoise(matPosX * 33.0 + 100.0, matPosZ * 33.0) - 0.5);
        float pnx = nx3 + pertX;
        float pnz = nz3 + pertZ;
        float plen = (float) Math.sqrt(pnx * pnx + normalY * normalY + pnz * pnz);
        return plen > 0f ? normalY / plen : normalY;
    }
}
