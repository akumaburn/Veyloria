package com.eonmux.chatllm.map;

import com.eonmux.chatllm.map.models.JourneyWaypoint;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stateless utility that rolls biome-weighted random events for each step of a journey.
 * Called once before the journey starts to pre-roll all event seeds.
 */
public final class TravelEventRoller {

    private TravelEventRoller() {}

    private static final String NONE = "none";
    private static final String ENCOUNTER = "encounter";
    private static final String DISCOVERY = "discovery";
    private static final String WEATHER = "weather";
    private static final String REST = "rest";

    private static final String[] EVENT_TYPES = { NONE, ENCOUNTER, DISCOVERY, WEATHER, REST };

    // Base weights: [none, encounter, discovery, weather, rest]
    private static final int[] BASE_WEIGHTS = { 58, 15, 10, 10, 7 };

    // Biome modifiers: Map<biome, Map<eventType, modifier>>
    private static final Map<String, Map<String, Integer>> BIOME_MODIFIERS = Map.ofEntries(
        Map.entry("GRASSLAND", Map.of(NONE, 5)),
        Map.entry("FOREST", Map.of(ENCOUNTER, 5, DISCOVERY, 3)),
        Map.entry("DENSE_FOREST", Map.of(ENCOUNTER, 10)),
        Map.entry("HILLS", Map.of(DISCOVERY, 3)),
        Map.entry("MOUNTAIN", Map.of(WEATHER, 5)),
        Map.entry("SNOW_PEAK", Map.of(WEATHER, 8)),
        Map.entry("DESERT", Map.of(WEATHER, 5, REST, 5)),
        Map.entry("SWAMP", Map.of(ENCOUNTER, 5)),
        Map.entry("TUNDRA", Map.of(WEATHER, 8)),
        Map.entry("BEACH", Map.of(NONE, 3))
    );

    // Road modifiers (applied when usesRoad is true)
    private static final Map<String, Integer> ROAD_MODIFIERS = Map.of(
        NONE, 10,
        ENCOUNTER, -5
    );

    /**
     * Assigns an event type to each waypoint in the journey.
     * Step 0 (departure) and the last step (arrival) always get "none".
     * At most 1 encounter per journey.
     *
     * @param waypoints the journey waypoints (mutated in place via builder pattern — caller
     *                  should rebuild with the returned list)
     * @param usesRoad  whether the journey follows a road
     * @param seed      random seed for deterministic rolling
     * @return new list of waypoints with eventType assigned
     */
    public static List<JourneyWaypoint> rollEvents(List<JourneyWaypoint> waypoints,
                                                     boolean usesRoad, long seed) {
        Random rng = new Random(seed);
        int totalSteps = waypoints.size();
        boolean[] encounterUsed = { false };

        return waypoints.stream().map(wp -> {
            if (wp.getStepIndex() == 0 || wp.isDestination()) {
                return wp.toBuilder().eventType(NONE).build();
            }

            String event = rollSingleEvent(wp.getBiome(), usesRoad, totalSteps, rng);

            // Enforce max 1 encounter per journey
            if (ENCOUNTER.equals(event)) {
                if (encounterUsed[0]) {
                    event = NONE;
                } else {
                    encounterUsed[0] = true;
                }
            }

            // "rest" only allowed if totalSteps > 3
            if (REST.equals(event) && totalSteps <= 3) {
                event = NONE;
            }

            return wp.toBuilder().eventType(event).build();
        }).toList();
    }

    private static String rollSingleEvent(String biome, boolean usesRoad, int totalSteps, Random rng) {
        int[] weights = new int[EVENT_TYPES.length];
        System.arraycopy(BASE_WEIGHTS, 0, weights, 0, BASE_WEIGHTS.length);

        // Apply biome modifiers
        if (biome != null) {
            Map<String, Integer> biomeMods = BIOME_MODIFIERS.get(biome.toUpperCase());
            if (biomeMods != null) {
                for (int i = 0; i < EVENT_TYPES.length; i++) {
                    Integer mod = biomeMods.get(EVENT_TYPES[i]);
                    if (mod != null) {
                        weights[i] += mod;
                    }
                }
            }
        }

        // Apply road modifiers
        if (usesRoad) {
            for (int i = 0; i < EVENT_TYPES.length; i++) {
                Integer mod = ROAD_MODIFIERS.get(EVENT_TYPES[i]);
                if (mod != null) {
                    weights[i] += mod;
                }
            }
        }

        // Clamp weights to minimum 0
        int totalWeight = 0;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = Math.max(0, weights[i]);
            totalWeight += weights[i];
        }

        if (totalWeight == 0) return NONE;

        // Weighted random selection
        int roll = rng.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < EVENT_TYPES.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return EVENT_TYPES[i];
            }
        }
        return NONE;
    }
}
