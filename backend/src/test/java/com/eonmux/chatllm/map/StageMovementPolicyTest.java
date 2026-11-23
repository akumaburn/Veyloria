package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.LocalAreaConnection;
import com.eonmux.chatllm.models.MapLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageMovementPolicyTest {
    private final LocalArea hall = area("Great Hall");
    private final LocalArea kitchen = area("Kitchen");
    private final LocalArea vault = area("Vault");

    @Test void aConnectedRoomIsReachable() {
        StageMovementPolicy.Decision decision = StageMovementPolicy.forArea(
                hall, kitchen, List.of(link(hall, kitchen)));

        assertEquals(StageMovementPolicy.Kind.AREA, decision.kind());
        assertEquals(kitchen.getLocalAreaId(), decision.area().getLocalAreaId());
    }

    @Test void aDisconnectedRoomIsRejected() {
        StageMovementPolicy.Decision decision = StageMovementPolicy.forArea(
                hall, vault, List.of(link(hall, kitchen)));

        assertEquals(StageMovementPolicy.Kind.REJECTED, decision.kind());
    }

    @Test void theCurrentRoomIsNoMove() {
        StageMovementPolicy.Decision decision = StageMovementPolicy.forArea(
                hall, hall, List.of());

        assertEquals(StageMovementPolicy.Kind.NONE, decision.kind());
    }

    @Test void leavingRequiresAReachableGateway() {
        MapLocation target = MapLocation.builder().locationId(UUID.randomUUID()).name("Greywater").build();

        assertEquals(StageMovementPolicy.Kind.REJECTED,
                StageMovementPolicy.forNode(false, target, true).kind());
        assertEquals(StageMovementPolicy.Kind.NODE,
                StageMovementPolicy.forNode(true, target, true).kind());
    }

    @Test void leavingRequiresAReachableDestination() {
        MapLocation target = MapLocation.builder().locationId(UUID.randomUUID()).name("Greywater").build();

        assertEquals(StageMovementPolicy.Kind.REJECTED,
                StageMovementPolicy.forNode(true, target, false).kind());
        assertEquals(StageMovementPolicy.Kind.NODE,
                StageMovementPolicy.forNode(true, target, true).kind());
    }

    @Test void localMapHelpersFindShortcutsAndRoutes() {
        List<LocalArea> areas = List.of(hall, kitchen, vault);
        List<LocalAreaConnection> links = List.of(link(hall, kitchen), link(kitchen, vault));

        assertEquals(kitchen.getLocalAreaId(),
                LocalMapService.findArea(areas, "the kitchen").getLocalAreaId());
        assertTrue(LocalMapService.reachableAreas(hall.getLocalAreaId(), links)
                .contains(vault.getLocalAreaId()));
        assertEquals(List.of(hall.getLocalAreaId(), kitchen.getLocalAreaId(), vault.getLocalAreaId()),
                LocalMapService.route(hall.getLocalAreaId(), vault.getLocalAreaId(), links));
    }

    private static LocalArea area(String name) {
        return LocalArea.builder()
                .localAreaId(UUID.randomUUID())
                .name(name)
                .areaType("room")
                .build();
    }

    private static LocalAreaConnection link(LocalArea from, LocalArea to) {
        return LocalAreaConnection.builder()
                .connectionId(UUID.randomUUID())
                .fromAreaId(from.getLocalAreaId())
                .toAreaId(to.getLocalAreaId())
                .label("door")
                .build();
    }
}
