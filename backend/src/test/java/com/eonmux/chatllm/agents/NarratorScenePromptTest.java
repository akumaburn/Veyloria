package com.eonmux.chatllm.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarratorScenePromptTest {

    @Test void theNarratorReceivesThePhysicalFactsAndStoredPronouns() {
        AgentContext context = AgentContext.builder()
                .characterIdsInScene(List.of("elara"))
                .characterNames(new JsonObject().put("elara", "Elara Vane"))
                .characterPronouns(new JsonObject().put("elara", "she/her"))
                .characterDescriptions(new JsonObject().put("elara", "Student Diver at The Royal Academy"))
                .characterAppearances(new JsonObject().put("elara", "A thin field coat and a tight braid."))
                .characterProfiles(new JsonObject().put("elara", new JsonObject()
                        .put("gender", "Woman").put("ageYears", 19)
                        .put("height", "5 ft 8 in / 173 cm").put("build", "Lean and quick")
                        .put("hair", "Dark brown, braided").put("eyes", "Silver-gray")))
                .characterSheet(new JsonObject().put("elara", new JsonObject()
                        .put("stats", new JsonArray())
                        .put("inventory", new JsonArray())))
                .build();

        StringBuilder prompt = new StringBuilder();
        NarratorAgent.appendCharactersInScene(prompt, context);

        assertTrue(prompt.toString().contains("- Elara Vane (pronouns: she/her)"), prompt.toString());
        assertTrue(prompt.toString().contains("Looks like: A thin field coat and a tight braid."), prompt.toString());
        assertTrue(prompt.toString().contains(
                "Facts: gender Woman; age 19; height 5 ft 8 in / 173 cm; build Lean and quick; "
                        + "hair Dark brown, braided; eyes Silver-gray"), prompt.toString());
        assertTrue(prompt.toString().contains(
                "The appearance line and the physical facts are fixed."), prompt.toString());
    }

    @Test void aCharacterWithoutFactsGetsNoFactLine() {
        AgentContext context = AgentContext.builder()
                .characterIdsInScene(List.of("assistant"))
                .characterNames(new JsonObject().put("assistant", "Assistant"))
                .build();

        StringBuilder prompt = new StringBuilder();
        NarratorAgent.appendCharactersInScene(prompt, context);

        assertTrue(prompt.toString().contains("- Assistant"), prompt.toString());
        assertFalse(prompt.toString().contains("Facts:"), prompt.toString());
    }

    @Test void anEmptySceneAddsNothing() {
        StringBuilder prompt = new StringBuilder();
        NarratorAgent.appendCharactersInScene(prompt, AgentContext.builder().build());

        assertTrue(prompt.isEmpty());
    }
}
