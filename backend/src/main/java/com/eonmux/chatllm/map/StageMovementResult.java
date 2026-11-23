package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.MapLocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of applying a requested location or area change. Rejected moves keep
 * the scene where it was, so a bad model output cannot move the party.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageMovementResult {
    private StageMovementPolicy.Kind kind;
    private StageContext stage;
    private String reason;
    private MapLocation fromNode;
    private MapLocation toNode;
    @Builder.Default
    private List<MapLocation> discovered = List.of();

    public boolean moved() {
        return kind == StageMovementPolicy.Kind.AREA || kind == StageMovementPolicy.Kind.NODE;
    }
}
