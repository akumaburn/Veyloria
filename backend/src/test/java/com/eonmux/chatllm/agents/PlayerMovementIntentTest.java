package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.MapLocation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PlayerMovementIntentTest {
    static StageContext stage() {
        var green = LocalArea.builder().localAreaId(UUID.randomUUID()).name("Village Green").build();
        var inn = LocalArea.builder().localAreaId(UUID.randomUUID()).name("Inn").build();
        var smithy = LocalArea.builder().localAreaId(UUID.randomUUID()).name("Smithy").build();
        return StageContext.builder().node(MapLocation.builder().locationId(UUID.randomUUID()).name("Nekorian Enclave").build())
            .area(green).areas(List.of(green, inn, smithy)).build();
    }
    static AgentContext context(String text) {
        return AgentContext.builder().postDialogue(true).triggerContent(text).stage(stage()).build();
    }
    @ParameterizedTest @ValueSource(strings = {
        "Let's check the Inn first, odds are the bartender can give us the rundown.",
        "Let’s head for the inn for now.", "Lets check the Inn.", "I walk to the Inn.", "We enter the Inn.", "Take us to the Inn."
    }) void recognizesExplicitPlayerTravel(String text) {
        assertEquals("Inn", PlayerMovementIntent.localDestination(context(text)));
    }
    @ParameterizedTest @ValueSource(strings = {
        "Should we go to the Inn?", "If it is safe, let's go to the Inn.",
        "Let's go to the Inn after we buy supplies.", "Let's go to the Inn tomorrow.",
        "Let's not go to the Inn.", "Elara says, let's check the Inn first.",
        "Let's check the Inn or the Smithy.", "Let's check the Inn. Actually, stay here.",
        "Let's check the Inn on the map.", "Let's check the Inn's menu.", "Let's visit a nonexistent palace.",
        "The party walks toward the Inn.", "We should check the Inn first."
    }) void leavesAmbiguousConditionalAndNpcPlansToTheAgent(String text) {
        assertNull(PlayerMovementIntent.localDestination(context(text)));
    }
    @Test void neverInfersMovementDuringOpeningOrBeforeCharacterReplies() {
        var context = context("Let's check the Inn first.");
        context.setPostDialogue(false);
        assertNull(PlayerMovementIntent.localDestination(context));
        context.setPostDialogue(true);
        context.setTriggerContent("[SESSION_START] Let's check the Inn.");
        assertNull(PlayerMovementIntent.localDestination(context));
        context.setTriggerContent("Let's check the Inn.");
        context.setStage(StageContext.empty());
        assertNull(PlayerMovementIntent.localDestination(context));
    }
}
