package com.eonmux.chatllm.map.models;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Biome {
    DEEP_WATER(0, "#1e3a5f"),
    SHALLOW_WATER(1, "#3b82f6"),
    BEACH(2, "#d4a574"),
    GRASSLAND(3, "#4ade80"),
    FOREST(4, "#22c55e"),
    DENSE_FOREST(5, "#166534"),
    HILLS(6, "#a3a38a"),
    MOUNTAIN(7, "#94a3b8"),
    SNOW_PEAK(8, "#e2e8f0"),
    DESERT(9, "#d4a017"),
    SWAMP(10, "#4a6741"),
    RIVER(11, "#2563eb"),
    LAKE(12, "#60a5fa"),
    TUNDRA(13, "#a8b4a0");

    private final int id;
    private final String hexColor;

    public static Biome fromId(int id) {
        for (Biome b : values()) {
            if (b.id == id) return b;
        }
        return GRASSLAND;
    }
}
