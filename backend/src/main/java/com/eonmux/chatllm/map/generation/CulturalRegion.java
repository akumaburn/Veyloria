package com.eonmux.chatllm.map.generation;

/**
 * Represents a cultural influence zone on the map.
 * Used by CulturalRegionMapper to assign cultures to grid positions via Voronoi partitioning.
 *
 * @param culture the dominant culture of this region
 * @param centerX grid x-coordinate of the cultural center
 * @param centerY grid y-coordinate of the cultural center
 * @param radius  approximate influence radius in grid cells
 * @param name    generated empire/kingdom name for display
 */
public record CulturalRegion(Culture culture, int centerX, int centerY, float radius, String name) {}
