package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A passable connection between two local areas in the same world-map node.
 * The connection is bidirectional; the label describes the passage itself
 * (for example, "service door" or "stone stair").
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocalAreaConnection {
    private UUID connectionId;
    private UUID mapId;
    private UUID locationId;
    private UUID fromAreaId;
    private UUID toAreaId;
    private String label;
    private String createdAt;
}
