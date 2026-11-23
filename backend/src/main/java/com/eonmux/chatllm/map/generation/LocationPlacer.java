package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.map.models.TerrainData;
import com.eonmux.chatllm.models.MapLocation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Places roleplay-aware locations on terrain using the culture-aware procedural
 * generation engine. Combines CulturalRegionMapper, LocationTypeRegistry,
 * NameGenerator, and DescriptionGenerator to produce unique, immersive locations.
 *
 * Uses a spatial hash grid for O(1) spacing checks to support 100-600 locations.
 */
@Slf4j
public class LocationPlacer {

    private static final long SEED_OFFSET = 12345L;
    private static final int MAX_PLACEMENT_ATTEMPTS = 500;
    private static final int MAX_CONSECUTIVE_FAILURES = 300;
    private static final float NEARBY_COUNT_RADIUS = 20.0f;
    private static final float MIN_GLOBAL_SPACING = 6.0f;
    private static final int MIN_LOCATION_COUNT = 60;
    /** Probability of skipping a water-biome placement attempt (0.0-1.0). Higher = fewer water locations. */
    private static final float WATER_SKIP_PROBABILITY = 0.996f;
    private static final Set<Biome> WATER_BIOMES = Set.of(
        Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.LAKE, Biome.RIVER
    );

    public List<MapLocation> place(TerrainData terrain, GenerationConfig config,
                                    CulturalRegionMapper cultureMapper) {
        NameGenerator nameGenerator = new NameGenerator();

        Random rng = new Random(config.getSeed() + SEED_OFFSET);
        int width = terrain.getWidth();
        int height = terrain.getHeight();
        float[] heightmap = terrain.getHeightmap();
        int[] biomeGrid = terrain.getBiomeGrid();
        float waterLevel = terrain.getWaterLevel();

        float worldScale = worldSizeForScale(config.getMapScale());
        float worldOffset = worldScale * 0.5f;

        int totalLocations = getLocationCount(config, rng);
        List<MapLocation> locations = new ArrayList<>();
        float[] slopeGrid = terrain.getSlopeGrid();

        // Compute global min spacing dynamically from target location count.
        // Measure actual land fraction from the terrain (excludes DEEP_WATER)
        // so spacing adapts to water-heavy and land-heavy maps alike.
        long landCells = 0;
        for (int b : biomeGrid) {
            if (Biome.fromId(b) != Biome.DEEP_WATER) landCells++;
        }
        float usableFraction = Math.max((float) landCells / (width * height), 0.20f);
        float usableArea = worldScale * worldScale * usableFraction;
        float globalMinSpacing = Math.max(
            (float) Math.sqrt(usableArea / totalLocations) * 1.0f,
            MIN_GLOBAL_SPACING
        );

        // Use the largest type minSpacing as the spatial grid cell size
        float maxTypeSpacing = 0f;
        for (LocationType lt : LocationTypeRegistry.getAllTypes()) {
            if (lt.minSpacing() > maxTypeSpacing) maxTypeSpacing = lt.minSpacing();
        }
        float cellSize = Math.max(globalMinSpacing, maxTypeSpacing);

        log.info("Placement config: target={}, landFraction={}, globalMinSpacing={}, cellSize={}",
                 totalLocations, String.format("%.0f%%", usableFraction * 100),
                 String.format("%.2f", globalMinSpacing),
                 String.format("%.2f", cellSize));

        SpatialGrid globalGrid = new SpatialGrid(cellSize);
        Map<String, SpatialGrid> typeGrids = new HashMap<>();
        Map<String, SpatialGrid> categoryGrids = new HashMap<>();

        int consecutiveFailures = 0;

        for (int i = 0; i < totalLocations; i++) {
            MapLocation loc = placeOne(nameGenerator, rng, width, height, heightmap,
                                        biomeGrid, slopeGrid, waterLevel, cultureMapper,
                                        globalGrid, typeGrids, categoryGrids,
                                        globalMinSpacing, worldScale, worldOffset);
            if (loc != null) {
                locations.add(loc);
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("Placement exhaustion after {} consecutive failures at {}/{} locations — stopping early",
                             MAX_CONSECUTIVE_FAILURES, locations.size(), totalLocations);
                    break;
                }
            }
        }

        log.info("Placed {} of {} target locations", locations.size(), totalLocations);
        return locations;
    }

    private MapLocation placeOne(NameGenerator nameGenerator, Random rng,
                                  int width, int height,
                                  float[] heightmap, int[] biomeGrid,
                                  float[] slopeGrid, float waterLevel,
                                  CulturalRegionMapper cultureMapper,
                                  SpatialGrid globalGrid,
                                  Map<String, SpatialGrid> typeGrids,
                                  Map<String, SpatialGrid> categoryGrids,
                                  float globalMinSpacing,
                                  float worldScale, float worldOffset) {
        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            int gx = rng.nextInt(width);
            int gy = rng.nextInt(height);
            int idx = gy * width + gx;
            float elev = heightmap[idx];

            Biome biome = Biome.fromId(biomeGrid[idx]);

            // Never place on deep water
            if (biome == Biome.DEEP_WATER) continue;

            // Dramatically reduce density on water biomes (skip ~99.6% of attempts)
            if (WATER_BIOMES.contains(biome) && rng.nextFloat() < WATER_SKIP_PROBABILITY) continue;

            LocationType type = LocationTypeRegistry.getWeightedRandom(biome, rng);

            // Below water level: only allow types that explicitly prefer this water biome
            if (elev < waterLevel && !type.preferredBiomes().contains(biome)) continue;

            // Convert land-fraction elevation bounds to absolute heightmap values
            float absMinElev = landFractionToAbsolute(type.minElevation(), waterLevel);
            float absMaxElev = landFractionToAbsolute(type.maxElevation(), waterLevel);
            if (elev < absMinElev || elev > absMaxElev) continue;
            if (type.forbiddenBiomes().contains(biome)) continue;

            // Slope check — reject locations on terrain that doesn't match
            // the shader's visual material for this location type
            if (slopeGrid != null) {
                float slope = slopeGrid[idx];
                if (slope < type.minSlope() || slope > type.maxSlope()) continue;
            }

            float worldX = toWorldCoord(gx, width, worldScale, worldOffset);
            float worldY = toWorldCoord(gy, height, worldScale, worldOffset);

            if (!globalGrid.checkSpacing(worldX, worldY, globalMinSpacing)) continue;

            // Same-type spacing
            SpatialGrid typeGrid = typeGrids.get(type.id());
            if (typeGrid != null && !typeGrid.checkSpacing(worldX, worldY, type.minSpacing())) continue;

            // Category spacing — prevents semantically similar types from clustering
            // (e.g. port next to city, fortress next to castle)
            String category = LocationTypeRegistry.getCategory(type.id());
            float categorySpacing = LocationTypeRegistry.getCategorySpacing(category);
            if (category != null && categorySpacing > 0f) {
                SpatialGrid catGrid = categoryGrids.get(category);
                if (catGrid != null && !catGrid.checkSpacing(worldX, worldY, categorySpacing)) continue;
            }

            Culture culture = cultureMapper.getCulture(gx, gy);
            String name = nameGenerator.generate(culture, type, rng);
            String description = generateDescription(type, culture, biome, elev,
                                                      heightmap, width, height, gx, gy,
                                                      waterLevel, globalGrid,
                                                      worldX, worldY, rng);

            MapLocation loc = MapLocation.builder()
                .locationId(UUID.randomUUID())
                .name(name)
                .description(description)
                .x(worldX)
                .y(worldY)
                .z(elev)
                .locationType(type.id())
                .icon(type.icon())
                .isDiscovered(false)
                .build();

            globalGrid.insert(worldX, worldY);
            typeGrids.computeIfAbsent(type.id(), k -> new SpatialGrid(type.minSpacing()))
                     .insert(worldX, worldY);
            if (category != null && categorySpacing > 0f) {
                categoryGrids.computeIfAbsent(category, k -> new SpatialGrid(categorySpacing))
                             .insert(worldX, worldY);
            }

            return loc;
        }

        return null;
    }

    private String generateDescription(LocationType type, Culture culture, Biome biome,
                                         float elevation, float[] heightmap,
                                         int width, int height, int gx, int gy,
                                         float waterLevel, SpatialGrid globalGrid,
                                         float worldX, float worldY, Random rng) {
        float waterDist = nearestWaterDistance(heightmap, width, height, gx, gy, waterLevel);
        int nearbyCount = globalGrid.countNearby(worldX, worldY, NEARBY_COUNT_RADIUS);

        PlacementContext ctx = new PlacementContext(
            type, culture, biome, elevation, waterDist, false, nearbyCount
        );

        return DescriptionGenerator.generate(ctx, rng);
    }

    private int getLocationCount(GenerationConfig config, Random rng) {
        if (config.getLocationCount() > 0) {
            if (config.getLocationCount() < MIN_LOCATION_COUNT) {
                log.warn("Requested locationCount={} is below minimum {}, using minimum",
                         config.getLocationCount(), MIN_LOCATION_COUNT);
            }
            return Math.max(config.getLocationCount(), MIN_LOCATION_COUNT);
        }
        String scale = config.getMapScale() != null ? config.getMapScale() : "region";
        return switch (scale) {
            case "city" -> 200 + rng.nextInt(151);          // 200-350
            case "continent" -> 400 + rng.nextInt(201);     // 400-600
            default -> 100 + rng.nextInt(61);               // 100-160 (region)
        };
    }

    private float nearestWaterDistance(float[] heightmap, int width, int height,
                                         int cx, int cy, float waterLevel) {
        int searchRadius = Math.max(10, Math.min(width, height) / 20);
        float minDist = Float.MAX_VALUE;

        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int nx = cx + dx;
                int ny = cy + dy;
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                if (heightmap[ny * width + nx] < waterLevel) {
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < minDist) minDist = dist;
                }
            }
        }
        return minDist;
    }

    /**
     * Converts a land-fraction elevation to an absolute heightmap value.
     * Fraction 0 = waterLevel, 1 = peak (1.0), negative = below water.
     */
    static float landFractionToAbsolute(float fraction, float waterLevel) {
        if (fraction >= 0) {
            return waterLevel + fraction * (1.0f - waterLevel);
        } else {
            return waterLevel + fraction * waterLevel;
        }
    }

    private float toWorldCoord(int gridCoord, int gridSize, float worldScale, float worldOffset) {
        return (gridCoord / (float) gridSize) * worldScale - worldOffset;
    }

    private static float worldSizeForScale(String scale) {
        return com.eonmux.chatllm.map.MapScaleUtils.worldSizeForScale(scale);
    }

    // ==================== Spatial Hash Grid ====================

    /**
     * O(1) spatial hash grid for efficient spacing checks.
     * Uses a HashMap keyed by (cellX, cellY) packed into a long.
     */
    static class SpatialGrid {
        private final float cellSize;
        private final Map<Long, List<float[]>> cells = new HashMap<>();

        SpatialGrid(float cellSize) {
            this.cellSize = Math.max(cellSize, 0.01f);
        }

        void insert(float x, float y) {
            long key = cellKey(x, y);
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(new float[]{x, y});
        }

        boolean checkSpacing(float x, float y, float minDist) {
            float d2 = minDist * minDist;
            int cellRadius = (int) Math.ceil(minDist / cellSize);
            int cx = (int) Math.floor(x / cellSize);
            int cy = (int) Math.floor(y / cellSize);

            for (int dx = -cellRadius; dx <= cellRadius; dx++) {
                for (int dy = -cellRadius; dy <= cellRadius; dy++) {
                    long key = packKey(cx + dx, cy + dy);
                    List<float[]> cell = cells.get(key);
                    if (cell == null) continue;
                    for (float[] pos : cell) {
                        float px = pos[0] - x;
                        float py = pos[1] - y;
                        if (px * px + py * py < d2) return false;
                    }
                }
            }
            return true;
        }

        int countNearby(float x, float y, float radius) {
            float r2 = radius * radius;
            int cellRadius = (int) Math.ceil(radius / cellSize);
            int cx = (int) Math.floor(x / cellSize);
            int cy = (int) Math.floor(y / cellSize);
            int count = 0;

            for (int dx = -cellRadius; dx <= cellRadius; dx++) {
                for (int dy = -cellRadius; dy <= cellRadius; dy++) {
                    long key = packKey(cx + dx, cy + dy);
                    List<float[]> cell = cells.get(key);
                    if (cell == null) continue;
                    for (float[] pos : cell) {
                        float px = pos[0] - x;
                        float py = pos[1] - y;
                        if (px * px + py * py < r2) count++;
                    }
                }
            }
            return count;
        }

        private long cellKey(float x, float y) {
            return packKey((int) Math.floor(x / cellSize), (int) Math.floor(y / cellSize));
        }

        private static long packKey(int cx, int cy) {
            return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
        }
    }
}
