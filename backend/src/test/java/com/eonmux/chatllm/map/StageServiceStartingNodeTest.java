package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.MapLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A new session must open where the party was placed.
 *
 * <p>A missing world-state row used to fall through to the first discovered
 * settlement, so a dungeon story opened in a town market while every character
 * and the player stood on the dungeon node.</p>
 */
class StageServiceStartingNodeTest {

    private static final MapLocation DUNGEON = MapLocation.builder()
            .locationId(UUID.randomUUID())
            .name("The Shattered Keep")
            .locationType("dungeon")
            .isDiscovered(true)
            .build();

    private static final MapLocation TOWN = MapLocation.builder()
            .locationId(UUID.randomUUID())
            .name("Woobeton")
            .locationType("town")
            .isDiscovered(true)
            .build();

    private static final MapLocation HIDDEN_RUIN = MapLocation.builder()
            .locationId(UUID.randomUUID())
            .name("Hidden Ruin")
            .locationType("ruins")
            .isDiscovered(false)
            .build();

    @Test void theMapStartingNodeWinsOverADiscoveredSettlement() {
        MapLocation chosen = StageService.chooseStartingLocation(
                List.of(TOWN, DUNGEON), DUNGEON.getLocationId().toString(), null);

        assertEquals(DUNGEON, chosen);
    }

    @Test void thePartyPositionWinsWhenTheMapHasNoStartingNode() {
        MapLocation chosen = StageService.chooseStartingLocation(
                List.of(TOWN, DUNGEON), null, DUNGEON.getLocationId().toString());

        assertEquals(DUNGEON, chosen);
    }

    @Test void aDiscoveredSettlementIsTheLastResort() {
        MapLocation chosen = StageService.chooseStartingLocation(List.of(HIDDEN_RUIN, TOWN), null, null);

        assertEquals(TOWN, chosen);
    }

    @Test void anUnknownStartingIdFallsBackToThePartyPosition() {
        MapLocation chosen = StageService.chooseStartingLocation(
                List.of(TOWN, DUNGEON), UUID.randomUUID().toString(), DUNGEON.getLocationId().toString());

        assertEquals(DUNGEON, chosen);
    }

    @Test void anEmptyMapHasNoStartingNode() {
        assertNull(StageService.chooseStartingLocation(List.of(), null, null));
    }
}
