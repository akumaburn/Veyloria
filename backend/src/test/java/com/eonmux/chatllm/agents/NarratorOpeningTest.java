package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.MapLocation;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarratorOpeningTest {

    /** A narrator whose model calls are captured and answered from a list. */
    private static class ScriptedNarrator extends NarratorAgent {
        final List<String> replies;
        final List<String> prompts = new ArrayList<>();
        int calls;

        ScriptedNarrator(String... replies) {
            super(null, null, null, null);
            this.replies = List.of(replies);
        }

        @Override
        protected Uni<JsonObject> callLlmProse(String systemPrompt, String userPrompt,
                                               float temperature, int maxTokens, String resolvedModel) {
            prompts.add(userPrompt);
            String content = replies.get(Math.min(calls, replies.size() - 1));
            calls++;
            return Uni.createFrom().item(response(content));
        }

        @Override
        protected Uni<JsonObject> callLlmJson(String systemPrompt, String userPrompt,
                                              float temperature, int maxTokens, String resolvedModel) {
            return callLlmProse(systemPrompt, userPrompt, temperature, maxTokens, resolvedModel);
        }

        private static JsonObject response(String content) {
            return new JsonObject().put("choices", new JsonArray().add(new JsonObject()
                    .put("message", new JsonObject().put("content", content))));
        }
    }

    @Test void anOpeningThatRefusesToNarrateIsAskedAgain() {
        ScriptedNarrator narrator = new ScriptedNarrator(
                "[NO_NARRATION]",
                "The Great Hall was cold, and the party stood together under its broken roof.");

        AgentResult result = narrator.executeWithFeedback(openingContext(), null, "test-model")
                .await().indefinitely();

        assertEquals(2, narrator.calls);
        assertTrue(result.getContent().contains("The Great Hall was cold"), result.getContent());
        assertTrue(narrator.prompts.get(1).contains("THE STORY HAS NOT OPENED YET"),
                narrator.prompts.get(1));
        assertTrue(narrator.prompts.get(0).contains("Do not output [NO_NARRATION] for the opening."),
                narrator.prompts.get(0));
    }

    @Test void aBoldSilentOpeningIsAskedAgain() {
        ScriptedNarrator narrator = new ScriptedNarrator(
                "**[NO_NARRATION]**",
                "The Great Hall was cold, and the party stood together under its broken roof.");

        AgentResult result = narrator.executeWithFeedback(openingContext(), null, "test-model")
                .await().indefinitely();

        assertEquals(2, narrator.calls);
        assertTrue(result.getContent().contains("The Great Hall was cold"), result.getContent());
    }

    @Test void aBoldSilentOpeningFallsBackToTheStage() {
        ScriptedNarrator narrator = new ScriptedNarrator("**[NO_NARRATION]**", "**[NO_NARRATION]**");

        AgentResult result = narrator.executeWithFeedback(openingContext(), null, "test-model")
                .await().indefinitely();

        assertEquals(2, narrator.calls);
        assertTrue(result.getContent().contains("in the Great Hall of The Shattered Keep"),
                result.getContent());
    }

    @Test void aBoldSilentLaterTurnIsStillAllowedToSkip() {
        ScriptedNarrator narrator = new ScriptedNarrator("** [NO_NARRATION] **");

        AgentContext context = AgentContext.builder()
                .triggerContent("Elara says the group should wait.")
                .build();
        AgentResult result = narrator.executeWithFeedback(context, null, "test-model")
                .await().indefinitely();

        assertEquals(0, narrator.calls);
        assertEquals("", result.getContent());
        assertTrue(result.getStructuredOutput().getBoolean("skipped", false));
    }

    @Test void aSilentOpeningFallsBackToTheStageInsteadOfAnEmptyScene() {
        ScriptedNarrator narrator = new ScriptedNarrator("[NO_NARRATION]", "[NO_NARRATION]");

        AgentResult result = narrator.executeWithFeedback(openingContext(), null, "test-model")
                .await().indefinitely();

        assertEquals(2, narrator.calls);
        assertTrue(result.getContent().contains("in the Great Hall of The Shattered Keep"),
                result.getContent());
        assertTrue(result.getContent().contains("Elara Vance"), result.getContent());
        assertFalse(result.getContent().isBlank());
    }

    @Test void aLaterSilentTurnIsStillAllowedToSkip() {
        ScriptedNarrator narrator = new ScriptedNarrator("[NO_NARRATION]");

        AgentContext context = AgentContext.builder()
                .triggerContent("Elara says the group should wait.")
                .build();
        AgentResult result = narrator.executeWithFeedback(context, null, "test-model")
                .await().indefinitely();

        assertEquals(0, narrator.calls);
        assertEquals("", result.getContent());
        assertTrue(result.getStructuredOutput().getBoolean("skipped", false));
    }

    @Test void theSessionStartSentinelCountsAsAnOpening() {
        ScriptedNarrator narrator = new ScriptedNarrator(
                "[NO_NARRATION]",
                "Smoke hung over the market square while the party gathered.");

        AgentContext context = AgentContext.builder()
                .triggerContent("[SESSION_START] This is the beginning of a new roleplay session.")
                .stage(stage())
                .build();
        AgentResult result = narrator.executeWithFeedback(context, null, "test-model")
                .await().indefinitely();

        assertEquals(2, narrator.calls);
        assertTrue(result.getContent().contains("Smoke hung"), result.getContent());
    }

    @Test void aCommittedMoveCannotBeSkippedAsMereDialogue() {
        ScriptedNarrator narrator = new ScriptedNarrator("[NO_NARRATION]", "**[NO_NARRATION]**");
        var context = AgentContext.builder().triggerContent("Let's move to the tavern for now then.")
                .playerName("Amir").stage(stage()).postDialogue(true)
                .stageMoveDescription("from the Market Square to the Tavern via tavern lane").build();
        AgentResult result = narrator.executeWithFeedback(context, null, "test-model").await().indefinitely();
        assertEquals(2, narrator.calls);
        assertEquals("Amir and the party moved from the Market Square to the Tavern via tavern lane.", result.getContent());
        assertFalse(result.getStructuredOutput().getBoolean("skipped", false));
        assertTrue(narrator.prompts.getFirst().contains("server has confirmed the move"));
    }

    private static AgentContext openingContext() {
        return AgentContext.builder()
                .triggerContent("[SESSION_START] This is the beginning of a new roleplay session.")
                .playerName("Amir")
                .characterIdsInScene(List.of("elara"))
                .characterNames(new JsonObject().put("elara", "Elara Vance"))
                .stage(stage())
                .build();
    }

    private static StageContext stage() {
        LocalArea hall = LocalArea.builder()
                .localAreaId(UUID.randomUUID())
                .name("Great Hall")
                .build();
        MapLocation keep = MapLocation.builder()
                .locationId(UUID.randomUUID())
                .name("The Shattered Keep")
                .locationType("dungeon")
                .build();
        return StageContext.builder()
                .node(keep)
                .area(hall)
                .areas(List.of(hall))
                .build();
    }
    @Test void blockedTravelCannotSilentlyDisappear() {
        var narrator = new ScriptedNarrator("[NO_NARRATION]");
        var context = AgentContext.builder().triggerContent("Let's check the Inn.")
            .stageMoveFailure("The established gate was locked.").build();
        var result = narrator.executeWithFeedback(context, null, "test-model").await().indefinitely();
        assertTrue(result.getContent().contains("gate was locked"));
        assertFalse(result.getContent().contains("arrived"));
    }

    @Test void strippingControlTagsCannotEraseARequiredArrival() {
        var narrator = new ScriptedNarrator("[IMAGE: The Inn entrance]");
        var context = AgentContext.builder().triggerContent("Let's check the Inn.")
            .stageMoveDescription("from the Village Green to the Inn via inn lane").build();
        var result = narrator.executeWithFeedback(context, null, "test-model").await().indefinitely();
        assertTrue(result.getContent().contains("Village Green to the Inn via inn lane"));
        assertFalse(result.getStructuredOutput().getBoolean("skipped", false));
    }
    @Test void resolvedInteractionsCannotBeSilencedByTheNarrator() {
        var narrator = new ScriptedNarrator("[NO_NARRATION]");
        var context = AgentContext.builder().triggerContent("I examine the ledger.")
            .interactionResult("Amir examined the ledger.").build();
        var result = narrator.executeWithFeedback(context, null, "test-model").await().indefinitely();
        assertEquals("Amir examined the ledger.", result.getContent());
        assertTrue(narrator.prompts.getFirst().contains("RESOLVED INTERACTION"));
    }

}
