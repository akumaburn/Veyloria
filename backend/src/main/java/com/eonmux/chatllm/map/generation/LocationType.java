package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;

import java.util.Set;

/**
 * Defines a location type with placement constraints.
 * Used by LocationTypeRegistry and LocationPlacer for biome-aware placement.
 *
 * @param id              unique identifier (e.g. "town", "fortress", "shrine")
 * @param icon            display icon key
 * @param minElevation    minimum land fraction (-1.0 to 1.0; 0=water level, 1=peak, negative=below water)
 * @param maxElevation    maximum land fraction (-1.0 to 1.0; 0=water level, 1=peak, negative=below water)
 * @param preferredBiomes biomes where this type naturally spawns
 * @param forbiddenBiomes biomes where this type must never spawn
 * @param minSpacing      minimum distance from another location of the same type (world units)
 * @param weight          spawn weight — higher values mean more common
 */
public record LocationType(
    String id,
    String icon,
    float minElevation,
    float maxElevation,
    Set<Biome> preferredBiomes,
    Set<Biome> forbiddenBiomes,
    float minSpacing,
    int weight,
    float minSlope,
    float maxSlope
) {
    /**
     * Convenience constructor without slope constraints (defaults to 0.0–1.0 = any slope).
     */
    public LocationType(String id, String icon, float minElevation, float maxElevation,
                        Set<Biome> preferredBiomes, Set<Biome> forbiddenBiomes,
                        float minSpacing, int weight) {
        this(id, icon, minElevation, maxElevation, preferredBiomes, forbiddenBiomes,
             minSpacing, weight, 0.0f, 1.0f);
    }
}
