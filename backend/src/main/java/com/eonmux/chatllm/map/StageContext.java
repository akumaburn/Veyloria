package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.LocalAreaConnection;
import com.eonmux.chatllm.models.MapLocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The single place the scene currently occupies: a world-map node, one local
 * area inside it, the areas reachable on foot, and the destinations reachable
 * through a gateway.
 *
 * <p>Every prompt that can move a character or describe the environment reads
 * this object. That keeps narration and dialogue on the same map.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StageContext {
    private String mapId;
    private String mapName;
    private String mapScale;
    private MapLocation node;
    private LocalArea area;
    @Builder.Default
    private List<LocalArea> areas = List.of();
    @Builder.Default
    private List<LocalAreaConnection> connections = List.of();
    @Builder.Default
    private List<StageExit> exits = List.of();
    @Builder.Default
    private List<StageDestination> destinations = List.of();
    @Builder.Default
    private List<String> routeToGateway = List.of();
    @Builder.Default
    private boolean atGateway = false;
    /** Areas of this node the party has already been in (ids, any order). */
    @Builder.Default
    private List<String> visitedAreaIds = List.of();

    public static StageContext empty() {
        return StageContext.builder().build();
    }

    public boolean isPresent() {
        return node != null && area != null;
    }
}
