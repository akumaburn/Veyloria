package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.MapLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StagePromptTest {

    @Test void thePromptNamesTheRoomAndItsExits() {
        LocalArea hall = LocalArea.builder()
                .localAreaId(UUID.randomUUID())
                .name("Great Hall")
                .areaType("hall")
                .description("A long hall with a cold hearth.")
                .build();
        MapLocation node = MapLocation.builder()
                .locationId(UUID.randomUUID())
                .name("Blackmoor Castle")
                .locationType("castle")
                .build();
        StageContext stage = StageContext.builder()
                .mapName("The North")
                .mapScale("region")
                .node(node)
                .area(hall)
                .exits(List.of(new StageExit(UUID.randomUUID().toString(), "Kitchen",
                        "kitchen", "A warm kitchen.", "service door", "west")))
                .atGateway(false)
                .routeToGateway(List.of("Great Hall", "Courtyard", "Gatehouse"))
                .build();

        String prompt = StagePrompt.format(stage, StagePrompt.Audience.CHARACTER);

        assertTrue(prompt.contains("Room: Great Hall"));
        assertTrue(prompt.contains("Kitchen"));
        assertTrue(prompt.contains("(west)"));
        assertTrue(prompt.contains("service door"));
        assertTrue(prompt.contains("Great Hall -> Courtyard -> Gatehouse"));
        assertTrue(prompt.contains("Never invent a room"));
        // The exit list is the complete set of passages; nothing may be added.
        assertTrue(prompt.contains("There is no passage in any other direction"));
    }

    @Test void anEmptyStageProducesNoPrompt() {
        String prompt = StagePrompt.format(StageContext.empty(), StagePrompt.Audience.NARRATOR);
        assertTrue(prompt.isEmpty());
    }
}
