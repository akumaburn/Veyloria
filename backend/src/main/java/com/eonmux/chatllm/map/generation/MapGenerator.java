package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.map.models.GenerationResult;
import com.eonmux.chatllm.map.models.RoadSegment;
import com.eonmux.chatllm.map.models.TerrainData;
import com.eonmux.chatllm.models.MapLocation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Orchestrator for procedural map generation.
 * Runs TerrainGenerator -> LocationPlacer -> RoadGenerator.
 */
@Slf4j
public class MapGenerator {

    private static final Map<String, Integer> STARTING_TYPE_PRIORITY = Map.of(
        "town", 0, "village", 1, "city", 2, "camp", 3, "port", 4
    );
    private static final Set<String> SETTLEMENT_TYPES = STARTING_TYPE_PRIORITY.keySet();
    private static final int MIN_SETTLEMENTS_PER_REGION = 3;
    private static final int MAX_FORCE_PLACED = 30;
    private static final float FORCE_PLACE_MIN_SPACING = 6.0f;
    private static final int FORCE_PLACE_MAX_ATTEMPTS = 200;
    // World scale is now derived from mapScale via worldSizeForScale()

    private final TerrainGenerator terrainGenerator;
    private final LocationPlacer locationPlacer;
    private final RoadGenerator roadGenerator;

    public MapGenerator() {
        this.terrainGenerator = new TerrainGenerator();
        this.locationPlacer = new LocationPlacer();
        this.roadGenerator = new RoadGenerator();
    }

    public GenerationResult generate(GenerationConfig config) {
        log.info("Generating procedural map: scale={}, seed={}, grid={}x{}, theme={}",
                 config.getMapScale(), config.getSeed(), config.getGridResolution(),
                 config.getGridResolution(), config.getTheme());

        long start = System.currentTimeMillis();

        // Step 1: Generate terrain
        TerrainData terrain = terrainGenerator.generate(config);
        log.debug("Terrain generated in {}ms", System.currentTimeMillis() - start);

        // Step 1.5: Create cultural region mapper
        CulturalRegionMapper cultureMapper = new CulturalRegionMapper(terrain, config.getSeed());

        // Step 2: Place locations on terrain
        long step2Start = System.currentTimeMillis();
        List<MapLocation> rawLocations = locationPlacer.place(terrain, config, cultureMapper);
        log.debug("Placed {} locations in {}ms", rawLocations.size(), System.currentTimeMillis() - step2Start);

        // Step 2.25: Ensure each empire has at least 3 settlements
        rawLocations = ensureMinSettlementsPerRegion(rawLocations, cultureMapper, terrain, config.getSeed());

        // Step 2.5: Mark starting area as discovered (fog of war)
        StartingAreaResult startingArea = markStartingAreaDiscovered(rawLocations, config);
        List<MapLocation> locations = startingArea.locations;

        // Step 3: Generate roads between locations
        long step3Start = System.currentTimeMillis();
        List<RoadSegment> roads = roadGenerator.generate(terrain, locations);
        log.debug("Generated {} road segments in {}ms", roads.size(), System.currentTimeMillis() - step3Start);

        log.info("Map generation complete: {} locations, {} roads, {}ms total",
                 locations.size(), roads.size(), System.currentTimeMillis() - start);

        return GenerationResult.builder()
            .terrain(terrain)
            .locations(locations)
            .roads(roads)
            .culturalRegions(cultureMapper.getRegions())
            .startingLocationId(startingArea.startingLocationId)
            .build();
    }

    /** Result of marking the starting area as discovered, including the chosen starting location. */
    private record StartingAreaResult(List<MapLocation> locations, String startingLocationId) {}

    /**
     * Fraction of world size used as discovery radius around the starting settlement.
     * Scaled by map scale so large maps don't have tiny starting views.
     */
    private static final float STARTING_DISCOVERY_FRACTION = 0.10f;
    /**
     * Fraction of world size — settlements within this distance of center are
     * considered "near center" for starting location selection.
     */
    private static final float STARTING_CENTER_FRACTION = 0.15f;
    /** Absolute minimum/minimum discovery radius regardless of map scale. */
    private static final float MIN_DISCOVERY_RADIUS = 15f;

    /**
     * Find the best starting settlement and mark it + all locations within
     * a scale-aware radius as discovered.
     * All other locations remain undiscovered (fog of war).
     * Prefers settlements near the map center (0,0), with type priority as tiebreaker.
     * Returns both the updated location list and the starting location's ID.
     */
    private StartingAreaResult markStartingAreaDiscovered(List<MapLocation> locations,
                                                          GenerationConfig config) {
        // Compute scale-aware radii from the first location's coordinates
        float worldSize = 200f; // default — will be computed below
        for (MapLocation loc : locations) {
            float maxCoord = Math.max(Math.abs(loc.getX()), Math.abs(loc.getY()));
            float inferredSize = maxCoord * 2.2f;
            if (inferredSize > worldSize) worldSize = inferredSize;
        }
        float discoveryRadius = Math.max(worldSize * STARTING_DISCOVERY_FRACTION, MIN_DISCOVERY_RADIUS);
        float centerRadius = worldSize * STARTING_CENTER_FRACTION;

        if (locations.isEmpty()) return new StartingAreaResult(locations, null);

        List<String> preferred = config != null && config.getPreferredStartingTypes() != null
            ? config.getPreferredStartingTypes() : List.of();

        // A story about a dungeon opens at a dungeon, not at an arbitrary
        // settlement. Fall back to settlements only when the map holds none of
        // the preferred kinds of place.
        List<MapLocation> candidates = preferred.isEmpty()
            ? locations.stream()
                .filter(loc -> STARTING_TYPE_PRIORITY.containsKey(loc.getLocationType()))
                .toList()
            : locations.stream()
                .filter(loc -> preferred.contains(loc.getLocationType()))
                .toList();
        if (candidates.isEmpty() && !preferred.isEmpty()) {
            candidates = locations.stream()
                .filter(loc -> STARTING_TYPE_PRIORITY.containsKey(loc.getLocationType()))
                .toList();
        }

        MapLocation startLoc;
        if (candidates.isEmpty()) {
            // No suitable node — pick location closest to center
            startLoc = locations.stream()
                .min(Comparator.comparingDouble(loc ->
                    loc.getX() * loc.getX() + loc.getY() * loc.getY()))
                .orElse(locations.get(0));
        } else {
            // First try: candidates within center radius, sorted by story fit
            float cr2 = centerRadius * centerRadius;
            List<MapLocation> nearCenter = candidates.stream()
                .filter(loc -> loc.getX() * loc.getX() + loc.getY() * loc.getY() <= cr2)
                .sorted(Comparator.<MapLocation>comparingInt(loc ->
                        startingPriority(loc, preferred))
                    .thenComparingDouble(loc ->
                        loc.getX() * loc.getX() + loc.getY() * loc.getY()))
                .toList();

            if (!nearCenter.isEmpty()) {
                startLoc = nearCenter.get(0);
            } else {
                // No candidates near center: pick the best-fitting closest one
                startLoc = candidates.stream()
                    .min(Comparator.<MapLocation>comparingInt(loc ->
                            startingPriority(loc, preferred))
                        .thenComparingDouble(loc ->
                            loc.getX() * loc.getX() + loc.getY() * loc.getY()))
                    .orElse(candidates.get(0));
            }
        }

        float sx = startLoc.getX();
        float sy = startLoc.getY();
        float r2 = discoveryRadius * discoveryRadius;

        List<MapLocation> result = new ArrayList<>(locations.size());
        int discoveredCount = 0;
        for (MapLocation loc : locations) {
            float dx = loc.getX() - sx;
            float dy = loc.getY() - sy;
            boolean withinRadius = (dx * dx + dy * dy) <= r2;
            if (withinRadius) {
                result.add(loc.toBuilder().isDiscovered(true).build());
                discoveredCount++;
            } else {
                result.add(loc);
            }
        }

        String startingId = startLoc.getLocationId() != null ? startLoc.getLocationId().toString() : null;
        log.debug("Fog of war: {} of {} locations discovered at start (near '{}', id={})",
                 discoveredCount, locations.size(), startLoc.getName(), startingId);
        return new StartingAreaResult(result, startingId);
    }

    private static int startingPriority(MapLocation location, List<String> preferred) {
        if (!preferred.isEmpty()) {
            int index = preferred.indexOf(location.getLocationType());
            if (index >= 0) return index;
        }
        return STARTING_TYPE_PRIORITY.getOrDefault(location.getLocationType(), 99);
    }

    /* ==================== Per-Empire Settlement Enforcement ==================== */    /**
     * Ensures each cultural region has at least {@link #MIN_SETTLEMENTS_PER_REGION} settlements.
     * Counts existing settlements per culture, then force-places new ones in deficit regions.
     */
    private List<MapLocation> ensureMinSettlementsPerRegion(
            List<MapLocation> locations, CulturalRegionMapper cultureMapper,
            TerrainData terrain, long seed) {

        int width = terrain.getWidth();
        int height = terrain.getHeight();
        float worldScale = worldSizeForScale(terrain.getMapScale());
        List<CulturalRegion> regions = cultureMapper.getRegions();

        // Pre-seed name generator with existing names to prevent duplicates
        NameGenerator nameGenerator = new NameGenerator();
        for (MapLocation loc : locations) {
            nameGenerator.registerExisting(loc.getName());
        }

        // Count settlements per culture
        Map<Culture, Integer> settlementCounts = new EnumMap<>(Culture.class);
        for (Culture c : Culture.values()) {
            settlementCounts.put(c, 0);
        }
        for (MapLocation loc : locations) {
            if (SETTLEMENT_TYPES.contains(loc.getLocationType())) {
                int gx = toGrid(loc.getX(), width, worldScale);
                int gy = toGrid(loc.getY(), height, worldScale);
                Culture culture = cultureMapper.getCulture(gx, gy);
                settlementCounts.merge(culture, 1, Integer::sum);
            }
        }

        Random rng = new Random(seed + 99999L);
        List<MapLocation> added = new ArrayList<>();
        int totalForced = 0;

        for (CulturalRegion region : regions) {
            int count = settlementCounts.getOrDefault(region.culture(), 0);
            int deficit = MIN_SETTLEMENTS_PER_REGION - count;

            if (deficit <= 0) continue;

            log.info("Empire '{}' ({}) has {} settlements, need {} more",
                     region.name(), region.culture(), count, deficit);

            for (int i = 0; i < deficit && totalForced < MAX_FORCE_PLACED; i++) {
                MapLocation forced = forceSettlement(region, terrain, cultureMapper,
                                                      nameGenerator, locations, added, rng);
                if (forced != null) {
                    added.add(forced);
                    totalForced++;
                    settlementCounts.merge(region.culture(), 1, Integer::sum);
                }
            }
        }

        if (!added.isEmpty()) {
            log.info("Force-placed {} settlements to meet per-empire minimums", added.size());
            List<MapLocation> result = new ArrayList<>(locations.size() + added.size());
            result.addAll(locations);
            result.addAll(added);
            return result;
        }

        return locations;
    }

    /**
     * Attempts to force-place a single settlement within a cultural region.
     * Searches random positions within the region's radius, respecting biome/elevation
     * constraints, culture membership, and minimum spacing.
     */
    private MapLocation forceSettlement(CulturalRegion region, TerrainData terrain,
                                         CulturalRegionMapper cultureMapper,
                                         NameGenerator nameGenerator,
                                         List<MapLocation> existing, List<MapLocation> added,
                                         Random rng) {
        int width = terrain.getWidth();
        int height = terrain.getHeight();
        float worldScale = worldSizeForScale(terrain.getMapScale());
        float waterLevel = terrain.getWaterLevel();
        float[] heightmap = terrain.getHeightmap();
        int[] biomeGrid = terrain.getBiomeGrid();

        for (int attempt = 0; attempt < FORCE_PLACE_MAX_ATTEMPTS; attempt++) {
            // Random position within region's radius (grid coords)
            float angle = rng.nextFloat() * (float) (2 * Math.PI);
            float dist = rng.nextFloat() * region.radius();
            int gx = region.centerX() + Math.round((float) Math.cos(angle) * dist);
            int gy = region.centerY() + Math.round((float) Math.sin(angle) * dist);

            if (gx < 0 || gx >= width || gy < 0 || gy >= height) continue;

            int idx = gy * width + gx;
            Biome biome = Biome.fromId(biomeGrid[idx]);
            float elev = heightmap[idx];

            // Skip deep water
            if (biome == Biome.DEEP_WATER) continue;

            // Verify this cell belongs to the target culture
            Culture cellCulture = cultureMapper.getCulture(gx, gy);
            if (cellCulture != region.culture()) continue;

            // Pick settlement type by biome
            String typeId = pickSettlementType(biome, rng);
            Optional<LocationType> typeOpt = LocationTypeRegistry.findById(typeId);
            if (typeOpt.isEmpty()) continue;
            LocationType type = typeOpt.get();

            // Check elevation constraints (convert land-fraction to absolute, same as LocationPlacer)
            float absMinElev = LocationPlacer.landFractionToAbsolute(type.minElevation(), terrain.getWaterLevel());
            float absMaxElev = LocationPlacer.landFractionToAbsolute(type.maxElevation(), terrain.getWaterLevel());
            if (elev < absMinElev || elev > absMaxElev) continue;
            if (type.forbiddenBiomes().contains(biome)) continue;

            float worldX = toWorld(gx, width, worldScale);
            float worldY = toWorld(gy, height, worldScale);

            // Check minimum spacing — use the larger of FORCE_PLACE_MIN_SPACING and type's own spacing
            float minDist = Math.max(FORCE_PLACE_MIN_SPACING, type.minSpacing());
            if (!checkSpacing(worldX, worldY, minDist, existing, added)) continue;

            // Also enforce category spacing against same-category locations
            String category = LocationTypeRegistry.getCategory(type.id());
            float categorySpacing = LocationTypeRegistry.getCategorySpacing(category);
            if (category != null && categorySpacing > 0f) {
                if (!checkCategorySpacing(worldX, worldY, categorySpacing, category, existing, added)) continue;
            }

            String name = nameGenerator.generate(region.culture(), type, rng);

            float waterDist = nearestWaterDistance(heightmap, width, height, gx, gy, waterLevel);
            PlacementContext ctx = new PlacementContext(
                type, region.culture(), biome, elev, waterDist, false, 0
            );
            String description = DescriptionGenerator.generate(ctx, rng);

            return MapLocation.builder()
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
        }

        log.warn("Failed to force-place settlement for empire '{}' after {} attempts",
                 region.name(), FORCE_PLACE_MAX_ATTEMPTS);
        return null;
    }

    /** Selects an appropriate settlement type based on the biome. */
    private static String pickSettlementType(Biome biome, Random rng) {
        return switch (biome) {
            case BEACH, SHALLOW_WATER -> "port";
            case SNOW_PEAK, TUNDRA, DESERT, SWAMP, MOUNTAIN -> "camp";
            default -> rng.nextFloat() < 0.6f ? "village" : "town";
        };
    }

    /** Checks minimum spacing against both existing and newly-added locations. */
    private static boolean checkSpacing(float x, float y, float minDist,
                                         List<MapLocation> existing, List<MapLocation> added) {
        float d2 = minDist * minDist;
        for (MapLocation loc : existing) {
            float dx = loc.getX() - x;
            float dy = loc.getY() - y;
            if (dx * dx + dy * dy < d2) return false;
        }
        for (MapLocation loc : added) {
            float dx = loc.getX() - x;
            float dy = loc.getY() - y;
            if (dx * dx + dy * dy < d2) return false;
        }
        return true;
    }

    /** Checks category spacing — only compares against locations of the same semantic category. */
    private static boolean checkCategorySpacing(float x, float y, float minDist, String category,
                                                 List<MapLocation> existing, List<MapLocation> added) {
        float d2 = minDist * minDist;
        for (MapLocation loc : existing) {
            String locCat = LocationTypeRegistry.getCategory(loc.getLocationType());
            if (!category.equals(locCat)) continue;
            float dx = loc.getX() - x;
            float dy = loc.getY() - y;
            if (dx * dx + dy * dy < d2) return false;
        }
        for (MapLocation loc : added) {
            String locCat = LocationTypeRegistry.getCategory(loc.getLocationType());
            if (!category.equals(locCat)) continue;
            float dx = loc.getX() - x;
            float dy = loc.getY() - y;
            if (dx * dx + dy * dy < d2) return false;
        }
        return true;
    }

    /** Converts a world coordinate back to grid coordinate. */
    private static int toGrid(float worldCoord, int gridSize, float worldScale) {
        float worldOffset = worldScale * 0.5f;
        return Math.round((worldCoord + worldOffset) / worldScale * gridSize);
    }

    /** Converts a grid coordinate to world coordinate. Same math as LocationPlacer. */
    private static float toWorld(int gridCoord, int gridSize, float worldScale) {
        float worldOffset = worldScale * 0.5f;
        return (gridCoord / (float) gridSize) * worldScale - worldOffset;
    }

    private static float worldSizeForScale(String scale) {
        return com.eonmux.chatllm.map.MapScaleUtils.worldSizeForScale(scale);
    }

    /** Computes minimum Euclidean distance from (cx, cy) to any water cell, clamped to a search radius. */
    private static float nearestWaterDistance(float[] heightmap, int width, int height,
                                                int cx, int cy, float waterLevel) {
        int searchRadius = Math.max(10, Math.min(width, height) / 20);
        float minDist = Float.MAX_VALUE;
        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int nx = cx + dx;
                int ny = cy + dy;
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                if (heightmap[ny * width + nx] < waterLevel) {
                    minDist = Math.min(minDist, (float) Math.sqrt(dx * dx + dy * dy));
                }
            }
        }
        return minDist;
    }
}
