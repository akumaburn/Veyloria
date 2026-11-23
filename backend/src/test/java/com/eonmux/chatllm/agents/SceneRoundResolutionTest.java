package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.map.*;
import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.services.WorldStateService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneRoundResolutionTest {
    Vertx vertx;
    AgentPipeline pipeline;
    AgentContext context;
    StageContext before, after;
    StageService stage = mock(StageService.class);
    WorldStateService world = mock(WorldStateService.class);
    List<String> order = new ArrayList<>();

    @BeforeEach void setup() {
        vertx = Vertx.vertx();
        var config = mock(AppConfig.class);
        when(config.getAgentSkepticMaxRetries()).thenReturn(2);
        pipeline = new AgentPipeline(vertx, pool((sql, params) -> rows(row(Map.of("conversationGUID", "chat")))),
            config, null, null, world, null, null, null, null, null, null, null, stage, null);
        before = PlayerMovementIntentTest.stage();
        var inn = before.getAreas().stream().filter(a -> "Inn".equals(a.getName())).findFirst().orElseThrow();
        before.setConnections(List.of(LocalAreaConnection.builder().fromAreaId(before.getArea().getLocalAreaId())
            .toAreaId(inn.getLocalAreaId()).label("inn lane").build()));
        after = StageContext.builder().node(before.getNode()).area(inn).build();
        context = AgentContext.builder().conversationId("chat").postDialogue(true).triggerMessageId("player")
            .roundLastMessageId("last-reply").triggerContent("Let's check the Inn first.").stage(before).playerName("Amir").build();
        when(world.getOrCreateWorldState("chat")).thenReturn(Uni.createFrom().item(WorldState.builder().build()));
        when(world.getWorldContextForPrompt("chat")).thenReturn(Uni.createFrom().item(new JsonObject()));
        when(world.updateWorldState(eq("chat"), any())).thenAnswer(call -> {
            order.add("state applied"); return Uni.createFrom().nullItem();
        });
        when(stage.getStageContext("chat")).thenReturn(Uni.createFrom().item(after));
        when(stage.move("chat", null, "Inn")).thenAnswer(call -> {
            order.add("move validated");
            return Uni.createFrom().item(StageMovementResult.builder().kind(StageMovementPolicy.Kind.AREA).stage(after).build());
        });
    }
    @AfterEach void close() { vertx.closeAndAwait(); }
    static AgentResult decision(JsonObject value) {
        return AgentResult.builder().status("success").structuredOutput(value).build();
    }
    static AgentResult rejection(String reason) {
        return AgentResult.builder().status("rejected").structuredOutput(new JsonObject().put("reason", reason)).build();
    }
    @Test void travelIsDecidedAppliedAndConfirmedBeforeNarrationAndSkipsTheInteractionRequest() {
        pipeline.resolvePostDialogueScene(context, current -> {
            order.add("travel question");
            verify(stage, never()).move(anyString(), any(), any());
            return Uni.createFrom().item(decision(new JsonObject().put("move", true).put("currentArea", "Inn")));
        }, current -> { fail("Travel and interaction must not be bundled or double-resolved"); return null; }).await().indefinitely();
        assertTrue(order.indexOf("travel question") < order.indexOf("move validated"));
        assertTrue(order.indexOf("move validated") < order.indexOf("state applied"));
        assertTrue(context.isSceneResolutionComplete());
        assertTrue(context.getStageMoveDescription().contains("Village Green to the Inn via inn lane"));
        AgentPipeline.ensureRequiredNarration(context);
        assertTrue(context.getNarratorOutput().contains("Village Green to the Inn"));
    }
    @Test void withoutTravelASeparateInteractionQuestionProducesARequiredResultBlock() {
        context.setTriggerContent("I examine the ledger.");
        when(stage.getStageContext("chat")).thenReturn(Uni.createFrom().item(before));
        pipeline.resolvePostDialogueScene(context, current -> {
            order.add("travel question"); return Uni.createFrom().item(decision(new JsonObject().put("move", false)));
        }, current -> {
            assertEquals(List.of("travel question"), order);
            order.add("interaction question");
            return Uni.createFrom().item(decision(new JsonObject().put("occurred", true).put("result", "Amir examined the ledger.")));
        }).await().indefinitely();
        verify(stage, never()).move(anyString(), any(), any());
        context.setNarratorOutput("[NO_NARRATION]");
        AgentPipeline.ensureRequiredNarration(context);
        assertEquals("Amir examined the ledger.", context.getNarratorOutput());
    }
    @Test void aCommittedMoveSurvivesAFailureInTheRemainingMovementBookkeeping() {
        when(stage.move("chat", null, "Inn")).thenReturn(Uni.createFrom().failure(new IllegalStateException("Visit refresh failed after commit")));
        pipeline.resolvePostDialogueScene(context,
            current -> Uni.createFrom().item(decision(new JsonObject().put("move", true).put("currentArea", "Inn"))),
            current -> { fail("Actual movement must still require arrival narration"); return null; }).await().indefinitely();
        assertNotNull(context.getStageMoveDescription());
        AgentPipeline.ensureRequiredNarration(context);
        assertTrue(context.getNarratorOutput().contains("to the Inn"));
    }
    @Test void revisionsCannotEraseTheArrivalEvenIfTheReviewerThenApprovesTheEmptyRevision() {
        context.setStageMoveDescription("from the Village Green to the Inn via inn lane");
        context.setNarratorOutput("The party entered the Inn.");
        var reviews = new AtomicInteger();
        pipeline.validateNarrationWithRetry(context, 0, current -> {
            assertFalse(current.getNarratorOutput().isBlank());
            return Uni.createFrom().item(reviews.getAndIncrement() == 0 ? rejection("Revise the atmosphere")
                : AgentResult.builder().status("success").build());
        }, (current, feedback) -> Uni.createFrom().item(AgentResult.builder().status("success").content("").build()))
            .await().indefinitely();
        assertEquals(2, reviews.get());
        assertTrue(context.getNarratorOutput().contains("Village Green to the Inn via inn lane"));
    }
    @Test void transportFailuresInRevisionOrReviewRetainTheResolvedEvent() {
        context.setInteractionResult("Amir examined the ledger.");
        context.setNarratorOutput("Amir examined the ledger.");
        pipeline.validateNarrationWithRetry(context, 0, current -> Uni.createFrom().item(rejection("Clarify the action")),
            (current, feedback) -> Uni.createFrom().failure(new IllegalStateException("Model unavailable"))).await().indefinitely();
        assertEquals("Amir examined the ledger.", context.getNarratorOutput());
    }
    @Test void aTruncatedRejectionWithNoFeedbackDoesNotLaunchAnAimlessRewrite() {
        context.setStageMoveDescription("from the Village Green to the Inn via inn lane");
        context.setNarratorOutput("The party entered the Inn.");
        pipeline.validateNarrationWithRetry(context, 0, current -> Uni.createFrom().item(rejection("")),
            (current, feedback) -> { fail("There is no actionable revision instruction"); return null; }).await().indefinitely();
        assertEquals("The party entered the Inn.", context.getNarratorOutput());
    }
    @Test void ordinaryConversationStillDoesNotRequireANarrationBlock() {
        context.setTriggerContent("Good morning.");
        when(stage.getStageContext("chat")).thenReturn(Uni.createFrom().item(before));
        pipeline.resolvePostDialogueScene(context, current -> Uni.createFrom().item(decision(new JsonObject().put("move", false))),
            current -> Uni.createFrom().item(decision(new JsonObject().put("occurred", false)))).await().indefinitely();
        AgentPipeline.ensureRequiredNarration(context);
        assertNull(context.getNarratorOutput());
    }
    @Test void activeCombatCannotBeResolvedThroughEitherSceneQuestion() {
        context.setBattleContext(new JsonObject().put("status", "ACTIVE"));
        pipeline.resolvePostDialogueScene(context, current -> { fail("Battle service owns combat"); return null; },
            current -> { fail("Battle service owns combat"); return null; }).await().indefinitely();
        verifyNoInteractions(stage);
    }
    @Test void reviewerCountsThePlayerSeparatelyFromTheNpcRoster() {
        context.setCharacterIdsInScene(List.of("elara", "ryn", "mira"));
        context.setCharacterNames(new JsonObject().put("elara", "Elara").put("ryn", "Ryn").put("mira", "Mira"));
        context.setStageMoveDescription("from the Village Green to the Inn via inn lane");
        var prompt = new StringBuilder();
        new SkepticAgent(null, null, null, null).buildNarrationEvalPrompt(prompt, context);
        assertTrue(prompt.toString().contains("Amir is present in addition to the NPC roster"));
        assertTrue(prompt.toString().contains("SERVER-CONFIRMED TRAVEL THIS ROUND"));
    }
    @Test void regularRoundsLoadTheActualPlayerFromTheSavedTriggerRatherThanAssumingTheHost() {
        var identityPipeline = new AgentPipeline(vertx, pool((sql, params) -> {
            assertEquals("player", params.getString(0));
            assertEquals("chat", params.getString(1));
            assertTrue(sql.contains("role = 'user'"));
            return rows(row(Map.of("ownerId", "visiting-player", "displayName", "Amir")));
        }), mock(AppConfig.class), null, null, null, null, null, null, null, null, null, null, null, null);
        context.setUserId("host"); context.setPlayerName(null);
        identityPipeline.hydrateTriggerPlayer(context).await().indefinitely();
        assertEquals("visiting-player", context.getUserId());
        assertEquals("Amir", context.getPlayerName());
    }

    @Test void fullPipelineKeepsTheArrivalThroughBookkeepingAndAControlOnlyEditorialRevision() {
        var config = mock(AppConfig.class);
        when(config.getAgentSkepticMaxRetries()).thenReturn(2);
        when(config.isAgentSkepticNarrationGateEnabled()).thenReturn(true);
        var models = mock(com.eonmux.chatllm.services.AgentModelConfigService.class);
        when(models.getConfig("chat")).thenReturn(Uni.createFrom().item(
            com.eonmux.chatllm.models.AgentModelConfig.builder().defaultModel("test-model").build()));
        var inventory = mock(com.eonmux.chatllm.services.InventoryService.class);
        when(inventory.recentActions("chat")).thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonArray()));
        var db = pool((sql, params) -> sql.startsWith("SELECT battleId") ? rows()
            : rows(row(Map.of("conversationGUID", "chat"))));
        var completePipeline = spy(new AgentPipeline(vertx, db, config, models, null, world, null, null,
            null, null, inventory, null, null, stage, null));
        doReturn(Uni.createFrom().item(context)).when(completePipeline).buildContext(eq("chat"), eq("player"), anyString(), any());
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var narratorCalls = new AtomicInteger();
        var reviewerCalls = new AtomicInteger();
        var consumer = vertx.eventBus().<JsonObject>consumer("llm.agent.completion").handler(message -> {
            String system = message.body().getJsonArray("messages").getJsonObject(0).getString("content");
            String text;
            if (SceneDecisionAgent.ENCOUNTER_PROMPT.equals(system)) {
                calls.add("encounter"); text = "{\"encounter\":false}";
            } else if (SceneDecisionAgent.TRAVEL_PROMPT.equals(system)) {
                calls.add("travel"); text = "{\"move\":true,\"currentArea\":\"Inn\"}";
            } else if (AgentPromptTemplates.MUSE_SYSTEM.equals(system)) {
                calls.add("muse"); text = "{}";
            } else if (system.contains("Judge ONLY ")) {
                calls.add("accountant");
                assertTrue(message.body().getJsonArray("messages").getJsonObject(1).getString("content").contains("SCENE RESOLUTION ALREADY COMPLETE"));
                text = "{\"change\":null}";
            } else if (AgentPromptTemplates.NARRATOR_SYSTEM.equals(system)) {
                calls.add("narrator");
                assertEquals("Inn", context.getStage().getArea().getName());
                text = narratorCalls.getAndIncrement() == 0 ? "The party entered the Inn." : "[IMAGE: The Inn interior]";
            } else if (AgentPromptTemplates.SKEPTIC_NARRATION_SYSTEM.equals(system)) {
                calls.add("review");
                text = reviewerCalls.getAndIncrement() == 0
                    ? "{\"approved\":false,\"reason\":\"Use simpler wording\"}" : "{\"approved\":true,\"reason\":\"Approved\"}";
            } else { message.fail(1, "Unexpected extra decision"); return; }
            message.reply(new JsonObject().put("choices", new io.vertx.core.json.JsonArray().add(new JsonObject()
                .put("message", new JsonObject().put("content", text)))));
        });
        consumer.completionHandlerAndAwait();
        try {
            var result = completePipeline.process("chat", "player", context.getTriggerContent(), null, true, "last-reply")
                .await().atMost(java.time.Duration.ofSeconds(10));
            assertFalse(result.isFailed(), result.getErrorMessage());
            assertTrue(result.getNarration().contains("Village Green to the Inn via inn lane"), result.getNarration());
            assertEquals(2, narratorCalls.get());
            assertTrue(calls.indexOf("travel") < calls.indexOf("accountant"));
            verify(stage, times(1)).move("chat", null, "Inn");
        } finally { consumer.unregisterAndAwait(); }
    }

    @Test void anUnavailableJudgmentCannotBeReportedAsNoInteraction() {
        context.setTriggerContent("I inspect the ledger.");
        when(stage.getStageContext("chat")).thenReturn(Uni.createFrom().item(before));
        assertThrows(IllegalStateException.class, () -> pipeline.resolvePostDialogueScene(context,
            current -> Uni.createFrom().item(decision(new JsonObject().put("move",false))),
            current -> Uni.createFrom().item(AgentResult.error("interaction_decision","Invalid response"))).await().indefinitely());
        assertFalse(context.isSceneResolutionComplete());
    }
    @Test void progressOperationsAreAwaitedAndOrderedBeforeNarration() throws Exception {
        var stats=mock(com.eonmux.chatllm.services.CharacterStatService.class);
        var relationships=mock(com.eonmux.chatllm.services.RelationshipService.class);
        var completed=new java.util.concurrent.CompletableFuture<com.eonmux.chatllm.models.CharacterStat>();
        when(stats.modifyStat("chat","elara","HP",-2)).thenReturn(Uni.createFrom().completionStage(completed));
        when(relationships.adjustRelationship(eq("chat"),eq("elara"),eq("ryn"),eq("trust"),eq(1.0),anyString()))
            .thenAnswer(call -> { order.add("relationship"); return Uni.createFrom().nullItem(); });
        var ordered=new AgentPipeline(vertx,pool((sql,params)->rows(row(Map.of("conversationGUID","chat")))),
            mock(AppConfig.class),null,null,null,null,null,null,stats,null,relationships,null,null,null);
        var output=new JsonObject().put("statChanges",new io.vertx.core.json.JsonArray().add(
            new JsonObject().put("characterId","elara").put("statName","HP").put("delta",-2)))
            .put("relationshipChanges",new io.vertx.core.json.JsonArray().add(
                new JsonObject().put("fromId","elara").put("toId","ryn").put("type","trust").put("intensityDelta",1)));
        var done=ordered.applyAccountantProgress(context,output,new ArrayList<>()).invoke(()->order.add("narration")).subscribeAsCompletionStage();
        assertFalse(done.toCompletableFuture().isDone()); verifyNoInteractions(relationships);
        completed.complete(null); done.toCompletableFuture().get(2,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(List.of("relationship","narration"),order);
    }

}
