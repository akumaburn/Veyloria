package com.eonmux.chatllm.map;

/**
 * One adjacent local area, reachable from the current area through a named
 * connection. {@code direction} is the compass bearing from the current
 * area to this one, so narration and the local map agree on where things
 * are.
 */
public record StageExit(
        String areaId,
        String name,
        String areaType,
        String description,
        String label,
        String direction
) {
}
