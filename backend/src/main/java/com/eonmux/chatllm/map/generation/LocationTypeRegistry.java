package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;

import java.util.*;

/**
 * Registry of all location types with placement constraints and spawn weights.
 * Provides biome-filtered lookups and weighted random selection for the LocationPlacer.
 *
 * <p>Category-based spacing prevents semantically similar types from clustering
 * (e.g. two ports on the same stretch of coast, fortress next to castle).
 */
public final class LocationTypeRegistry {

    // Semantic categories — types within the same category enforce mutual minimum spacing.
    private static final Map<String, String> TYPE_CATEGORY = Map.ofEntries(
        Map.entry("city",     "major_settlement"),
        Map.entry("town",     "major_settlement"),
        Map.entry("port",     "major_settlement"),
        Map.entry("fortress", "fortification"),
        Map.entry("castle",   "fortification"),
        Map.entry("keep",     "fortification"),
        Map.entry("dungeon",  "underground"),
        Map.entry("cave",     "underground"),
        Map.entry("mine",     "underground"),
        Map.entry("shrine",   "sacred"),
        Map.entry("temple",   "sacred")
    );

    private static final Map<String, Float> CATEGORY_SPACING = Map.of(
        "major_settlement", 18.0f,
        "fortification",    14.0f,
        "underground",      10.0f,
        "sacred",           10.0f
    );

    private static final List<LocationType> ALL_TYPES = List.of(
        // Spacing values sized for a 200x200 world supporting 200-1000 locations.
        // Elevation ranges in land-fraction space: 0 = water level, 1 = peak, negative = below water.
        // LocationPlacer converts to absolute heightmap values using the effective water level.
        //
        // Slope values: minSlope/maxSlope correspond to terrain normal.y (0=cliff, 1=flat).
        // These align with the shader's TerrainColour material thresholds:
        //   cliff/rock: normal.y < 0.50, scree: 0.50-0.68, grass: > 0.65, beach: > 0.40
        //
        // Settlements: flat lowlands (require grass-zone slopes)
        new LocationType("town",           "town",     0.05f, 0.40f,
            Set.of(Biome.GRASSLAND, Biome.FOREST),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.LAKE, Biome.RIVER),
            8.0f, 18, 0.65f, 1.0f),
        new LocationType("city",           "city",     0.05f, 0.25f,
            Set.of(Biome.GRASSLAND),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.SWAMP, Biome.LAKE, Biome.RIVER),
            15.0f, 3, 0.65f, 1.0f),
        new LocationType("village",        "village",  0.05f, 0.50f,
            Set.of(Biome.GRASSLAND, Biome.FOREST, Biome.HILLS),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.LAKE, Biome.RIVER),
            6.0f, 20, 0.65f, 1.0f),
        // Military/defensive: allow moderate slopes
        new LocationType("fortress",       "fortress", 0.45f, 1.0f,
            Set.of(Biome.HILLS, Biome.MOUNTAIN),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.SWAMP, Biome.RIVER, Biome.LAKE),
            12.0f, 4, 0.50f, 1.0f),
        new LocationType("castle",         "castle",   0.12f, 0.75f,
            Set.of(Biome.HILLS, Biome.GRASSLAND, Biome.FOREST),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.RIVER, Biome.LAKE),
            14.0f, 4, 0.50f, 1.0f),
        new LocationType("keep",           "keep",     0.50f, 1.0f,
            Set.of(Biome.MOUNTAIN, Biome.HILLS),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.SWAMP, Biome.RIVER, Biome.LAKE),
            10.0f, 3, 0.50f, 1.0f),
        // Underground/hidden: allow steep terrain
        new LocationType("dungeon",        "dungeon",  0.20f, 0.85f,
            Set.of(Biome.MOUNTAIN, Biome.HILLS, Biome.DENSE_FOREST),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.RIVER, Biome.LAKE),
            8.0f, 5, 0.35f, 1.0f),
        new LocationType("cave",           "cave",     0.35f, 0.85f,
            Set.of(Biome.MOUNTAIN, Biome.HILLS),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.DESERT),
            7.0f, 6, 0.35f, 1.0f),
        new LocationType("mine",           "mine",     0.35f, 0.85f,
            Set.of(Biome.MOUNTAIN, Biome.HILLS),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.SWAMP, Biome.RIVER, Biome.LAKE),
            10.0f, 4, 0.35f, 1.0f),
        // Nature: require grass-zone slopes
        new LocationType("forest",         "forest",   0.05f, 0.45f,
            Set.of(Biome.FOREST, Biome.DENSE_FOREST),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.DESERT, Biome.TUNDRA),
            6.0f, 8, 0.65f, 1.0f),
        new LocationType("grove",          "grove",    0.05f, 0.35f,
            Set.of(Biome.FOREST),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.DESERT, Biome.SNOW_PEAK, Biome.TUNDRA),
            6.0f, 5, 0.65f, 1.0f),
        // Mountain: any slope
        new LocationType("mountain",       "mountain", 0.60f, 1.0f,
            Set.of(Biome.MOUNTAIN, Biome.SNOW_PEAK),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER),
            10.0f, 5, 0.0f, 1.0f),
        // Water-adjacent features: near water level
        new LocationType("lake",           "lake",    -0.80f, 0.05f,
            Set.of(Biome.SHALLOW_WATER, Biome.LAKE),
            Set.of(Biome.DEEP_WATER, Biome.DESERT, Biome.SNOW_PEAK),
            12.0f, 3, 0.0f, 1.0f),
        new LocationType("river_crossing", "river_crossing", -0.30f, 0.15f,
            Set.of(Biome.RIVER),
            Set.of(Biome.DEEP_WATER, Biome.MOUNTAIN, Biome.SNOW_PEAK),
            12.0f, 3, 0.0f, 1.0f),
        // Sacred/spiritual: moderate to flat
        new LocationType("shrine",         "shrine",   0.10f, 0.75f,
            Set.of(Biome.FOREST, Biome.HILLS, Biome.GRASSLAND, Biome.MOUNTAIN),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.LAKE, Biome.RIVER),
            7.0f, 5, 0.50f, 1.0f),
        new LocationType("temple",         "temple",   0.35f, 0.80f,
            Set.of(Biome.HILLS, Biome.MOUNTAIN, Biome.GRASSLAND),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.SWAMP, Biome.LAKE, Biome.RIVER),
            12.0f, 3, 0.55f, 1.0f),
        // Miscellaneous
        new LocationType("ruins",          "ruins",    0.05f, 0.60f,
            Set.of(Biome.GRASSLAND, Biome.FOREST, Biome.HILLS, Biome.DESERT, Biome.TUNDRA),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.LAKE, Biome.RIVER),
            6.0f, 7, 0.40f, 1.0f),
        new LocationType("tower",          "tower",    0.12f, 0.80f,
            Set.of(Biome.HILLS, Biome.GRASSLAND, Biome.FOREST),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.SWAMP, Biome.RIVER, Biome.LAKE),
            9.0f, 4, 0.50f, 1.0f),
        new LocationType("port",           "port",    -0.30f, 0.08f,
            Set.of(Biome.BEACH, Biome.SHALLOW_WATER),
            Set.of(Biome.DEEP_WATER, Biome.MOUNTAIN, Biome.SNOW_PEAK, Biome.DESERT),
            14.0f, 3, 0.40f, 1.0f),
        new LocationType("camp",           "camp",     0.05f, 0.50f,
            Set.of(Biome.GRASSLAND, Biome.FOREST, Biome.HILLS, Biome.DESERT, Biome.BEACH, Biome.TUNDRA),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.LAKE, Biome.RIVER),
            5.0f, 10, 0.65f, 1.0f),
        new LocationType("oasis",          "oasis",    0.05f, 0.25f,
            Set.of(Biome.DESERT),
            Set.of(Biome.DEEP_WATER, Biome.SHALLOW_WATER, Biome.FOREST, Biome.DENSE_FOREST, Biome.SNOW_PEAK, Biome.TUNDRA),
            10.0f, 3, 0.55f, 1.0f),
        new LocationType("swamp",          "swamp",   -0.30f, 0.08f,
            Set.of(Biome.SWAMP),
            Set.of(Biome.DEEP_WATER, Biome.MOUNTAIN, Biome.SNOW_PEAK, Biome.DESERT),
            7.0f, 4, 0.0f, 1.0f)
    );

    private static final Map<Biome, List<LocationType>> BIOME_INDEX;

    static {
        Map<Biome, List<LocationType>> index = new EnumMap<>(Biome.class);
        for (Biome b : Biome.values()) {
            List<LocationType> matching = new ArrayList<>();
            for (LocationType lt : ALL_TYPES) {
                if (lt.preferredBiomes().contains(b) && !lt.forbiddenBiomes().contains(b)) {
                    matching.add(lt);
                }
            }
            index.put(b, List.copyOf(matching));
        }
        BIOME_INDEX = Map.copyOf(index);
    }

    private LocationTypeRegistry() {}

    public static List<LocationType> getAllTypes() {
        return ALL_TYPES;
    }

    /** Returns all location types that prefer the given biome. */
    public static List<LocationType> getTypesForBiome(Biome biome) {
        return BIOME_INDEX.getOrDefault(biome, List.of());
    }

    /** Selects a random location type weighted by spawn weight, filtered to the given biome. */
    public static LocationType getWeightedRandom(Biome biome, Random rng) {
        List<LocationType> candidates = getTypesForBiome(biome);
        if (candidates.isEmpty()) {
            // Fallback: pick from all land types
            candidates = ALL_TYPES.stream()
                .filter(lt -> !lt.forbiddenBiomes().contains(biome))
                .toList();
        }
        if (candidates.isEmpty()) {
            return ALL_TYPES.get(0); // ultimate fallback: town
        }

        int totalWeight = candidates.stream().mapToInt(LocationType::weight).sum();
        if (totalWeight <= 0) {
            return ALL_TYPES.get(0); // fallback if all candidates have zero weight
        }
        int roll = rng.nextInt(totalWeight);
        int cumulative = 0;
        for (LocationType lt : candidates) {
            cumulative += lt.weight();
            if (roll < cumulative) {
                return lt;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** Finds a location type by its id string. */
    public static Optional<LocationType> findById(String id) {
        return ALL_TYPES.stream().filter(lt -> lt.id().equals(id)).findFirst();
    }

    /** Returns the semantic category for a type, or null if uncategorised. */
    public static String getCategory(String typeId) {
        return TYPE_CATEGORY.get(typeId);
    }

    /** Returns the minimum spacing for a category, or 0 if unknown. */
    public static float getCategorySpacing(String category) {
        return category != null ? CATEGORY_SPACING.getOrDefault(category, 0f) : 0f;
    }
}
