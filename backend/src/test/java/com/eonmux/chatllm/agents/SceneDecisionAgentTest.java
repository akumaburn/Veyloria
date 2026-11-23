package com.eonmux.chatllm.agents;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneDecisionAgentTest {
    static class Scripted extends SceneDecisionAgent {
        final List<String> replies;
        final List<String> systems = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        Scripted(boolean travel, String... replies) { this(travel ? Question.TRAVEL : Question.INTERACTION, replies); }
        Scripted(Question question, String... replies) { super(question, null, null, null, null); this.replies = List.of(replies); }
        @Override protected Uni<JsonObject> callLlmJson(String system, String prompt, float temp, int tokens, String model) {
            systems.add(system); prompts.add(prompt);
            return Uni.createFrom().item(new JsonObject().put("choices", new JsonArray().add(new JsonObject()
                .put("message", new JsonObject().put("content", replies.get(Math.min(replies.size()-1, systems.size()-1)))))));
        }
        AgentResult decide() {
            return execute(AgentContext.builder().triggerContent("Let's check the Inn first.")
                .recentMessages(new JsonArray().add(new JsonObject().put("displayName", "Elara").put("content", "I follow.")))
                .stage(PlayerMovementIntentTest.stage()).build(), "test")
                .onFailure().recoverWithItem(error -> AgentResult.error("test",error.getMessage())).await().indefinitely();
        }
    }
    @Test void travelHasOnlyItsOwnDecisionSchemaAndTheCompletedRound() {
        var agent = new Scripted(true, "{\"move\":true,\"currentArea\":\"Inn\"}");
        assertEquals("Inn", agent.decide().getStructuredOutput().getString("currentArea"));
        assertTrue(agent.systems.getFirst().contains("Resolve ONLY travel"));
        assertFalse(agent.systems.getFirst().contains("\"occurred\""));
        assertTrue(agent.prompts.getFirst().contains("Elara: I follow."));
    }
    @Test void interactionIsASeparateRequestWithNoTravelDecisionSchema() {
        var agent = new Scripted(false, "{\"occurred\":true,\"result\":\"Amir examined the ledger.\"}");
        assertEquals("Amir examined the ledger.", agent.decide().getStructuredOutput().getString("result"));
        assertTrue(agent.systems.getFirst().contains("Resolve ONLY whether"));
        assertFalse(agent.systems.getFirst().contains("\"move\""));
    }
    @Test void malformedDecisionIsRetriedInsteadOfSilentlyBecomingNoAction() {
        var agent = new Scripted(true, "{}", "{\"move\":true,\"currentArea\":\"Inn\"}");
        assertEquals("success", agent.decide().getStatus());
        assertEquals(2, agent.systems.size());
    }
    @Test void movementWithoutADestinationAndInteractionsWithoutAResultAreInvalid() {
        assertEquals("error", new Scripted(true, "{\"move\":true}").decide().getStatus());
        assertEquals("error", new Scripted(false, "{\"occurred\":true,\"result\":\"\"}").decide().getStatus());
    }
    @Test void explicitNoActionIsValid() {
        assertFalse(new Scripted(true, "{\"move\":false}").decide().getStructuredOutput().getBoolean("move"));
        assertFalse(new Scripted(false, "{\"occurred\":false}").decide().getStructuredOutput().getBoolean("occurred"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"move\":true,\"currentArea\":\"Inn\",\"currentLocation\":\"Town\"}",
        "{\"move\":false,\"currentArea\":\"Inn\"}",
        "{\"move\":true,\"currentArea\":\"Inn\",\"movementBlockedReason\":\"Locked\"}",
        "{\"move\":true,\"currentArea\":\"Inn\",\"occurred\":true}",
        "{\"move\":false} {\"move\":true,\"currentArea\":\"Inn\"}"
    }) void contradictoryTravelResponsesAreErrorsNotPartialDecisions(String wire) {
        assertEquals("error", new Scripted(true,wire).decide().getStatus());
    }
    @Test void falseInteractionCannotCarryAResultAndEncounterCannotCarryOtherDecisions() {
        assertEquals("error",new Scripted(false,"{\"occurred\":false,\"result\":\"Something happened\"}").decide().getStatus());
        assertEquals("error",new Scripted(SceneDecisionAgent.Question.ENCOUNTER,"{\"encounter\":false,\"enemyName\":\"Bandit\"}").decide().getStatus());
        var encounter=new Scripted(SceneDecisionAgent.Question.ENCOUNTER,
            "{\"encounter\":true,\"enemyName\":\"Bandit\",\"enemyDescription\":\"An established attacker\",\"enemyHp\":20}").decide();
        assertTrue(encounter.getStructuredOutput().getBoolean("encounter"));
    }

}
