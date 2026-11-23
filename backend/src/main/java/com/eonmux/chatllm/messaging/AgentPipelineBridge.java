package com.eonmux.chatllm.messaging;

import com.eonmux.chatllm.agents.AgentPipeline;
import com.eonmux.chatllm.agents.PipelineResult;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.services.*;
import com.eonmux.chatllm.map.MapService;
import com.eonmux.chatllm.map.StageService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * CDI bridge that wires the {@link AgentPipeline} to the Vert.x event bus.
 *
 * <p>LLMBridge sends pipeline requests to {@code roleplay.pipeline.process}
 * (lines 906, 1551 in LLMBridge). This bean instantiates the pipeline with
 * all its injected dependencies and registers the event-bus consumer that
 * processes those requests.</p>
 */
@Slf4j
@ApplicationScoped
public class AgentPipelineBridge {

    @Inject Vertx vertx;
    @Inject MySQLPool mysqlPool;
    @Inject AppConfig config;
    @Inject AgentModelConfigService modelConfigService;
    @Inject KnowledgeSieveService knowledgeSieveService;
    @Inject WorldStateService worldStateService;
    @Inject SceneService sceneService;
    @Inject RetrievalService retrievalService;
    @Inject CastingService castingService;
    @Inject CharacterStatService characterStatService;
    @Inject InventoryService inventoryService;
    @Inject RelationshipService relationshipService;
    @Inject MapService mapService;
    @Inject StageService stageService;
    @Inject ConversationProgressService progressService;
    @Inject LocationResidentService residents;
    @Inject BattleService battleService;

    private AgentPipeline pipeline;
    private MessageConsumer<JsonObject> consumer;

    void onStart(@Observes StartupEvent ev) {
        unregisterConsumer();
        pipeline = new AgentPipeline(
            vertx, mysqlPool, config, modelConfigService,
            knowledgeSieveService, worldStateService, sceneService,
            retrievalService, castingService, characterStatService,
            inventoryService, relationshipService, mapService, stageService, battleService);
        pipeline.setProgressService(progressService);
        pipeline.setResidentService(residents);

        consumer = vertx.eventBus().<JsonObject>consumer("roleplay.pipeline.process", message -> {
            JsonObject body = message.body();
            String conversationId = body.getString("conversationId");
            String triggerMessageId = body.getString("triggerMessageId");
            String triggerContent = body.getString("triggerContent");
            JsonArray recentMessages = body.getJsonArray("recentMessages", new JsonArray());

            log.info("[pipeline-bridge] Processing pipeline request for conversation {}", conversationId);

            Uni<PipelineResult> resultUni = pipeline.process(
                conversationId, triggerMessageId, triggerContent, recentMessages,
                body.getBoolean("postDialogue", false), body.getString("roundLastMessageId"));

            resultUni.subscribe().with(
                result -> {
                    JsonObject reply = new JsonObject()
                        .put("failed", result.isFailed())
                        .put("totalDurationMs", result.getTotalDurationMs() != null ? result.getTotalDurationMs() : -1L)
                        .put("narration", result.getNarration() != null ? result.getNarration() : "")
                        .put("arrivalResident", result.getArrivalResident())
                        .put("errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "");
                    if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
                        reply.put("warnings", new JsonArray(result.getWarnings()));
                    }
                    if (!body.getBoolean("postDialogue",false)) progressService.finish(conversationId,"opening:"+conversationId);
                    message.reply(reply);
                },
                error -> {
                    if (!body.getBoolean("postDialogue",false)) progressService.finish(conversationId,"opening:"+conversationId);
                    log.error("[pipeline-bridge] Pipeline failed for conversation {}: {}",
                        conversationId, error.getMessage(), error);
                    message.reply(new JsonObject()
                        .put("failed", true)
                        .put("errorMessage", error.getMessage())
                        .put("narration", "")
                        .put("totalDurationMs", -1L));
                });
        });

        log.info("Agent pipeline bridge ready (consumer registered on roleplay.pipeline.process)");
    }
    void onStop(@Observes ShutdownEvent ev) {
        unregisterConsumer();
    }

    private void unregisterConsumer() {
        if (consumer == null) return;
        // Vert.x survives a Quarkus dev reload. Leaving this registration behind
        // round-robins future requests into pipelines from obsolete class loaders.
        consumer.unregister().await().atMost(java.time.Duration.ofSeconds(5));
        consumer = null;
    }
}
