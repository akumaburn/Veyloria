package com.eonmux.chatllm.llm;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetTest {

    private static JsonObject message(String role, String content) {
        return new JsonObject().put("role", role).put("content", content);
    }

    @Test void reducesTheCompletionCeilingSoPromptPlusCompletionFits() {
        JsonArray messages = new JsonArray().add(message("user", "x".repeat(3000)));
        JsonObject params = new JsonObject().put("max_tokens", 256000);
        TokenBudget.fitRequest(params, messages, 64000);

        int maxTokens = params.getInteger("max_tokens");
        assertTrue(maxTokens < 256000);
        assertTrue(TokenBudget.estimateMessages(messages) + maxTokens <= 64000);
    }

    @Test void keepsASmallerRequestedCeiling() {
        JsonObject params = new JsonObject().put("max_tokens", 1000);
        TokenBudget.fitRequest(params, new JsonArray().add(message("user", "hello")), 262144);
        assertEquals(1000, params.getInteger("max_tokens"));
    }

    @Test void trimsOldestTurnsButKeepsTheSystemPromptAndNewestMessage() {
        JsonArray messages = new JsonArray()
                .add(message("system", "system"))
                .add(message("user", "old".repeat(4000)))
                .add(message("assistant", "older".repeat(4000)))
                .add(message("user", "newest"));
        JsonArray fitted = TokenBudget.fitMessages(messages, 5000);

        assertTrue(fitted.size() < messages.size());
        assertEquals("system", fitted.getJsonObject(0).getString("content"));
        assertEquals("newest", fitted.getJsonObject(fitted.size() - 1).getString("content"));
    }

    @Test void rejectsAPromptThatCannotFitEvenAfterTrimming() {
        JsonArray messages = new JsonArray().add(message("user", "x".repeat(30000)));
        assertThrows(IllegalArgumentException.class,
                () -> TokenBudget.fitRequest(new JsonObject().put("max_tokens", 1000), messages, 2000));
    }

    @Test void leavesARequestWithoutACompletionCeilingAlone() {
        JsonObject params = new JsonObject().put("temperature", 0.7);
        TokenBudget.fitRequest(params, new JsonArray().add(message("user", "hello")), 262144);
        assertFalse(params.containsKey("max_tokens"));
    }
}
