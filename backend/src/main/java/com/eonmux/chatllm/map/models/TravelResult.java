package com.eonmux.chatllm.map.models;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of a travel calculation or execution between two map locations.
 */
@Data
@Builder
public class TravelResult {
    private final float distance;
    private final float effectiveCost;
    private final int timePeriodsRequired;
    private final boolean usesRoad;
    private final String roadType;
    private final String dominantBiome;
    private final String fromLocationId;
    private final String toLocationId;
    private final String fromLocationName;
    private final String toLocationName;
    @Builder.Default
    private final List<String> discoveredLocationIds = List.of();
    /** Road waypoints for path-following (null if no road). */
    @org.eclipse.microprofile.graphql.Ignore
    private final List<float[]> roadWaypoints;
}
