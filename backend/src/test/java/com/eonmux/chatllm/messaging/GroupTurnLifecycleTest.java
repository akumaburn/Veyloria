package com.eonmux.chatllm.messaging;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.Message;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;

import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupTurnLifecycleTest {
    private Vertx vertx;
    private LLMBridge bridge;

    @BeforeEach void setup() {
        vertx = Vertx.vertx();
        bridge = new LLMBridge();
        bridge.vertx = vertx;
        bridge.mysqlPool = pool((sql, params) -> affected(1));
    }

    @AfterEach void close() { vertx.closeAndAwait(); }

    @Test void chainedTurnsPreserveTheUserAndUseFireAndForgetDelivery() throws Exception {
        CompletableFuture<Message<JsonObject>> next = new CompletableFuture<>();
        var consumer = vertx.eventBus().<JsonObject>consumer("group.character.respond").handler(next::complete);
        consumer.completionHandlerAndAwait();
        trigger().await().indefinitely();
        Message<JsonObject> event = next.get(2, TimeUnit.SECONDS);
        assertEquals("owner", event.body().getString("userId"));
        assertEquals("character", event.body().getString("characterId"));
        assertNull(event.replyAddress(), "A streaming turn must not wait for an unimplemented reply");
    }

    @Test void pausingCancelsAnAlreadyScheduledFollowUpTurn() throws Exception {
        CompletableFuture<Boolean> delivered = new CompletableFuture<>();
        var consumer = vertx.eventBus().consumer("group.character.respond").handler(event -> delivered.complete(true));
        consumer.completionHandlerAndAwait();
        trigger().await().indefinitely();
        @SuppressWarnings("unchecked") Message<JsonObject> pause = mock(Message.class);
        when(pause.body()).thenReturn(new JsonObject().put("conversationId", "chat"));
        Method method = LLMBridge.class.getDeclaredMethod("handleGroupPause", Message.class);
        method.setAccessible(true);
        method.invoke(bridge, pause);
        vertx.setTimer(700, ignored -> delivered.complete(false));
        assertFalse(delivered.get(2, TimeUnit.SECONDS));
    }

    @Test void cancelledWorkCannotBecomeValidAgainWhenTheSameCharacterHasANewTurn() throws Exception {
        var field = LLMBridge.class.getDeclaredField("activeGroupTurns");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> turns = (Map<String, String>) field.get(bridge);
        turns.put("chat", "new-turn");
        Method method = LLMBridge.class.getDeclaredMethod("verifyActiveGroupTurn", String.class, String.class, String.class);
        method.setAccessible(true);
        Uni<?> oldTurn = (Uni<?>) method.invoke(bridge, "chat", "character", "old-turn");
        assertThrows(IllegalStateException.class, () -> oldTurn.await().indefinitely());
        assertEquals("new-turn", turns.get("chat"));
        verifyNoInteractions(bridge.mysqlPool);
    }

    @Test void stopAcknowledgesTheStateResetAndNotifiesOtherViewers() throws Exception {
        var order = new java.util.ArrayList<String>();
        bridge.mysqlPool = pool((sql, params) -> {
            order.add("state reset");
            return affected(1);
        });
        CompletableFuture<JsonObject> changed = new CompletableFuture<>();
        var consumer = vertx.eventBus().<JsonObject>consumer("group:chat:state-changed")
                .handler(event -> changed.complete(event.body()));
        consumer.completionHandlerAndAwait();
        @SuppressWarnings("unchecked") Message<JsonObject> stop = mock(Message.class);
        when(stop.body()).thenReturn(new JsonObject().put("conversationId", "chat"));
        doAnswer(call -> { order.add("acknowledged"); return null; }).when(stop).reply(any(JsonObject.class));
        Method method = LLMBridge.class.getDeclaredMethod("handleStopGeneration", Message.class);
        method.setAccessible(true);
        method.invoke(bridge, stop);
        assertEquals(java.util.List.of("state reset", "acknowledged"), order);
        assertEquals("WAITING_FOR_USER", changed.get(2, TimeUnit.SECONDS).getString("groupState"));
    }

    @Test void returnsToTheUserWhenTheCharacterTurnBudgetIsSpent() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        bridge.mysqlPool = pool((sql, params) -> {
            if (sql.contains("SET currentSpeakerId = NULL")) return affected(1);
            if (sql.contains("UPDATE conversation_character")) return affected(1);
            if (sql.contains("SELECT consecutiveResponseCount")) return rows(row(Map.of(
                    "consecutiveResponseCount", 3, "maxConsecutiveResponses", 3)));
            if (sql.contains("SELECT conversationGUID FROM conversation")) return rows(row(Map.of("conversationGUID", "chat")));
            if (sql.contains("SELECT isRoleplay, agentPipelineEnabled")) return rows(row(Map.of("isRoleplay", false, "agentPipelineEnabled", false)));
            if (sql.contains("SET groupState = 'WAITING_FOR_USER'")) {
                calls.add("waiting");
                return affected(1);
            }
            throw new AssertionError("Unexpected query: " + sql);
        });
        bridge.groupChatService = mock(com.eonmux.chatllm.services.GroupChatService.class);
        Method method = LLMBridge.class.getDeclaredMethod("updateGroupChatStateAfterResponse",
                String.class, String.class, String.class);
        method.setAccessible(true);
        ((Uni<?>) method.invoke(bridge, "chat", "character", "owner")).await().indefinitely();
        assertEquals(java.util.List.of("waiting"), calls);
        verify(bridge.groupChatService, never()).selectNextCharacter(anyString());
    }

    @Test void anEmptyCastReleasesTheSessionInitClaim() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        bridge.mysqlPool = pool((sql, params) -> {
            if (sql.contains("SET groupState = 'CHARACTER_RESPONDING'")) return affected(1);
            if (sql.contains("SELECT cc.characterId")) return rows();
            if (sql.contains("SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL")) {
                calls.add("released");
                return affected(1);
            }
            throw new AssertionError("Unexpected query: " + sql);
        });
        CompletableFuture<JsonObject> changed = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group:chat:state-changed")
                .handler(event -> changed.complete(event.body()));

        Method method = LLMBridge.class.getDeclaredMethod("triggerInitialCharacterResponse",
                String.class, String.class);
        method.setAccessible(true);
        method.invoke(bridge, "chat", "owner");

        assertEquals(java.util.List.of("released"), calls);
        assertEquals("WAITING_FOR_USER", changed.get(2, TimeUnit.SECONDS).getString("groupState"),
                "an init with nobody to speak must not leave the conversation responding");
    }

    @Test void aDeliveredResponseSurvivesAFailedHandoffToTheNextSpeaker() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        bridge.mysqlPool = pool((sql, params) -> {
            if (sql.contains("SET currentSpeakerId = NULL")) return affected(1);
            if (sql.contains("UPDATE conversation_character")) return affected(1);
            if (sql.contains("SELECT consecutiveResponseCount")) return rows(row(Map.of(
                    "consecutiveResponseCount", 1, "maxConsecutiveResponses", 3)));
            if (sql.contains("SELECT conversationGUID FROM conversation")) return rows(row(Map.of("conversationGUID", "chat")));
            if (sql.contains("SELECT isRoleplay, agentPipelineEnabled")) return rows(row(Map.of("isRoleplay", false, "agentPipelineEnabled", false)));
            if (sql.contains("SET groupState = 'WAITING_FOR_USER'")) {
                calls.add("waiting");
                return affected(1);
            }
            throw new AssertionError("Unexpected query: " + sql);
        });
        bridge.groupChatService = mock(com.eonmux.chatllm.services.GroupChatService.class);
        when(bridge.groupChatService.selectNextCharacter("chat"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("No active characters available")));
        CompletableFuture<JsonObject> changed = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>consumer("group:chat:state-changed")
                .handler(event -> changed.complete(event.body()));

        Method method = LLMBridge.class.getDeclaredMethod("updateGroupChatStateAfterResponse",
                String.class, String.class, String.class);
        method.setAccessible(true);
        // The message is already saved and streamed. A missing next speaker must
        // end the chain, not fail the turn the client just received.
        ((Uni<?>) method.invoke(bridge, "chat", "character", "owner")).await().indefinitely();

        assertEquals(java.util.List.of("waiting"), calls);
        assertEquals("WAITING_FOR_USER", changed.get(2, TimeUnit.SECONDS).getString("groupState"));
    }

    @Test void cancelledExternalTurnsCannotStartStreamingAfterContextPreparation() throws Exception {
        Method method = LLMBridge.class.getDeclaredMethod("generateExternalStreamingResponse",
                io.vertx.core.json.JsonArray.class, String.class, String.class, String.class,
                JsonObject.class, String.class, String.class, com.eonmux.chatllm.models.Settings.LLMSettings.class);
        method.setAccessible(true);
        Uni<?> response = (Uni<?>) method.invoke(bridge, new io.vertx.core.json.JsonArray(),
                "chat", "owner", "cancelled-turn", null, "character", "Character", null);
        assertThrows(IllegalStateException.class, () -> response.await().indefinitely());
    }

    @SuppressWarnings("unchecked")
    @Test void theCompletedDialogueSettlesBeforeNarrationIsSavedAndThePlayerTurnReopens() throws Exception {
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var request = new CompletableFuture<Message<JsonObject>>();
        vertx.eventBus().<JsonObject>consumer("roleplay.pipeline.process").handler(message -> {
            order.add("pipeline"); request.complete(message);
        }).completionHandlerAndAwait();
        bridge.storyMessageService = mock(com.eonmux.chatllm.services.StoryMessageService.class);
        when(bridge.storyMessageService.narrate(eq("chat"), eq("round:last-reply"), anyString(), isNull()))
                .thenAnswer(call -> Uni.createFrom().deferred(() -> { order.add("narration saved"); return Uni.createFrom().voidItem(); }));
        bridge.config = mock(com.eonmux.chatllm.config.AppConfig.class);
        when(bridge.config.getLlmConversationHistoryLimit()).thenReturn(20);
        bridge.mysqlPool = pool((sql, params) -> {
            if (sql.contains("SELECT conversationGUID FROM conversation")) return rows(row(Map.of("conversationGUID", "chat")));
            if (sql.contains("SELECT isRoleplay")) return rows(row(Map.of("isRoleplay", true, "agentPipelineEnabled", true)));
            if (sql.contains("SELECT messageUUID, content")) return rows(row(Map.of("messageUUID", "player-decision", "content", "Let's move to the tavern", "roundLastMessageId", "last-reply")));
            if (sql.contains("SELECT content, role, displayName")) return rows(row(Map.of("content", "I follow the party.", "role", "assistant", "displayName", "Elara", "characterId", "elara")));
            if (sql.contains("SELECT c.conversationGUID")) return rows(row(Map.of("conversationGUID", "chat")));
            if (sql.contains("INSERT INTO message")) { order.add("narration saved"); return affected(1); }
            if (sql.contains("SET messageCount")) return affected(1);
            if (sql.contains("SET groupState = 'WAITING_FOR_USER'")) { order.add("waiting"); return affected(1); }
            throw new AssertionError("Unexpected query: " + sql);
        });
        Method method = LLMBridge.class.getDeclaredMethod("settleGroupRound", String.class, String.class);
        method.setAccessible(true);
        var done = ((Uni<?>) method.invoke(bridge, "chat", "character")).subscribeAsCompletionStage();
        Message<JsonObject> pipeline = request.get(2, TimeUnit.SECONDS);
        assertTrue(pipeline.body().getBoolean("postDialogue"));
        assertEquals("last-reply", pipeline.body().getString("roundLastMessageId"));
        assertTrue(pipeline.body().getJsonArray("recentMessages").getJsonObject(0).getString("content").contains("follow"));
        assertEquals(java.util.List.of("pipeline"), order);
        assertFalse(done.toCompletableFuture().isDone());
        pipeline.reply(new JsonObject().put("narration", "The party followed the tavern lane into the Tavern."));
        done.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(java.util.List.of("pipeline", "narration saved", "waiting"), order);
    }

    @Test void cancellingAStreamTerminatesItsTaskAndReleasesTheNextQueuedTurn() throws Exception {
        bridge.messageBuffer = mock(com.eonmux.chatllm.services.StreamingMessageBuffer.class);
        var started = new CompletableFuture<Boolean>();
        var next = new CompletableFuture<Boolean>();
        var ended = new java.util.concurrent.atomic.AtomicInteger();
        Method queue = LLMBridge.class.getDeclaredMethod("queueLlmRequest", Runnable.class);
        Method complete = LLMBridge.class.getDeclaredMethod("llmRequestComplete");
        Method cancel = LLMBridge.class.getDeclaredMethod("handleCancellation", String.class, String.class);
        queue.setAccessible(true); complete.setAccessible(true); cancel.setAccessible(true);
        var buffersField = LLMBridge.class.getDeclaredField("activeBuffers"); buffersField.setAccessible(true);
        @SuppressWarnings("unchecked") var buffers = (Map<String, com.eonmux.chatllm.services.SSEStreamBuffer>) buffersField.get(bridge);
        queue.invoke(bridge, (Runnable) () -> Uni.createFrom().<JsonObject>emitter(emitter -> {
            var buffer = new com.eonmux.chatllm.services.SSEStreamBuffer(vertx, "chat", data -> {},
                    () -> emitter.complete(new JsonObject()), emitter::fail);
            buffers.put("chat", buffer); buffer.start(); started.complete(true);
        }).subscribe().with(ignored -> fail("Cancelled output must not be saved"), error -> {
            ended.incrementAndGet();
            try { complete.invoke(bridge); } catch (Exception e) { throw new RuntimeException(e); }
        }));
        started.get(2,TimeUnit.SECONDS);
        queue.invoke(bridge,(Runnable) () -> next.complete(true));
        assertFalse(next.isDone());
        cancel.invoke(bridge,"chat","Player replaced the message");
        assertTrue(next.get(2,TimeUnit.SECONDS), "Cancellation must not hold the queue until the absolute generation timeout");
        assertEquals(1,ended.get());
        cancel.invoke(bridge,"chat","Repeated cancellation");
        assertEquals(1,ended.get());
    }

    private Uni<Void> trigger() throws Exception {
        Method method = LLMBridge.class.getDeclaredMethod("triggerNextCharacter", String.class, JsonObject.class, String.class);
        method.setAccessible(true);
        return (Uni<Void>) method.invoke(bridge, "chat", new JsonObject().put("id", "character").put("name", "Character"), "owner");
    }
}
