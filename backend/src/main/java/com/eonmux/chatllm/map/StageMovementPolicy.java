package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.LocalAreaConnection;
import com.eonmux.chatllm.models.MapLocation;

import java.util.List;
import java.util.UUID;

/**
 * Decides whether a requested move is legal. The decision is pure so the same
 * rules can be tested without a database and can never be relaxed by an LLM.
 */
public final class StageMovementPolicy {

    public enum Kind {NONE, AREA, NODE, REJECTED}

    public record Decision(Kind kind, String reason, LocalArea area, MapLocation node) {
        public static Decision none(String reason) {
            return new Decision(Kind.NONE, reason, null, null);
        }

        public static Decision area(LocalArea area) {
            return new Decision(Kind.AREA, "moved within the current place", area, null);
        }

        public static Decision node(MapLocation node, LocalArea area) {
            return new Decision(Kind.NODE, "left through a gateway", area, node);
        }

        public static Decision rejected(String reason) {
            return new Decision(Kind.REJECTED, reason, null, null);
        }
    }

    private StageMovementPolicy() {
    }

    /**
     * A move inside the current node must follow a recorded connection. A path
     * through intermediate areas is allowed because a scene can walk through
     * two rooms in one message; disconnected rooms are not.
     */
    public static Decision forArea(LocalArea current, LocalArea target,
                                   List<LocalAreaConnection> connections) {
        if (target == null) return Decision.rejected("That area does not exist in this place.");
        if (current == null) return Decision.none("The scene has no current area.");
        UUID currentId = current.getLocalAreaId();
        UUID targetId = target.getLocalAreaId();
        if (currentId == null || targetId == null) return Decision.rejected("Invalid area.");
        if (currentId.equals(targetId)) return Decision.none("Already in that area.");
        if (LocalMapService.reachableAreas(currentId, connections).contains(targetId)) {
            return Decision.area(target);
        }
        return Decision.rejected("That area is not connected to the current area.");
    }

    /**
     * Leaving the node requires a reachable gateway and a destination
     * connected by a road or close enough to walk. The party may walk from
     * the current room to that gateway in the same scene, but it cannot cut
     * from a disconnected room to another part of the world.
     */
    public static Decision forNode(boolean canReachGateway, MapLocation target, boolean reachable) {
        if (target == null) return Decision.rejected("That place does not exist on the map.");
        if (!canReachGateway) {
            return Decision.rejected("The party cannot reach a gateway from the current area.");
        }
        if (!reachable) {
            return Decision.rejected("That place is not a reachable destination from here.");
        }
        return Decision.node(target, null);
    }
}
