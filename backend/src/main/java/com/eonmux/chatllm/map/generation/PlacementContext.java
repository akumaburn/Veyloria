package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;

/**
 * Contextual data gathered during location placement, used by DescriptionGenerator
 * to compose rich, context-aware descriptions.
 *
 * @param type               the location type definition
 * @param culture            the cultural region this location belongs to
 * @param biome              the biome at this location's position
 * @param elevation          normalized elevation (0.0-1.0)
 * @param nearestWaterDist   distance to nearest water in grid cells (Float.MAX_VALUE if none nearby)
 * @param nearRoad           whether an existing road passes nearby
 * @param nearbyLocationCount number of other locations within close proximity
 */
public record PlacementContext(
    LocationType type,
    Culture culture,
    Biome biome,
    float elevation,
    float nearestWaterDist,
    boolean nearRoad,
    int nearbyLocationCount
) {}
