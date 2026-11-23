package com.eonmux.chatllm.map.models;

import lombok.Builder;
import lombok.Data;

/**
 * A single waypoint along a travel journey route.
 * Each step of the journey corresponds to one waypoint.
 */
@Data
@Builder(toBuilder = true)
public class JourneyWaypoint {
    private final int stepIndex;
    private final float x;
    private final float y;
    private final float z;
    private final String biome;
    private final String nearbyLocationName;
    private final String nearbyLocationId;
    private final String nearbyLocationBiome;
    private final String headingDirection;
    private final String aheadBiome;
    private final boolean isDestination;
    /** Event type for this step: "none", "encounter", "discovery", "weather", "rest" */
    private final String eventType;
}
