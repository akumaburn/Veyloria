package com.eonmux.chatllm.agents;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarratorPromptIntegrationTest {

    /** A narrator whose model call is captured instead of sent. */
    private static class CapturingNarrator extends NarratorAgent {
        String userPrompt;

        CapturingNarrator() {
            super(null, null, null, null);
        }

        @Override
        protected Uni<JsonObject> callLlmProse(String systemPrompt, String userPrompt,
                                               float temperature, int maxTokens, String resolvedModel) {
            this.userPrompt = userPrompt;
            return Uni.createFrom().item(response("The hall was cold."));
        }

        @Override
        protected Uni<JsonObject> callLlmJson(String systemPrompt, String userPrompt,
                                              float temperature, int maxTokens, String resolvedModel) {
            this.userPrompt = userPrompt;
            return Uni.createFrom().item(response("The hall was cold."));
        }

        private static JsonObject response(String content) {
            return new JsonObject().put("choices", new JsonArray().add(new JsonObject()
                    .put("message", new JsonObject().put("content", content))));
        }
    }

    @Test void theModelReceivesTheFactsAndTheFixedBodyRule() {
        AgentContext context = AgentContext.builder()
                .triggerContent("I examine the door.")
                .interactionResult("The player examined the door.")
                .characterIdsInScene(List.of("elara"))
                .characterNames(new JsonObject().put("elara", "Elara Vane"))
                .characterPronouns(new JsonObject().put("elara", "she/her"))
                .characterDescriptions(new JsonObject().put("elara", "Student Diver at The Royal Academy"))
                .characterAppearances(new JsonObject().put("elara", "A thin field coat and a tight braid."))
                .characterProfiles(new JsonObject().put("elara", new JsonObject()
                        .put("gender", "Woman").put("ageYears", 18)
                        .put("height", "5 ft 11 in / 180 cm").put("build", "Slender and athletic")
                        .put("hair", "Dark, long, tight practical braid").put("eyes", "Silver, sharp")))
                .characterSheet(new JsonObject().put("elara", new JsonObject()
                        .put("stats", new JsonArray())
                        .put("inventory", new JsonArray())))
                .build();

        CapturingNarrator narrator = new CapturingNarrator();
        AgentResult result = narrator.executeWithFeedback(context, null, "test-model").await().indefinitely();

        assertEquals("success", result.getStatus());
        assertTrue(narrator.userPrompt.contains("- Elara Vane (pronouns: she/her)"), narrator.userPrompt);
        assertTrue(narrator.userPrompt.contains(
                "Facts: gender Woman; age 18; height 5 ft 11 in / 180 cm; build Slender and athletic; "
                        + "hair Dark, long, tight practical braid; eyes Silver, sharp"), narrator.userPrompt);
        assertTrue(narrator.userPrompt.contains("The appearance line and the physical facts are fixed."),
                narrator.userPrompt);
    }
}
