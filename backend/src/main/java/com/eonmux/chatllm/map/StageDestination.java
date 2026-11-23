package com.eonmux.chatllm.map;

/**
 * A world-map location reachable from the current node through a gateway area
 * such as a gatehouse, dock, or trailhead.
 */
public record StageDestination(
        String locationId,
        String name,
        String locationType,
        String distanceLabel,
        boolean viaRoad
) {
}
