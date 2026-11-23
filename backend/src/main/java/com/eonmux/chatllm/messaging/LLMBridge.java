package com.eonmux.chatllm.messaging;

import com.eonmux.chatllm.utils.PronounHelper;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.utils.CharacterRosterGenerator;
import com.eonmux.chatllm.llm.LLMParameterBuilder;
import com.eonmux.chatllm.llm.ModelServerInstance;
import com.eonmux.chatllm.llm.ModelServerPool;
import com.eonmux.chatllm.llm.PromptBuilder;
import com.eonmux.chatllm.llm.TemplateDetector;
import com.eonmux.chatllm.map.MapService;
import com.eonmux.chatllm.map.ScenarioSetting;
import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.map.StagePrompt;
import com.eonmux.chatllm.map.StageService;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.models.Settings;
import com.eonmux.chatllm.services.GroupChatService;
import com.eonmux.chatllm.services.InventoryService;
import com.eonmux.chatllm.services.SSEStreamBuffer;
import com.eonmux.chatllm.services.StreamingMessageBuffer;
import com.eonmux.chatllm.services.WorldStateService;
import com.eonmux.chatllm.services.StoryMessageService;
import com.eonmux.chatllm.services.ConversationProgressService;
import com.eonmux.chatllm.services.LocationResidentService;
import com.google.gson.Gson;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.Message;
import io.vertx.mutiny.core.eventbus.MessageConsumer;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Event-bus bridge for LLM generation, agent completions, group chat, and
 * roleplay session orchestration. Replaces the deleted LLMVerticle by
 * registering CDI-managed consumers on the same addresses that producers
 * (MessageService, MutationResolver, BaseAgent, MapResolver, JourneyService,
 * GroupChatResolver, RoleplayResolver, SubscriptionResolver, QueryResolver,
 * CharacterService) still publish to.
 *
 * <p>Addresses registered:
 * <ul>
 *   <li>{@code llm.generate}               - single-assistant streaming generation</li>
 *   <li>{@code llm.generate.character}     - character sheet generation (non-streaming)</li>
 *   <li>{@code llm.status}                 - pool / external-server status</li>
 *   <li>{@code llm.reload}                 - reload all model servers</li>
 *   <li>{@code llm.statistics}             - aggregated request counters</li>
 *   <li>{@code llm.stop}                   - cancel active generation</li>
 *   <li>{@code group.character.respond}    - group chat character turn</li>
 *   <li>{@code roleplay.session.init}      - roleplay init (pipeline + map)</li>
 *   <li>{@code llm.agent.completion}       - non-streaming agent completion (local pool routing)</li>
 *   <li>{@code llm.health.restart.requested} - restart request from health verticle</li>
 *   <li>{@code llm.health.restored}        - notify when health restored</li>
 * </ul>
 */
@Slf4j
@ApplicationScoped
public class LLMBridge {

    // ---------- Injected CDI beans ----------

    @Inject
    Vertx vertx;

    @Inject
    Pool mysqlPool;

    @Inject
    AppConfig config;

    @Inject
    WorldStateService worldStateService;

    @Inject
    StoryMessageService storyMessageService;
    @Inject ConversationProgressService progressService;
    @Inject LocationResidentService residents;

    @Inject
    InventoryService inventoryService;

    @Inject
    MapService mapService;

    @Inject
    StageService stageService;

    @Inject
    StreamingMessageBuffer messageBuffer;

    @Inject
    GroupChatService groupChatService;

    // ---------- Instance state (manually managed, not CDI) ----------

    private ModelServerPool serverPool;
    private String currentTemplate = "CHATML";
    private final Gson gson = new Gson();

    // Statistics tracking
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalTokensGenerated = new AtomicLong(0);
    private final Instant startTime = Instant.now();

    // Track active streaming EventSources by conversationId for cancellation
    private final ConcurrentHashMap<String, EventSource> activeStreams = new ConcurrentHashMap<>();
    // Track active SSE buffers for proper cleanup
    private final ConcurrentHashMap<String, SSEStreamBuffer> activeBuffers = new ConcurrentHashMap<>();
    // Cache for conversation system prompts
    private final ConcurrentHashMap<String, String> conversationSystemPrompts = new ConcurrentHashMap<>();

    // LLM Request Queue - ArrayDeque has better cache locality than LinkedList
    private final Queue<Runnable> llmRequestQueue = new ArrayDeque<>();
    private final AtomicBoolean isProcessingLlmRequest = new AtomicBoolean(false);
    private final Object queueLock = new Object();

    // Track request IDs that were cancelled
    private final Map<String, String> scheduledGroupTurns = new ConcurrentHashMap<>();
    private final Map<String, String> activeGroupTurns = new ConcurrentHashMap<>();

    private final Set<String> cancelledRequestIds = ConcurrentHashMap.newKeySet();

    // Auto-recovery state
    private final AtomicInteger consecutiveRestartFailures = new AtomicInteger(0);
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);

    // Reusable WebClient for agent pipeline completion requests (local mode only)
    private volatile WebClient agentWebClient;

    // Singleton OkHttpClient shared across all SSE streaming + character gen calls.
    // Built once in onStart, torn down in onStop. readTimeout=0 / callTimeout=0
    // are required for SSE streams (otherwise idle reads would abort the stream).
    private OkHttpClient sseHttpClient;

    // Consumers registered on the Vert.x event bus. Kept so shutdown can remove
    // them; dev-mode reloads otherwise leave stale consumers that still receive
    // round-robin traffic after their HTTP client is closed.
    private final java.util.List<MessageConsumer<JsonObject>> consumers = new java.util.ArrayList<>();

    // Periodic cleanup timer id for StreamingMessageBuffer.cleanupExpired().
    private long cleanupTimerId = -1;

    // --- DEFAULT MAP GENERATION CONFIG ---
    // Must match MapResolver.generateProceduralMap() defaults for consistency
    private static final int DEFAULT_MAP_GRID_RESOLUTION = 512;
    private static final float DEFAULT_MAP_WATER_LEVEL = 0.3f;
    private static final int DEFAULT_MAP_LOCATION_COUNT = -1;
    private static final String DEFAULT_MAP_THEME = "temperate";
    private static final String DEFAULT_MAP_SCALE = "region";

    private static final Set<String> SETTLEMENT_TYPES = Set.of("town", "village", "city", "camp", "port");

    // --- PRE-COMPILED PATTERNS FOR PERFORMANCE ---
    private static final Pattern CODE_BLOCK_JSON = Pattern.compile("```json\\s*\\n([\\s\\S]*?)\\n?```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_BLOCK_GENERIC = Pattern.compile("```\\s*\\n([\\s\\S]*?)\\n?```", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRACT_NAME_QUOTED = Pattern.compile("(?i)name[^a-z]*[\"']?([A-Z][a-zA-Z\\s]{1,50})[\"']?");
    private static final Pattern EXTRACT_NAME_CAPS = Pattern.compile("(?:^|\\n)\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})\\s*(?:\\n|$)");
    private static final Pattern JSON_KEY_REPAIR_PATTERN = Pattern.compile("(?m)^(\\s*)([a-zA-Z_][a-zA-Z0-9_]*)\\s*:");

    // Cached stop sequences / GPT-OSS result (template rarely changes)
    private volatile JsonArray cachedStopSequences = null;
    private volatile String cachedStopSequencesTemplate = null;
    private volatile Boolean cachedIsGptOss = null;
    private volatile String cachedIsGptOssTemplate = null;

    // =======================================================================
    // Lifecycle
    // =======================================================================

    void onStart(@Observes StartupEvent ev) {
        log.info("Starting LLM event-bus bridge...");

        // Build the shared OkHttpClient once. All SSE streams and character-gen
        // calls reuse this instance so dispatcher thread pools and connection
        // pools are shared (fixes per-request thread/connection leak).
        // readTimeout(0) / callTimeout(0) permit long-running SSE streams;
        // connection pool matches the original per-request configuration.
        sseHttpClient = newHttpClient();

        if (config.isLlmExternalServer()) {
            log.info("LLM external server mode enabled. Base URL: {}, Model: {}",
                    config.getLlmExternalBaseUrl(), config.getLlmExternalModel());
            if (config.getLlmExternalBaseUrl().isEmpty()) {
                log.warn("LLM_EXTERNAL_BASE_URL is empty - external API calls will fail");
            }
            if (config.getLlmExternalModel().isEmpty()) {
                log.warn("LLM_EXTERNAL_MODEL is empty - external API calls may fail");
            }
            registerConsumers();
            publishLlmStatus();
        } else {
            // Local mode: initialize Model Server Pool and start default server
            int maxConcurrentServers = 1;
            long idleTimeoutSeconds = 300;

            try {
                serverPool = new ModelServerPool(vertx, config, maxConcurrentServers, idleTimeoutSeconds);
                log.info("Model Server Pool initialized: maxServers={}, idleTimeout={}s",
                        maxConcurrentServers, idleTimeoutSeconds);
            } catch (Exception e) {
                log.error("Failed to initialize ModelServerPool; LLM handlers will reply with errors", e);
                serverPool = null;
            }

            // Register consumers FIRST so pre-warm failure doesn't block the bridge.
            registerConsumers();

            publishLlmStatus();

            if (serverPool != null) {
                serverPool.getDefaultServer("system-init")
                        .chain(defaultServer -> {
                            log.info("Default model server started: {}", defaultServer);
                            return detectTemplateForServer(defaultServer);
                        })
                        .subscribe().with(
                                v -> {
                                    log.info("LLM bridge pre-warm complete");
                                    publishLlmStatus();
                                },
                                err -> {
                                    log.error("LLM bridge pre-warm failed (handlers still active)", err);
                                    publishLlmStatus();
                                });
            }
        }

        // Periodically evict StreamingMessageBuffer entries older than 5 minutes
        // so abandoned streams (client disconnected mid-generation) don't leak.
        cleanupTimerId = vertx.setPeriodic(60_000L, id -> {
            try {
                messageBuffer.cleanupExpired();
            } catch (Exception e) {
                log.warn("StreamingMessageBuffer cleanup failed", e);
            }
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        log.info("Stopping LLM event-bus bridge and Model Server Pool...");
        unregisterConsumers();
        cleanupResources();
        if (serverPool != null) {
            serverPool.stopAll()
                    .subscribe().with(
                            v -> log.info("All model servers stopped successfully"),
                            err -> log.error("Failed to stop some model servers", err));
        }
    }

    private void publishLlmStatus() {
        JsonObject status = new JsonObject()
            .put("isRunning", serverPool != null)
            .put("modelLoaded", serverPool != null)
            .put("currentModel", config.getLlmModelPath() != null ? config.getLlmModelPath() : "")
            .put("availableMemory", Runtime.getRuntime().freeMemory())
            .put("usedMemory", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .put("providerType", config.isLlmExternalServer() ? "external" : "local");
        vertx.eventBus().publish("llm:status:changed", status);
    }

    private void registerConsumers() {
        var bus = vertx.eventBus();

        consumers.add(bus.<JsonObject>consumer("llm.generate").handler(this::handleGenerateMessage));
        consumers.add(bus.<JsonObject>consumer("llm.generate.character").handler(this::handleGenerateCharacter));
        consumers.add(bus.<JsonObject>consumer("llm.status").handler(this::handleGetStatus));
        consumers.add(bus.<JsonObject>consumer("llm.reload").handler(this::handleReloadModel));
        consumers.add(bus.<JsonObject>consumer("llm.statistics").handler(this::handleGetStatistics));
        consumers.add(bus.<JsonObject>consumer("llm.stop").handler(this::handleStopGeneration));
        consumers.add(bus.<JsonObject>consumer("group.character.respond").handler(this::handleGroupCharacterRespond));
        consumers.add(bus.<JsonObject>consumer("group.conversation.paused").handler(this::handleGroupPause));
        consumers.add(bus.<JsonObject>consumer("roleplay.session.init").handler(this::handleRoleplaySessionInit));
        consumers.add(bus.<JsonObject>consumer("llm.agent.completion").handler(this::handleAgentCompletion));

        // Health event listeners for auto-recovery
        consumers.add(bus.<JsonObject>consumer("llm.health.restart.requested").handler(this::handleRestartRequested));
        consumers.add(bus.<JsonObject>consumer("llm.health.restored").handler(this::handleHealthRestored));

        cleanupStuckGroupConversations();
        log.info("LLM event-bus bridge ready ({} consumers registered)", consumers.size());
    }

    /**
     * Removes this instance from the event bus. Without this, a dev-mode reload
     * leaves the old bridge registered while its HTTP client is closed, so
     * round-robin delivery fails for every request that reaches the old instance.
     */
    private void unregisterConsumers() {
        synchronized (consumers) {
            for (MessageConsumer<JsonObject> consumer : consumers) {
                try {
                    consumer.unregister().await().atMost(java.time.Duration.ofSeconds(5));
                } catch (Exception error) {
                    log.warn("Failed to unregister LLM consumer {}", consumer.address(), error);
                }
            }
            consumers.clear();
        }
    }

    private OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }

    /**
     * Returns the shared client, rebuilding it if a previous shutdown closed its
     * dispatcher. A closed dispatcher rejects every new call with
     * {@code InterruptedIOException: executor rejected}.
     */
    private synchronized OkHttpClient sharedHttpClient() {
        if (sseHttpClient == null || sseHttpClient.dispatcher().executorService().isShutdown()) {
            log.warn("Shared HTTP client was closed; rebuilding it");
            sseHttpClient = newHttpClient();
        }
        return sseHttpClient;
    }

    private void cleanupStuckGroupConversations() {
        // CHARACTER_RESPONDING is the only state that implies in-flight work.
        // INITIALIZING and INTERJECTION_PENDING describe work that a restart
        // has already lost, so leaving them set would lock the composer.
        mysqlPool.preparedQuery(
                "UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL "
                        + "WHERE groupState IN ('CHARACTER_RESPONDING', 'INITIALIZING', 'INTERJECTION_PENDING')")
                .execute()
                .subscribe().with(
                        result -> {
                            if (result.rowCount() > 0) {
                                log.warn("Cleaned up {} group conversations stuck in a claimed state",
                                        result.rowCount());
                            }
                        },
                        err -> log.error("Failed to cleanup stuck group conversations", err));
    }

    // =======================================================================
    // Health / Restart handlers
    // =======================================================================

    private void handleRestartRequested(Message<JsonObject> message) {
        JsonObject body = message.body();
        String reason = body.getString("reason", "unknown");
        int attempt = body.getInteger("attempt", 1);

        if (config.isLlmExternalServer()) {
            log.info("LLM restart requested in external mode — cannot restart external server. Reason: {}", reason);
            return;
        }

        if (serverPool == null) {
            log.warn("LLM restart requested but server pool is not initialized");
            return;
        }

        long delayMs = getRestartDelay(consecutiveRestartFailures.get());
        log.warn("LLM server restart scheduled (attempt {}, delay {}ms): {}", attempt, delayMs, reason);

        vertx.setTimer(Math.max(delayMs, 1), timerId -> {
            if (!restartInProgress.compareAndSet(false, true)) {
                log.debug("LLM restart already in progress, skipping this attempt");
                return;
            }

            String modelPath = config.getLlmModelPath();
            serverPool.restartServer(modelPath)
                    .chain(this::detectTemplateForServer)
                    .subscribe().with(
                            v -> {
                                consecutiveRestartFailures.set(0);
                                restartInProgress.set(false);
                                log.info("LLM server restarted successfully after health event");
                                publishLlmStatus();
                            },
                            err -> {
                                int failures = consecutiveRestartFailures.incrementAndGet();
                                restartInProgress.set(false);
                                log.error("LLM server restart failed (consecutive failures: {}): {}",
                                        failures, err.getMessage());
                                publishLlmStatus();
                            });
        });
    }

    private long getRestartDelay(int consecutiveFailures) {
        switch (consecutiveFailures) {
            case 0: return 0;
            case 1: return 10_000;
            case 2: return 30_000;
            case 3: return 60_000;
            case 4: return 120_000;
            default: return 300_000;
        }
    }

    private void handleHealthRestored(Message<JsonObject> message) {
        log.info("LLM server health restored");
        consecutiveRestartFailures.set(0);

        // Published on the channel systemNotification() subscribes to, with the
        // field shape (type/title/message/severity) the resolver reads.
        JsonObject notification = new JsonObject()
                .put("type", "llm_health")
                .put("title", "LLM server restored")
                .put("message", "LLM server is back online")
                .put("severity", "info");
        vertx.eventBus().publish("system:notifications", notification);
    }

    // =======================================================================
    // Request queue
    // =======================================================================

    private void queueLlmRequest(Runnable requestTask) {
        synchronized (queueLock) {
            llmRequestQueue.offer(requestTask);
            int queueSize = llmRequestQueue.size();
            if (queueSize > 1) {
                log.info("LLM request queued. Queue size: {}", queueSize);
            }
        }
        processNextLlmRequest();
    }

    private void processNextLlmRequest() {
        Runnable nextRequest;

        synchronized (queueLock) {
            if (isProcessingLlmRequest.get()) {
                return;
            }
            nextRequest = llmRequestQueue.poll();
            if (nextRequest != null) {
                isProcessingLlmRequest.set(true);
            }
        }

        if (nextRequest != null) {
            final Runnable request = nextRequest;
            vertx.runOnContext(() -> {
                try {
                    request.run();
                } catch (Exception e) {
                    log.error("Error executing queued LLM request", e);
                    llmRequestComplete();
                }
            });
        }
    }

    private void llmRequestComplete() {
        isProcessingLlmRequest.set(false);
        vertx.setTimer(50L, id -> processNextLlmRequest());
    }

    // =======================================================================
    // Template detection & HTTP client factories
    // =======================================================================

    private Uni<Void> detectTemplateForServer(ModelServerInstance server) {
        WebClient client = createWebClientForServer(server);
        return TemplateDetector.detect(client, server.getModelPath())
                .onItem().invoke(t -> {
                    currentTemplate = t;
                    log.info("Detected template for {}: {}", server.getModelName(), t);
                })
                .onFailure().invoke(e -> log.warn("Failed to detect template for {}, using: {}",
                        server.getModelName(), currentTemplate))
                .eventually(() -> {
                    client.close();
                    return Uni.createFrom().voidItem();
                })
                .replaceWithVoid();
    }

    private WebClient createWebClientForServer(ModelServerInstance server) {
        int timeoutSeconds = (int) (config.getLlmRequestTimeout() / 1000);
        WebClientOptions options = new WebClientOptions()
                .setDefaultHost("127.0.0.1")
                .setDefaultPort(server.getPort())
                .setConnectTimeout(30_000)
                .setIdleTimeout(timeoutSeconds);
        return WebClient.create(vertx, options);
    }

    private void cleanupResources() {
        activeBuffers.values().forEach(SSEStreamBuffer::cancel);
        activeBuffers.clear();
        activeStreams.values().forEach(EventSource::cancel);
        activeStreams.clear();
        synchronized (queueLock) {
            llmRequestQueue.clear();
            isProcessingLlmRequest.set(false);
        }
        cancelledRequestIds.clear();
        activeGroupTurns.clear();
        scheduledGroupTurns.clear();

        // Cancel the periodic StreamingMessageBuffer cleanup timer.
        if (cleanupTimerId >= 0) {
            vertx.cancelTimer(cleanupTimerId);
            cleanupTimerId = -1;
        }

        // Close the reusable agent pipeline WebClient so Vert.x channels are freed.
        if (agentWebClient != null) {
            try {
                agentWebClient.close();
            } catch (Exception e) {
                log.warn("Failed to close agentWebClient", e);
            }
            agentWebClient = null;
        }

        // Tear down the singleton OkHttpClient: shut down its dispatcher thread
        // pool (with bounded await) and evict any pooled connections.
        if (sseHttpClient != null) {
            sseHttpClient.dispatcher().executorService().shutdown();
            try {
                sseHttpClient.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sseHttpClient.connectionPool().evictAll();
        }
    }

    // =======================================================================
    // llm.generate
    // =======================================================================

    @Inject com.eonmux.chatllm.services.CharacterAffinityService affinityService;
    @Inject com.eonmux.chatllm.services.CharacterAlignmentService alignmentService;

    private void handleGenerateMessage(Message<JsonObject> message) {
        totalRequests.incrementAndGet();
        JsonObject request = message.body();
        String conversationId = request.getString("conversationId");
        String userId = request.getString("userId");
        String content = request.getString("content");
        String providedUserName = request.getString("userName");

        handleCancellation(conversationId, "Superseded by new generation");

        String providedAssistantId = request.getString("assistantMessageId");
        final String assistantMessageId = (providedAssistantId != null && !providedAssistantId.isEmpty())
                ? providedAssistantId : UUID.randomUUID().toString();

        JsonObject userSettings = extractUserSettings(request);

        queueLlmRequest(() -> {
            getUserLLMSettings(userId)
                    .chain(llmSettings -> resolveUserDisplayName(userId, providedUserName)
                            .chain(userName -> fetchSystemPrompt(conversationId)
                                    .chain(basePrompt -> fetchCharacterInfo(conversationId)
                                            .chain(info -> Uni.combine().all().unis(
                                                            affinityContext(userId, info.getString("id"), conversationId),
                                                            alignmentContext(userId, info.getString("id"),
                                                                    info.getString("name"), conversationId))
                                                    .asTuple()
                                                    .map(tuple -> {
                                                        String enriched = basePrompt + tuple.getItem1() + tuple.getItem2();
                                                        conversationSystemPrompts.put(conversationId, enriched);
                                                        return enriched;
                                                    })))
                                    .chain(systemPrompt -> getConversationHistory(conversationId)
                                            .chain(history -> {
                                                if (config.isLlmExternalServer()) {
                                                    JsonArray messagesArray = buildMessagesArray(
                                                            systemPrompt, history, content, userName);
                                                    return generateExternalStreamingResponse(messagesArray,
                                                            conversationId, userId, assistantMessageId,
                                                            userSettings, null, null, llmSettings);
                                                } else {
                                                    String prompt = buildPrompt(content, history, conversationId,
                                                            userName, llmSettings);
                                                    return generateStreamingResponseWithSettings(prompt,
                                                            conversationId, userId, assistantMessageId,
                                                            userSettings, null, null, llmSettings);
                                                }
                                            }))))
                    .chain(result -> saveAssistantMessageWithId(conversationId, assistantMessageId,
                            result.getString("content"), result.getJsonObject("generationStats"),
                            result.getString("displayName", "Assistant")))
                    .subscribe().with(
                            assistantMessage -> {
                                cancelledRequestIds.remove(conversationId);
                                successfulRequests.incrementAndGet();
                                message.reply(assistantMessage);
                                llmRequestComplete();
                            },
                            error -> {
                                String userFacingError;
                                int failCode;
                                if (cancelledRequestIds.remove(conversationId)) {
                                    log.info("Message generation cancelled: conversationId={}", conversationId);
                                    userFacingError = "Request cancelled";
                                    failCode = 499;
                                } else if (isConnectionError(error)) {
                                    log.warn("LLM server unavailable for message generation: {}",
                                            error.getMessage());
                                    userFacingError = "LLM server is temporarily unavailable, reconnecting...";
                                    failCode = 503;
                                } else {
                                    failedRequests.incrementAndGet();
                                    log.error("Failed to generate message", error);
                                    userFacingError = error.getMessage() != null
                                            ? error.getMessage() : "Generation failed";
                                    failCode = 500;
                                }
                                // Always publish a final EventBus frame so streaming subscribers
                                // never hang on pre-stream setup errors or post-stream save errors.
                                // The frontend's hasCompleted guard makes a duplicate completion
                                // safe when the streaming method already published its own final
                                // frame (e.g., DB save failed AFTER streaming finished cleanly).
                                publishStreamingError(conversationId, assistantMessageId,
                                        userFacingError, null);
                                message.fail(failCode, userFacingError);
                                llmRequestComplete();
                            });
        });
    }

    private boolean isConnectionError(Throwable error) {
        if (error == null) return false;
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.NoRouteToHostException
                    || cause instanceof java.net.UnknownHostException) {
                return true;
            }
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Connection refused")
                    || msg.contains("Connection reset")
                    || msg.contains("Failed to connect"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // =======================================================================
    // group.character.respond
    // =======================================================================

    private void handleGroupPause(Message<JsonObject> event) {
        String conversationId = event.body().getString("conversationId");
        scheduledGroupTurns.remove(conversationId);
        activeGroupTurns.remove(conversationId);
        handleCancellation(conversationId, "Conversation paused or user interjection approved");
        // Group work is cancelled by its turn identity, so this conversation-wide
        // flag must not accidentally cancel the next turn after resume.
        cancelledRequestIds.remove(conversationId);
    }

    private void handleGroupCharacterRespond(Message<JsonObject> event) {
        totalRequests.incrementAndGet();
        JsonObject request = event.body();
        String conversationId = request.getString("conversationId");
        String characterId = request.getString("characterId");
        String characterName = request.getString("characterName");
        String characterSystemPrompt = request.getString("systemPrompt");
        String userId = request.getString("userId");
        boolean autoTriggered = request.getBoolean("autoTriggered", false);

        log.info("[group-respond] Character '{}' ({}) responding in conversation {}, autoTriggered={}",
                characterName, characterId, conversationId, autoTriggered);

        final String assistantMessageId = UUID.randomUUID().toString();
        // Duplicate delivery must not cancel the response already owning this turn.
        if (activeGroupTurns.putIfAbsent(conversationId, assistantMessageId) != null) {
            if (event.replyAddress() != null) event.reply(new JsonObject().put("accepted", false));
            return;
        }
        if (event.replyAddress() != null) event.reply(new JsonObject().put("accepted", true));

        queueLlmRequest(() -> verifyActiveGroupTurn(conversationId, characterId, assistantMessageId)
                .chain(v -> {
                    // Characters react first. The round-closing pipeline sees every
                    // response and resolves movement/consequences before releasing the turn.
                    return Uni.createFrom().voidItem()
                            .chain(v2 -> fetchCharacterById(characterId))
                            .chain(character -> {
                                String fullSystemPrompt = (character != null)
                                        ? character.getFullSystemPrompt()
                                        : ((characterSystemPrompt != null && !characterSystemPrompt.isEmpty())
                                                ? characterSystemPrompt : getDefaultSystemPrompt());

                                Uni<JsonArray> historyFuture = getConversationHistory(conversationId);
                                Uni<JsonObject> worldCtxFuture = worldStateService
                                        .getWorldContextForPrompt(conversationId)
                                        .onFailure().recoverWithItem(new JsonObject());
                                Uni<java.util.List<com.eonmux.chatllm.models.CharacterInventory>> invFuture =
                                        inventoryService.getInventory(conversationId, characterId)
                                                .onFailure().recoverWithItem(java.util.Collections.emptyList());
                                // The chain's last speaker needs to know it is last,
                                // otherwise a round can end on another plea and leave
                                // the scene exactly where it started.
                                Uni<JsonObject> turnInfoFuture = mysqlPool.preparedQuery("""
                                        SELECT consecutiveResponseCount, maxConsecutiveResponses
                                        FROM conversation WHERE conversationGUID = ?
                                        """).execute(Tuple.of(conversationId))
                                        .map(rows -> {
                                            int responses = 0;
                                            int budget = 3;
                                            if (rows.size() > 0) {
                                                Row row = rows.iterator().next();
                                                Integer count = row.getInteger("consecutiveResponseCount");
                                                Integer max = row.getInteger("maxConsecutiveResponses");
                                                responses = count == null ? 0 : count;
                                                budget = max == null || max <= 0 ? 3 : max;
                                            }
                                            return new JsonObject()
                                                    .put("index", responses + 1)
                                                    .put("budget", budget);
                                        })
                                        .onFailure().recoverWithItem(new JsonObject()
                                                .put("index", 1).put("budget", 3));
                                // Carry the player name with the turn info so the
                                // combine still has one result per input. A tenth
                                // Uni has no asTuple() overload here.
                                turnInfoFuture = turnInfoFuture.chain(info ->
                                        resolveUserDisplayName(userId, null)
                                                .map(name -> info.put("playerName", name)));

                                return Uni.combine().all().unis(historyFuture, worldCtxFuture, invFuture,
                                                affinityContext(userId, characterId, conversationId),
                                                alignmentContext(userId, characterId, characterName, conversationId),
                                                castPresenceContext(conversationId),
                                                stageService.getStageContext(conversationId)
                                                        .onFailure().recoverWithItem(StageContext.empty()),
                                                groupChatService.latestPlayerTurn(conversationId)
                                                        .onFailure().recoverWithItem(
                                                                (GroupChatService.PlayerTurn) null),
                                                turnInfoFuture)
                                        .asTuple()
                                        .map(tuple -> {
                                            JsonArray history = tuple.getItem1();
                                            JsonObject worldCtx = tuple.getItem2();
                                            java.util.List<com.eonmux.chatllm.models.CharacterInventory> inv =
                                                    tuple.getItem3();

                                            StringBuilder invSummary = new StringBuilder();
                                            if (inv != null && !inv.isEmpty()) {
                                                for (var item : inv) {
                                                    invSummary.append("- ").append(item.getItemName());
                                                    if (item.getQuantity() != null && item.getQuantity() > 1) {
                                                        invSummary.append(" x").append(item.getQuantity());
                                                    }
                                                    if (Boolean.TRUE.equals(item.getIsEquipped())) {
                                                        invSummary.append(" [equipped]");
                                                    }
                                                    invSummary.append("\n");
                                                }
                                            }

                                            GroupChatService.PlayerTurn playerTurn = tuple.getItem8();
                                            JsonObject turnInfo = tuple.getItem9();
                                            boolean finalTurn = turnInfo.getInteger("index", 1)
                                                    >= turnInfo.getInteger("budget", 3);
                                            JsonObject presence = tuple.getItem6();
                                            String castPresence = presence.getString("text", "");
                                            JsonArray newcomers = presence.getJsonArray("newcomers", new JsonArray());
                                            // The opening cast is placed by the narrator. Only a
                                            // character who joins after the player has spoken needs
                                            // an entrance; otherwise the second and third opening
                                            // speakers announce themselves as if they had just arrived.
                                            if (playerTurn != null && newcomers.contains(characterName)) {
                                                castPresence = "You are new to this scene and have not spoken. "
                                                        + "Open your turn with a visible entrance through one of "
                                                        + "the adjacent areas listed in STAGE. Do not speak or "
                                                        + "act as if you were already in the room.\n" + castPresence;
                                            }
                                            String playerName = turnInfo.getString("playerName");
                                            if (playerName != null && !playerName.isBlank()
                                                    && !"User".equals(playerName)) {
                                                castPresence = "The player's name is " + playerName
                                                        + ". Address them by name or as \"you\". "
                                                        + "Never write \"the player\" or \"the user\".\n"
                                                        + castPresence;
                                            }
                                            List<String> staleImages = recentImages(history);
                                            if (!staleImages.isEmpty()) {
                                                castPresence = "The last two turns already used these images: "
                                                        + String.join(", ", staleImages)
                                                        + ". Do not reuse them or a synonym; find a different image "
                                                        + "or speak plainly.\n" + castPresence;
                                            }
                                            JsonObject context = new JsonObject()
                                                    .put("history", history)
                                                    .put("systemPrompt", fullSystemPrompt + tuple.getItem4()
                                                            + tuple.getItem5())
                                                    .put("worldState", worldCtx)
                                                    .put("inventory", invSummary.toString())
                                                    .put("castPresence", castPresence)
                                                    .put("stage", tuple.getItem7())
                                                    .put("playerTurn", playerTurn)
                                                    .put("finalTurn", finalTurn)
                                                    .put("voice", character != null
                                                            ? character.getVoiceAnchor() : "");
                                            String addressee = playerTurn == null ? "none"
                                                    : playerTurn.addresseeName() == null
                                                            ? "group" : playerTurn.addresseeName();
                                            log.info("[turn-direction] {} speaks; addressee={}, alreadyAnsweredBy={}, finalTurn={}",
                                                    characterName, addressee,
                                                    playerTurn == null ? List.of() : playerTurn.repliers(),
                                                    finalTurn);
                                            return context;
                                        });
                            })
                            .chain(context -> {
                                if (config.isLlmExternalServer()) {
                                    JsonArray messagesArray = buildGroupMessagesArray(
                                            context.getJsonArray("history"), context.getString("systemPrompt"),
                                            characterId, characterName, context.getString("voice"),
                                            context.getJsonObject("worldState"), context.getString("inventory"),
                                            context.getString("castPresence"),
                                            (StageContext) context.getValue("stage"),
                                            (GroupChatService.PlayerTurn)
                                                    context.getValue("playerTurn"),
                                            context.getBoolean("finalTurn", false));
                                    return getUserLLMSettings(userId).chain(llmSettings ->
                                            generateExternalStreamingResponse(messagesArray, conversationId,
                                                    userId, assistantMessageId, null,
                                                    characterId, characterName, llmSettings));
                                } else {
                                    String prompt = buildGroupChatPrompt(
                                            context.getJsonArray("history"), context.getString("systemPrompt"),
                                            characterId, characterName, null, context.getString("voice"),
                                            context.getJsonObject("worldState"), context.getString("inventory"),
                                            context.getString("castPresence"),
                                            (StageContext) context.getValue("stage"),
                                            (GroupChatService.PlayerTurn)
                                                    context.getValue("playerTurn"),
                                            context.getBoolean("finalTurn", false));
                                    return generateStreamingResponseWithCharacter(prompt, conversationId,
                                            userId, assistantMessageId, null, characterId, characterName);
                                }
                            })
                            .chain(result -> {
                                String cleanedContent = stripCharacterPrefix(
                                        normalizeRoleplayMarkup(stripReasoning(result.getString("content"))),
                                        characterName);
                                return verifyActiveGroupTurn(conversationId, characterId, assistantMessageId)
                                        .chain(verified -> saveGroupCharacterMessage(conversationId, assistantMessageId,
                                                characterId, characterName, cleanedContent,
                                                result.getJsonObject("generationStats")));
                            })
                            .chain(savedMessage -> updateGroupChatStateAfterResponse(conversationId, characterId, userId));
                })
                .subscribe().with(
                        v -> {
                            if (activeGroupTurns.remove(conversationId, assistantMessageId)) {
                                cancelledRequestIds.remove(conversationId);
                                successfulRequests.incrementAndGet();
                                vertx.eventBus().publish("group.character.response.complete", new JsonObject()
                                        .put("conversationId", conversationId).put("characterId", characterId)
                                        .put("characterName", characterName).put("messageId", assistantMessageId)
                                        .put("completed", true));
                            }
                            llmRequestComplete();
                        },
                        error -> {
                            boolean ownsTurn = activeGroupTurns.remove(conversationId, assistantMessageId);
                            if (!ownsTurn || cancelledRequestIds.remove(conversationId)) {
                                log.info("Group character response cancelled: {}", characterName);
                            } else if (isConnectionError(error)) {
                                log.warn("LLM server unavailable for group character response: {}",
                                        error.getMessage());
                            } else {
                                failedRequests.incrementAndGet();
                                log.error("Failed group character response: {}", characterName, error);
                            }
                            if (ownsTurn) {
                                publishStreamingError(conversationId, assistantMessageId, error.getMessage(), characterName);
                                resetGroupChatState(conversationId, characterId);
                            }
                            llmRequestComplete();
                        }));
    }

    // =======================================================================
    // llm.agent.completion
    // =======================================================================

    private void handleAgentCompletion(Message<JsonObject> msg) {
        JsonObject params = msg.body();

        if (config.isLlmExternalServer()) {
            completeOnExternalServer(msg, params);
            return;
        }

        if (serverPool == null) {
            msg.fail(503, "No local model server pool available");
            return;
        }

        WebClient client = agentCompletionClient();

        serverPool.getDefaultServer("agent-pipeline")
                .chain(server -> {
                    int port = server.getPort();
                    log.debug("[agent-completion] Routing to model server on port {}", port);
                    return client
                            .post(port, "127.0.0.1", "/v1/chat/completions")
                            .timeout(120_000)
                            .sendJson(params)
                            .map(response -> {
                                if (response.statusCode() == 200) {
                                    return response.bodyAsJsonObject();
                                }
                                throw new RuntimeException("LLM agent call failed: status="
                                        + response.statusCode() + " body=" + response.bodyAsString());
                            });
                })
                .subscribe().with(
                        result -> msg.reply(result),
                        err -> {
                            log.error("[agent-completion] Failed: {}", err.getMessage());
                            msg.fail(500, err.getMessage());
                        });
    }

    /**
     * Serves {@code llm.agent.completion} from an external chat-completions API.
     *
     * Journeys, map customisation and the agent pipeline all send this event and
     * expect the completions body back. Until this branch existed they only
     * worked in local mode, so an external deployment answered every one of them
     * with "No local model server pool available".
     */
    private void completeOnExternalServer(Message<JsonObject> msg, JsonObject params) {
        String baseUrl = config.getLlmExternalBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            msg.fail(503, "LLM_EXTERNAL_BASE_URL is not configured");
            return;
        }
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        java.net.URI uri = java.net.URI.create(baseUrl);
        String path = (uri.getPath() == null ? "" : uri.getPath()) + "/v1/chat/completions";

        if (!params.containsKey("model")) {
            String model = config.getLlmExternalModel();
            if (model != null && !model.isEmpty()) {
                params.put("model", model);
            }
        }

        var request = agentCompletionClient()
                .post(path)
                .timeout(config.getLlmRequestTimeout());
        String apiKey = config.getLlmExternalApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            request.putHeader("Authorization", "Bearer " + apiKey);
        }

        request.sendJson(params)
                .map(response -> {
                    if (response.statusCode() == 200) {
                        return response.bodyAsJsonObject();
                    }
                    throw new RuntimeException("LLM agent call failed: status="
                            + response.statusCode() + " body=" + response.bodyAsString());
                })
                .subscribe().with(
                        result -> msg.reply(result),
                        err -> {
                            log.error("[agent-completion] External API failure: {}", err.getMessage());
                            msg.fail(502, err.getMessage());
                        });
    }

    /**
     * Lazily builds the WebClient shared by agent completions. In external mode
     * the client carries the API host, port and TLS settings, so the caller can
     * pass a relative path; the local branch still passes host and port per
     * request because those change with the pooled server.
     */
    private WebClient agentCompletionClient() {
        if (agentWebClient == null) {
            synchronized (this) {
                if (agentWebClient == null) {
                    WebClientOptions options = new WebClientOptions()
                            .setConnectTimeout(30_000)
                            .setIdleTimeout(120)
                            .setMaxPoolSize(4);
                    if (config.isLlmExternalServer()) {
                        String baseUrl = config.getLlmExternalBaseUrl();
                        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                        java.net.URI uri = java.net.URI.create(baseUrl);
                        boolean useSsl = "https".equals(uri.getScheme());
                        int port = uri.getPort() == -1 ? (useSsl ? 443 : 80) : uri.getPort();
                        options.setDefaultHost(uri.getHost())
                                .setDefaultPort(port)
                                .setSsl(useSsl)
                                .setTrustAll(useSsl);
                    }
                    agentWebClient = WebClient.create(vertx, options);
                }
            }
        }
        return agentWebClient;
    }

    // =======================================================================
    // roleplay.session.init
    // =======================================================================

    private void handleRoleplaySessionInit(Message<JsonObject> event) {
        JsonObject body = event.body();
        String conversationId = body.getString("conversationId");
        String userId = body.getString("userId");

        log.info("[session-init] Starting roleplay session initialization for conversation {}", conversationId);

        Uni<io.vertx.mutiny.sqlclient.RowSet<Row>> charFuture = mysqlPool.preparedQuery("""
                SELECT c.characterUUID, c.name, c.description, c.personality, c.systemPrompt
                FROM conversation_character cc
                JOIN `character` c ON cc.characterId = c.characterUUID
                WHERE cc.conversationId = ? AND cc.isActive = TRUE
                  AND (cc.isIntroduced = TRUE OR cc.isIntroduced IS NULL)
                ORDER BY cc.turnOrder
                """).execute(Tuple.of(conversationId));

        Uni<io.vertx.mutiny.sqlclient.RowSet<Row>> userFuture = mysqlPool.preparedQuery(
                "SELECT username, displayName FROM user WHERE userUUID = ?").execute(Tuple.of(userId));

        Uni<io.vertx.mutiny.sqlclient.RowSet<Row>> convFuture = mysqlPool.preparedQuery(
                "SELECT title, description, storyTemplateId FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversationId));

        Uni.combine().all().unis(charFuture, userFuture, convFuture)
                .asTuple()
                .map(tuple -> {
                    var charRows = tuple.getItem1();
                    var userRows = tuple.getItem2();
                    var convRows = tuple.getItem3();

                    String userName = "Player";
                    if (userRows.size() > 0) {
                        Row uRow = userRows.iterator().next();
                        String dn = uRow.getString("displayName");
                        userName = (dn != null && !dn.isBlank()) ? dn : uRow.getString("username");
                    }

                    String roleplayDescription = null;
                    String conversationTitle = null;
                    if (convRows.size() > 0) {
                        Row convRow = convRows.iterator().next();
                        roleplayDescription = convRow.getString("description");
                        conversationTitle = convRow.getString("title");
                    }

                    return new Object[]{charRows, userName, roleplayDescription, conversationTitle};
                })
                .chain(result -> {
                    @SuppressWarnings("unchecked")
                    var charRows = (io.vertx.mutiny.sqlclient.RowSet<Row>) result[0];
                    String userName = (String) result[1];
                    String roleplayDescription = (String) result[2];
                    String conversationTitle = (String) result[3];

                    StringBuilder charDesc = new StringBuilder();
                    JsonArray characterIds = new JsonArray();
                    for (Row row : charRows) {
                        String charUUID = row.getString("characterUUID");
                        String charName = row.getString("name");
                        characterIds.add(charUUID);
                        charDesc.append("- ").append(charName)
                                .append(" (characterId: ").append(charUUID).append(")");
                        String desc = row.getString("description");
                        if (desc != null && !desc.isBlank()) {
                            charDesc.append(": ").append(desc);
                        }
                        String personality = row.getString("personality");
                        if (personality != null && !personality.isBlank()) {
                            charDesc.append(" | Personality: ").append(personality);
                        }
                        charDesc.append("\n");
                    }

                    String scenarioSection = "";
                    if (roleplayDescription != null && !roleplayDescription.isBlank()) {
                        scenarioSection = String.format("""

                        === ROLEPLAY SCENARIO ===
                        %s

                        All narration, world state, and character behavior must align with this scenario description.
                        """, roleplayDescription.trim());
                    }

                    String triggerContent = String.format("""
                            [SESSION_START] This is the beginning of a new roleplay session.
                            %s
                            Player: %s (userId: %s)
                            Characters in this session:
                            %s
                            IMPORTANT: The ONLY characters in this session are those listed above. \
                            Do NOT invent, reference, or introduce any characters not in this list.

                            Instructions for Accountant: You MUST output a JSON object with these changes:
                            1. "inventoryChanges": Create starting inventory for EACH character using their characterId, \
                            AND for the player using userId "%s". Give items appropriate to each character's role/description. \
                            Give the player basic adventuring items. An item is a separate object the character carries, \
                            wears, draws, or uses; a hairstyle, body part, personality trait, or emblem printed on clothing \
                            is not an item. Do not invent a device or a power that the character's description does not name.
                            2. "worldStateChanges": Initialize the world state with appropriate starting values. \
                            Use the exact Place name from the STAGE block for "currentLocation" and the exact \
                            Room name for "currentArea". Do not invent a place or a room. The party starts in that \
                            Room and does not move during the opening. \
                            You MUST set "currentScene" to a brief description of the opening scene. \
                            Also set "weatherChange" to an appropriate weather, and "flagChanges" for any relevant world flags.

                            Instructions for Narrator: Write an engaging opening narration (2 paragraphs max) that sets \
                            the scene, establishes the atmosphere, and introduces the setting. \
                            The scene opens in the exact Place and Room named in the STAGE block. Do not move the \
                            party to another room or place in this opening. \
                            The scene opens with exactly these characters present together. Establish why they are \
                            together at this place and time, using the role and facts listed for each of them. Do not \
                            invent shared history that the facts do not state. \
                            Do not split them into unrelated vignettes. You may mention them by name but do NOT write \
                            dialogue for them. Do NOT invent or introduce any characters not in the list above. \
                            This is the opening of the story and it must be written. Do NOT output [NO_NARRATION].
                            """, scenarioSection, userName, userId, charDesc.toString().trim(), userId);

                    JsonObject pipelineRequest = new JsonObject()
                            .put("conversationId", conversationId)
                            .put("triggerMessageId", (String) null)
                            .put("triggerContent", triggerContent)
                            .put("recentMessages", new JsonArray());

                    log.info("[session-init] Invoking map generation before the agent pipeline");

                    DeliveryOptions pipelineTimeout = new DeliveryOptions().setSendTimeout(300_000);

                    Uni<Void> pipelineUni = vertx.eventBus().<JsonObject>request(
                                    "roleplay.pipeline.process", pipelineRequest, pipelineTimeout)
                            .chain(reply -> {
                                JsonObject pipelineResult = reply.body();
                                boolean failed = pipelineResult.getBoolean("failed", false);
                                long durationMs = pipelineResult.getLong("totalDurationMs", -1L);
                                String narration = pipelineResult.getString("narration");
                                boolean hasNarration = narration != null && !narration.isBlank();
                                log.info("[session-init] Pipeline result: failed={}, durationMs={}, hasNarration={}",
                                        failed, durationMs, hasNarration);
                                if (failed) {
                                    log.warn("[session-init] Pipeline failed: {}",
                                            pipelineResult.getString("errorMessage", "unknown"));
                                    return Uni.createFrom().voidItem();
                                }

                                if (hasNarration) {
                                    log.info("[session-init] Opening narration ({} chars): {}",
                                            narration.length(),
                                            narration.length() > 200 ? narration.substring(0, 200) + "..."
                                                    : narration);
                                    return saveNarrationMessage(conversationId, narration);
                                }
                                return Uni.createFrom().voidItem();
                            })
                            .onFailure().recoverWithUni(err -> {
                                log.warn("[session-init] Pipeline transport failure (non-fatal): {}",
                                        err.getMessage());
                                return Uni.createFrom().voidItem();
                            });

                    String mapName = (conversationTitle != null && !conversationTitle.isBlank())
                            ? conversationTitle : "World Map";
                    String scenario = ((conversationTitle == null ? "" : conversationTitle) + " "
                            + (roleplayDescription == null ? "" : roleplayDescription)).strip();
                    Uni<Void> mapUni = generateSessionMap(conversationId, mapName, userId, scenario);

                    // The map and the local layout must exist before the agents
                    // run. Otherwise the Accountant invents a place name that
                    // the map has never heard of, and the narrator opens in a
                    // scene the party cannot actually occupy.
                    return mapUni
                            .chain(v -> stageService.ensureStage(conversationId))
                            .chain(v -> pipelineUni);
                })
                .subscribe().with(
                        v -> {
                            log.info("[session-init] Initialization complete, triggering first character dialogue");
                            triggerInitialCharacterResponse(conversationId, userId);
                        },
                        err -> {
                            log.error("[session-init] Failed to initialize session for conversation {}: {}",
                                    conversationId, err.getMessage(), err);
                            mysqlPool.preparedQuery(
                                    "UPDATE conversation SET groupState = 'WAITING_FOR_USER' WHERE conversationGUID = ?")
                                    .execute(Tuple.of(conversationId))
                                    .subscribe().with(
                                            r -> {},
                                            e -> log.error("[session-init] Failed to reset state for {}: {}",
                                                    conversationId, e.getMessage()));
                        });
    }

    private Uni<Void> generateSessionMap(String conversationId, String mapName, String userId,
                                         String scenario) {
        if (mapService == null) {
            log.warn("[session-init] MapService not available, skipping map generation");
            return Uni.createFrom().voidItem();
        }

        return mapService.getMaps(conversationId)
                .chain(existingMaps -> {
                    if (!existingMaps.isEmpty()) {
                        log.info("[session-init] Map already exists for conversation {}, skipping generation",
                                conversationId);
                        return Uni.createFrom().<Void>voidItem();
                    }

                    long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                    java.util.List<String> preferredTypes = ScenarioSetting.preferredStartingTypes(scenario);
                    GenerationConfig genConfig = GenerationConfig.builder()
                            .seed(seed)
                            .mapScale(DEFAULT_MAP_SCALE)
                            .gridResolution(DEFAULT_MAP_GRID_RESOLUTION)
                            .waterLevel(DEFAULT_MAP_WATER_LEVEL)
                            .locationCount(DEFAULT_MAP_LOCATION_COUNT)
                            .theme(DEFAULT_MAP_THEME)
                            .preferredStartingTypes(preferredTypes)
                            .build();

                    log.info("[session-init] Generating procedural map '{}' for conversation {} "
                                    + "(seed={}, opening types={})",
                            mapName, conversationId, seed, ScenarioSetting.describe(scenario));

                    return mapService.generateProceduralMap(conversationId, mapName, DEFAULT_MAP_SCALE, genConfig)
                            .chain(worldMap -> {
                                log.info("[session-init] Map generated: id={}, {}x{}, {} locations",
                                        worldMap.getMapId(), worldMap.getGridWidth(), worldMap.getGridHeight(),
                                        worldMap.getLocations() != null ? worldMap.getLocations().size() : 0);

                                String mapId = worldMap.getMapId().toString();
                                String startingLocationId = worldMap.getMetadata() != null
                                        ? worldMap.getMetadata().getString("startingLocationId")
                                        : null;

                                return positionEntitiesOnGeneratedMap(mapId, userId, conversationId,
                                        startingLocationId)
                                        .chain(v -> customizeStartingLocation(mapId, conversationId,
                                                startingLocationId))
                                        .onFailure().recoverWithUni(err -> {
                                            log.warn("[session-init] Entity positioning/customization failed "
                                                    + "(non-fatal): {}", err.getMessage());
                                            return Uni.createFrom().voidItem();
                                        });
                            })
                            .onFailure().invoke(err -> log.error(
                                    "[session-init] Map generation failed (non-fatal): {}", err.getMessage()))
                            .replaceWithVoid();
                })
                .onFailure().recoverWithUni(err -> {
                    log.warn("[session-init] Map generation error (non-fatal): {}", err.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> positionEntitiesOnGeneratedMap(String mapId, String userId, String conversationId,
                                                    String startingLocationId) {
        return mysqlPool.preparedQuery("SELECT * FROM map_location WHERE mapId = ?")
                .execute(Tuple.of(mapId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        log.warn("[session-init] No locations on map {} — cannot position entities", mapId);
                        return Uni.createFrom().<Void>voidItem();
                    }

                    Row targetLoc = null;
                    List<Row> settlements = new ArrayList<>();
                    List<Row> allLocs = new ArrayList<>();
                    for (var row : rows) {
                        allLocs.add(row);
                        if (startingLocationId != null
                                && startingLocationId.equals(row.getString("locationId"))) {
                            targetLoc = row;
                        }
                        String locType = row.getString("locationType");
                        if (locType != null && SETTLEMENT_TYPES.contains(locType)) {
                            settlements.add(row);
                        }
                    }

                    if (targetLoc == null) {
                        log.warn("[session-init] Starting location {} not found on map {}, falling back",
                                startingLocationId, mapId);
                        targetLoc = settlements.isEmpty() ? allLocs.get(0) : settlements.get(0);
                    }

                    String locationId = targetLoc.getString("locationId");
                    float x = targetLoc.getFloat("x");
                    float y = targetLoc.getFloat("y");
                    float z = targetLoc.getFloat("z");
                    String locationName = targetLoc.getString("name");

                    log.info("[session-init] Positioning entities at '{}' ({}) on map {}",
                            locationName, locationId, mapId);

                    Uni<Void> userUni = (userId != null)
                            ? mapService.updateUserPosition(mapId, userId, locationId, x, y, z)
                            : Uni.createFrom().voidItem();

                    Uni<Void> charUni = mysqlPool.preparedQuery(
                            "SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE"
                                    + " AND (isIntroduced = TRUE OR isIntroduced IS NULL)")
                            .execute(Tuple.of(conversationId))
                            .chain(charRows -> {
                                List<Uni<Void>> unis = new ArrayList<>();
                                for (var charRow : charRows) {
                                    String characterId = charRow.getString("characterId");
                                    unis.add(mapService.updateCharacterPosition(mapId, characterId, locationId,
                                            x, y, z));
                                }
                                if (unis.isEmpty()) return Uni.createFrom().<Void>voidItem();
                                return Uni.combine().all().unis(unis).discardItems().replaceWithVoid();
                            });

                    return Uni.combine().all().unis(userUni, charUni).discardItems()
                            .onItem().invoke(() -> log.info(
                                    "[session-init] Positioned user + characters at '{}' on map {}",
                                    locationName, mapId))
                            .replaceWithVoid();
                });
    }

    private Uni<Void> customizeStartingLocation(String mapId, String conversationId, String startingLocationId) {
        if (startingLocationId == null) {
            return Uni.createFrom().voidItem();
        }

        Uni<Row> locFuture = mysqlPool.preparedQuery("SELECT * FROM map_location WHERE locationId = ?")
                .execute(Tuple.of(startingLocationId))
                .map(rows -> rows.size() > 0 ? rows.iterator().next() : null);

        Uni<Row> convFuture = mysqlPool.preparedQuery(
                "SELECT title, description FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .map(rows -> rows.size() > 0 ? rows.iterator().next() : null);

        return Uni.combine().all().unis(locFuture, convFuture).asTuple().chain(tuple -> {
            Row locRow = tuple.getItem1();
            Row convRow = tuple.getItem2();

            if (locRow == null) {
                log.warn("[session-init] Starting location {} not found for LLM customization",
                        startingLocationId);
                return Uni.createFrom().voidItem();
            }

            String locName = locRow.getString("name");
            String locType = locRow.getString("locationType");
            String locDescription = locRow.getString("description");
            String storyTitle = convRow != null ? convRow.getString("title") : "Untitled Story";
            String storyDescription = convRow != null ? convRow.getString("description") : "";

            String systemPrompt = """
                    You are a creative writing assistant helping to name and describe a starting location \
                    for a roleplay adventure. Given the story context and the location's type, provide a \
                    fitting name and atmospheric description.

                    Output ONLY a valid JSON object with two fields:
                    {"name": "...", "description": "..."}

                    The name should be evocative and fit the story's genre/setting (2-4 words).
                    The description should be 1-2 atmospheric sentences that set the scene.
                    Do not include any text outside the JSON object.""";

            String userPrompt = String.format("""
                    Story: "%s"
                    Story description: %s

                    Location type: %s
                    Current template name: %s
                    Current template description: %s

                    Provide a better name and description that fits this story's world.""",
                    storyTitle,
                    storyDescription != null && !storyDescription.isEmpty() ? storyDescription : "(none)",
                    locType != null ? locType : "settlement",
                    locName != null ? locName : "Unknown",
                    locDescription != null ? locDescription : "(none)");

            JsonArray messages = new JsonArray()
                    .add(new JsonObject().put("role", "system").put("content", systemPrompt))
                    .add(new JsonObject().put("role", "user").put("content", userPrompt));

            JsonObject params = new JsonObject()
                    .put("messages", messages)
                    .put("temperature", 0.8)
                    .put("max_tokens", 300)
                    .put("stream", false);

            // The reasoning pass alone can exceed 300 tokens, which leaves the
            // JSON body empty and drops the starting location back to its
            // template. This is a naming task, not a puzzle, so skip it.
            if (config.isLlmExternalServer() && config.isLlmProseDisableThinking()) {
                params.put("chat_template_kwargs", new JsonObject().put("enable_thinking", false));
            }

            DeliveryOptions opts = new DeliveryOptions().setSendTimeout(60_000);

            return vertx.eventBus().<JsonObject>request("llm.agent.completion", params, opts)
                    .chain(reply -> {
                        JsonObject rBody = reply.body();
                        String content = rBody != null ? rBody.getString("content") : null;
                        if (content == null && rBody != null) {
                            JsonArray choices = rBody.getJsonArray("choices");
                            if (choices != null && !choices.isEmpty()) {
                                JsonObject m = choices.getJsonObject(0).getJsonObject("message");
                                if (m != null) content = m.getString("content");
                            }
                        }
                        if (content == null || content.isBlank()) {
                            log.warn("[session-init] LLM returned empty content for starting location customization");
                            return Uni.createFrom().<Void>voidItem();
                        }

                        try {
                            String cleaned = content.strip();
                            if (cleaned.contains("```")) {
                                Matcher m = Pattern
                                        .compile("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*```", Pattern.DOTALL)
                                        .matcher(cleaned);
                                if (m.find()) cleaned = m.group(1).strip();
                            }
                            int first = cleaned.indexOf('{');
                            int last = cleaned.lastIndexOf('}');
                            if (first >= 0 && last > first) {
                                cleaned = cleaned.substring(first, last + 1);
                            }
                            JsonObject result = new JsonObject(cleaned);
                            String newName = result.getString("name");
                            String newDescription = result.getString("description");

                            if (newName == null || newName.isBlank()) {
                                return Uni.createFrom().<Void>voidItem();
                            }

                            log.info("[session-init] LLM customized starting location: '{}' -> '{}'",
                                    locName, newName);

                            Uni<Void> updateLoc = mapService.updateLocationNameAndDescription(
                                    mapId, startingLocationId, newName, newDescription);
                            Uni<Void> updateWs = worldStateService.updateWorldState(conversationId,
                                    new JsonObject()
                                            .put("currentLocation", newName)
                                            .put("currentLocationId", startingLocationId))
                                    .replaceWithVoid();

                            return Uni.combine().all().unis(updateLoc, updateWs).discardItems().replaceWithVoid();
                        } catch (Exception e) {
                            log.warn("[session-init] Failed to parse LLM customization response: {}",
                                    content, e);
                            return Uni.createFrom().<Void>voidItem();
                        }
                    })
                    .onFailure().recoverWithUni(err -> {
                        log.warn("[session-init] LLM customization failed (non-fatal): {}", err.getMessage());
                        return Uni.createFrom().<Void>voidItem();
                    })
                    .replaceWithVoid();
        });
    }

    private void triggerInitialCharacterResponse(String conversationId, String userId) {
        mysqlPool.preparedQuery("""
                UPDATE conversation SET groupState = 'CHARACTER_RESPONDING'
                WHERE conversationGUID = ? AND groupState IN ('INITIALIZING', 'WAITING_FOR_USER')
                """)
                .execute(Tuple.of(conversationId))
                .chain(result -> {
                    if (result.rowCount() == 0) {
                        return Uni.createFrom().<io.vertx.mutiny.sqlclient.RowSet<Row>>failure(
                                new RuntimeException("State already changed"));
                    }
                    return mysqlPool.preparedQuery("""
                            SELECT cc.characterId, c.name, c.systemPrompt
                            FROM conversation_character cc
                            JOIN `character` c ON cc.characterId = c.characterUUID
                            WHERE cc.conversationId = ? AND cc.isActive = TRUE
                              AND (cc.isIntroduced = TRUE OR cc.isIntroduced IS NULL)
                            ORDER BY cc.turnOrder ASC
                            LIMIT 1
                            """).execute(Tuple.of(conversationId));
                })
                .subscribe().with(
                        rows -> {
                            if (rows.size() == 0) {
                                // Nobody can open the scene. Release the claimed
                                // state, or the init screen waits forever.
                                log.warn("[session-init] No active characters found for initial dialogue in {}",
                                        conversationId);
                                releaseGroupState(conversationId);
                                return;
                            }
                            Row row = rows.iterator().next();
                            String characterId = row.getString("characterId");
                            String characterName = row.getString("name");
                            String systemPrompt = row.getString("systemPrompt");

                            mysqlPool.preparedQuery(
                                    "UPDATE conversation SET currentSpeakerId = ? WHERE conversationGUID = ?")
                                    .execute(Tuple.of(characterId, conversationId))
                                    .subscribe().with(
                                            v -> {
                                                JsonObject charEvent = new JsonObject()
                                                        .put("conversationId", conversationId)
                                                        .put("characterId", characterId)
                                                        .put("characterName", characterName)
                                                        .put("systemPrompt", systemPrompt)
                                                        .put("autoTriggered", true)
                                                        .put("userId", userId);
                                                vertx.eventBus().send("group.character.respond", charEvent);
                                                log.info("[session-init] Triggered initial dialogue for character: {}",
                                                        characterName);
                                            },
                                            err -> {
                                                log.error("[session-init] Failed to update currentSpeakerId: {}",
                                                        err.getMessage());
                                                releaseGroupState(conversationId);
                                            });
                        },
                        err -> {
                            if (!"State already changed".equals(err.getMessage())) {
                                log.error("[session-init] Failed to trigger initial character response: {}",
                                        err.getMessage());
                                releaseGroupState(conversationId);
                            }
                        });
    }

    /**
     * Returns a claimed turn to the user when nobody can speak for it, and
     * tells the client so the composer re-enables. Safe to call twice.
     */
    private void releaseGroupState(String conversationId) {
        mysqlPool.preparedQuery("""
                UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL
                WHERE conversationGUID = ? AND groupState IN ('CHARACTER_RESPONDING', 'INITIALIZING')
                """)
                .execute(Tuple.of(conversationId))
                .subscribe().with(
                        result -> {
                            if (result.rowCount() > 0) {
                                vertx.eventBus().publish("group:" + conversationId + ":state-changed", new JsonObject()
                                        .put("conversationId", conversationId)
                                        .put("groupState", "WAITING_FOR_USER"));
                            }
                        },
                        err -> log.error("Failed to release group state for {}", conversationId, err));
    }

    // =======================================================================
    // Cancellation & request helpers
    // =======================================================================

    // Publish a final isComplete=true frame to the message-stream channel so
    // subscribers always receive an end-of-stream signal — even when the
    // generation chain fails before any streaming method is called (history
    // fetch, prompt build, etc.) or after streaming completes but the DB save
    // fails. Without this, the frontend hangs on "Generating response..." and
    // the user must refresh to recover.
    //
    // Idempotent w.r.t. the frontend: useMessageStream's hasCompleted ref
    // ignores stale non-final frames after completion, and the empty-content
    // fallback in the hook preserves any already-streamed accumulator content
    // when this frame arrives after a successful stream completion.
    private void publishStreamingError(String conversationId, String assistantMessageId,
                                       String errorMessage, String displayName) {
        if (conversationId == null || conversationId.isEmpty()) return;
        JsonObject errorChunk = new JsonObject()
                .put("messageId", assistantMessageId != null ? assistantMessageId : UUID.randomUUID().toString())
                .put("content", "")
                .put("streamingContent", "")
                .put("role", "ASSISTANT")
                .put("isComplete", true)
                .put("tokenCount", 0)
                .put("createdAt", Instant.now().toString())
                .put("conversationId", conversationId)
                .put("error", errorMessage != null ? errorMessage : "Generation failed");
        if (displayName != null && !displayName.isEmpty()) {
            errorChunk.put("displayName", displayName);
        }
        try {
            vertx.eventBus().publish("conversation:" + conversationId + ":message-stream", errorChunk);
        } catch (Exception ex) {
            log.warn("Failed to publish streaming error frame for {}: {}", conversationId, ex.getMessage());
        }
    }

    private void handleCancellation(String conversationId, String reason) {
        if (progressService != null) progressService.cancel(conversationId);
        SSEStreamBuffer buffer = activeBuffers.remove(conversationId);
        EventSource existingStream = activeStreams.remove(conversationId);
        if (existingStream != null || buffer != null) {
            log.info("Cancelling generation for conversation {}: {}", conversationId, reason);
            cancelledRequestIds.add(conversationId);
            if (existingStream != null) {
                existingStream.cancel();
            }
            messageBuffer.failStreaming(conversationId, reason);

            JsonObject cancelledChunk = new JsonObject()
                    .put("messageId", "cancelled")
                    .put("content", "")
                    .put("streamingContent", "")
                    .put("role", "ASSISTANT")
                    .put("isComplete", true)
                    .put("error", reason)
                    .put("tokenCount", 0)
                    .put("createdAt", Instant.now().toString())
                    .put("conversationId", conversationId);
            vertx.eventBus().publish("conversation:" + conversationId + ":message-stream", cancelledChunk);
            // Cancelling only the transport leaves its Uni unresolved, holding
            // the global request queue until the (potentially 30 minute) timeout.
            if (buffer != null) buffer.abort(new java.util.concurrent.CancellationException(reason));
        }
    }

    private JsonObject extractUserSettings(JsonObject request) {
        JsonObject userSettings = new JsonObject();
        if (request.containsKey("temperature")) userSettings.put("temperature", request.getDouble("temperature"));
        if (request.containsKey("maxTokens")) userSettings.put("maxTokens", request.getInteger("maxTokens"));
        if (request.containsKey("topP")) userSettings.put("topP", request.getDouble("topP"));
        if (request.containsKey("topK")) userSettings.put("topK", request.getInteger("topK"));
        if (request.containsKey("contextSize")) userSettings.put("contextSize", request.getInteger("contextSize"));
        return userSettings.isEmpty() ? null : userSettings;
    }

    // =======================================================================
    // Character lookup / prompt building
    // =======================================================================

    private Uni<com.eonmux.chatllm.models.Character> fetchCharacterById(String characterId) {
        return mysqlPool.preparedQuery("SELECT * FROM `character` WHERE characterUUID = ?")
                .execute(Tuple.of(characterId))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    com.eonmux.chatllm.models.Character character = new com.eonmux.chatllm.models.Character();
                    character.setId(UUID.fromString(row.getString("characterUUID")));
                    character.setName(row.getString("name"));
                    character.setSystemPrompt(row.getString("systemPrompt"));
                    character.setDescription(row.getString("description"));
                    character.setPersonality(row.getString("personality"));
                    character.setAppearance(row.getString("appearance"));
                    character.setGender(row.getString("gender"));
                    character.setPronouns(row.getString("pronouns"));
                    character.setAgeYears(row.getInteger("ageYears"));
                    character.setHeight(row.getString("height"));
                    character.setBuild(row.getString("build"));
                    character.setHair(row.getString("hair"));
                    character.setEyes(row.getString("eyes"));
                    character.setStrength(row.getInteger("strength"));
                    character.setDexterity(row.getInteger("dexterity"));
                    character.setConstitution(row.getInteger("constitution"));
                    character.setIntelligence(row.getInteger("intelligence"));
                    character.setWisdom(row.getInteger("wisdom"));
                    character.setCharisma(row.getInteger("charisma"));
                    character.setBackstory(row.getString("backstory"));
                    character.setGoals(row.getString("goals"));
                    character.setLikes(row.getString("likes"));
                    character.setDislikes(row.getString("dislikes"));
                    character.setExamples(row.getString("examples"));
                    character.setAvatar(row.getString("avatar"));
                    character.setGreeting(row.getString("greeting"));
                    return character;
                });
    }

    private String buildGroupChatPrompt(JsonArray history, String systemPrompt, String currentCharacterId,
                                        String characterName, Settings.LLMSettings llmSettings, String voice,
                                        JsonObject worldState, String inventory, String castPresence,
                                        StageContext stage,
                                        GroupChatService.PlayerTurn playerTurn, boolean finalTurn) {
        String template = currentTemplate;
        if (llmSettings != null && llmSettings.getPromptTemplate() != null
                && !llmSettings.getPromptTemplate().isEmpty()) {
            template = llmSettings.getPromptTemplate().toUpperCase();
        }

        PromptBuilder builder = new PromptBuilder(template);
        builder.setReasoningEffort(config.getLlmGptOssReasoningEffort());

        StringBuilder enhancedPrompt = new StringBuilder(systemPrompt);
        appendGroupContext(enhancedPrompt, characterName, voice, worldState, inventory, castPresence,
                stage, playerTurn, finalTurn);
        builder.setSystemPrompt(enhancedPrompt.toString());

        if (!historyHasPlayerTurn(history)) {
            // The cue is the last user message so the model answers it rather
            // than the narrator's description above it.
            builder.addUserMessage(openingTurnCue(characterName,
                    speakerHasSpoken(history, currentCharacterId)));
        }

        StringBuilder msgBuilder = new StringBuilder(256);

        for (int i = 0; i < history.size(); i++) {
            JsonObject msg = history.getJsonObject(i);
            String role = msg.getString("role");
            String content = msg.getString("content");
            String msgCharacterId = msg.getString("characterId");

            if ("user".equals(role)) {
                String userDisplayName = msg.getString("displayName", "User");
                msgBuilder.setLength(0);
                msgBuilder.append('[').append(userDisplayName).append("]: ").append(content);
                builder.addUserMessage(msgBuilder.toString());
            } else if ("assistant".equals(role)) {
                if (currentCharacterId != null && currentCharacterId.equals(msgCharacterId)) {
                    builder.addAssistantMessage(content);
                } else {
                    String speakerName = msg.getString("displayName", "Another Character");
                    msgBuilder.setLength(0);
                    msgBuilder.append('[').append(speakerName).append("]: ").append(content);
                    builder.addUserMessage(msgBuilder.toString());
                }
            } else if ("system".equals(role)) {
                String speakerName = msg.getString("displayName", "Narrator");
                msgBuilder.setLength(0);
                msgBuilder.append('[').append(speakerName).append("]: ").append(content);
                builder.addUserMessage(msgBuilder.toString());
            }
        }
        return builder.build();
    }

    private Uni<JsonObject> saveGroupCharacterMessage(String conversationId, String messageId, String characterId,
                                                     String characterName, String content,
                                                     JsonObject generationStats) {
        String cleanedContent = stripReasoning(content);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        int promptTokens = generationStats != null ? generationStats.getInteger("promptTokens", 0) : 0;
        int completionTokens = generationStats != null ? generationStats.getInteger("completionTokens", 0) : 0;
        int totalTimeMs = generationStats != null ? generationStats.getInteger("totalTimeMs", 0) : 0;
        double tps = generationStats != null ? generationStats.getDouble("tokensPerSecond", 0.0) : 0.0;
        int promptEvalMs = generationStats != null ? generationStats.getInteger("promptEvalMs", 0) : 0;
        int generationMs = generationStats != null ? generationStats.getInteger("generationMs", 0) : 0;

        return mysqlPool.preparedQuery("""
                INSERT INTO message (messageUUID, conversationId, characterId, content, role, displayName,
                                     promptTokens, completionTokens, promptEvalMs, generationMs,
                                     totalTimeMs, tokensPerSecond, createdAt, updatedAt)
                VALUES (?, ?, ?, ?, 'assistant', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .execute(Tuple.tuple()
                        .addValue(messageId).addValue(conversationId).addValue(characterId)
                        .addValue(cleanedContent)
                        .addValue(characterName != null ? characterName : "Assistant")
                        .addValue(promptTokens).addValue(completionTokens)
                        .addValue(promptEvalMs).addValue(generationMs)
                        .addValue(totalTimeMs).addValue(tps)
                        .addValue(now).addValue(now))
                .map(v -> new JsonObject()
                        .put("id", messageId).put("conversationId", conversationId)
                        .put("characterId", characterId).put("content", cleanedContent)
                        .put("role", "assistant").put("displayName", characterName)
                        .put("createdAt", now.toString()));
    }

    private Uni<Void> maybeRunAgentPipeline(String conversationId) {
        return mysqlPool.preparedQuery(
                "SELECT isRoleplay, agentPipelineEnabled FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() == 0) return Uni.createFrom().<Void>voidItem();
                    Row row = rows.iterator().next();
                    Boolean isRoleplay = row.getBoolean("isRoleplay");
                    Boolean enabled = row.getBoolean("agentPipelineEnabled");
                    if (!Boolean.TRUE.equals(isRoleplay) || !Boolean.TRUE.equals(enabled)) {
                        return Uni.createFrom().<Void>voidItem();
                    }
                    return runAgentPipeline(conversationId);
                });
    }

    private Uni<Void> runAgentPipeline(String conversationId) {
        return mysqlPool.preparedQuery(
                "SELECT messageUUID, content, (SELECT m.messageUUID FROM message m "
                        + "WHERE m.conversationId = message.conversationId AND m.role = 'assistant' AND m.isDeleted = FALSE ORDER BY m.seq DESC LIMIT 1) AS roundLastMessageId FROM message "
                        + "WHERE conversationId = ? AND role = 'user' AND isDeleted = FALSE "
                        + "ORDER BY seq DESC LIMIT 1")
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        log.debug("[pipeline] No user message found for conversation {}, skipping pipeline",
                                conversationId);
                        return Uni.createFrom().<Void>voidItem();
                    }
                    Row triggerRow = rows.iterator().next();
                    String triggerMessageId = triggerRow.getString("messageUUID");
                    String triggerContent = triggerRow.getString("content");

                    return getConversationHistory(conversationId).chain(recentMessages -> isNarrativeRoundCurrent(conversationId,triggerMessageId,triggerRow.getString("roundLastMessageId"))
                        .chain(currentRound -> {
                        if (!currentRound) return Uni.createFrom().voidItem();
                        JsonObject pipelineRequest = new JsonObject()
                                .put("conversationId", conversationId)
                                .put("triggerMessageId", triggerMessageId)
                                .put("triggerContent", triggerContent)
                                .put("postDialogue", true)
                                .put("roundLastMessageId", triggerRow.getString("roundLastMessageId"))
                                .put("recentMessages", recentMessages);

                        log.info("[pipeline] Invoking agent pipeline for conversation {}", conversationId);

                        if (progressService != null) progressService.begin(conversationId,triggerRow.getString("roundLastMessageId"));
                        DeliveryOptions pipelineTimeout = new DeliveryOptions().setSendTimeout(300_000);
                        return vertx.eventBus().<JsonObject>request(
                                        "roleplay.pipeline.process", pipelineRequest, pipelineTimeout)
                                .chain(reply -> {
                                    JsonObject result = reply.body();
                                    boolean failed = result.getBoolean("failed", false);
                                    long durationMs = result.getLong("totalDurationMs", -1L);
                                    String narration = result.getString("narration");
                                    boolean hasNarration = narration != null && !narration.isBlank();
                                    log.info("[pipeline] Pipeline result for conversation {}: "
                                                    + "failed={}, durationMs={}, hasNarration={}",
                                            conversationId, failed, durationMs, hasNarration);
                                    if (failed) {
                                        log.warn("[pipeline] Pipeline failed for conversation {}: {}",
                                                conversationId, result.getString("errorMessage", "unknown"));
                                        return Uni.createFrom().<Void>voidItem();
                                    }

                                    if (hasNarration) {
                                        log.info("[pipeline] Narration generated ({} chars): {}",
                                                narration.length(),
                                                narration.length() > 200
                                                        ? narration.substring(0, 200) + "..." : narration);
                                        if (progressService != null) progressService.update(conversationId,triggerRow.getString("roundLastMessageId"),"saving","Saving the scene…");
                                        return isNarrativeRoundCurrent(conversationId, triggerMessageId,
                                                triggerRow.getString("roundLastMessageId"))
                                            .chain(current -> current ? storyMessageService.narrate(conversationId,
                                                "round:" + triggerRow.getString("roundLastMessageId"),
                                                normalizeRoleplayMarkup(narration), null)
                                                .chain(() -> {
                                                    JsonObject resident=result.getJsonObject("arrivalResident");
                                                    if (residents == null || resident == null) return Uni.createFrom().voidItem();
                                                    if (progressService != null) progressService.update(conversationId,triggerRow.getString("roundLastMessageId"),"greeting",resident.getString("name")+" is greeting the party…");
                                                    return residents.greet(conversationId,triggerMessageId,triggerRow.getString("roundLastMessageId"),resident,narration,
                                                        () -> isNarrativeRoundCurrent(conversationId,triggerMessageId,triggerRow.getString("roundLastMessageId")));
                                                }) : Uni.createFrom().voidItem());
                                    }
                                    return Uni.createFrom().<Void>voidItem();
                                })
                                .onFailure().recoverWithUni(err -> {
                                    log.warn("[pipeline] Pipeline invocation failed for conversation {}: {}",
                                            conversationId, err.getMessage());
                                    return Uni.createFrom().<Void>voidItem();
                                }).eventually(() -> {
                                    if (progressService != null) progressService.finish(conversationId,triggerRow.getString("roundLastMessageId"));
                                });
                    }));
                });
    }

    private Uni<Void> saveNarrationMessage(String conversationId, String narration) {
        return storyMessageService.narrate(conversationId, "opening:" + UUID.randomUUID(),
                normalizeRoleplayMarkup(narration), null);
    }

    private Uni<Void> updateGroupChatStateAfterResponse(String conversationId, String characterId, String userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mysqlPool.preparedQuery("""
                UPDATE conversation
                SET currentSpeakerId = NULL,
                    consecutiveResponseCount = COALESCE(consecutiveResponseCount, 0) + 1,
                    lastSpeakerId = ?, lastModified = ?, updatedAt = ?, messageCount = IFNULL(messageCount, 0) + 1
                WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING' AND currentSpeakerId = ?
                """).execute(Tuple.of(characterId, now, now, conversationId, characterId))
                .chain(result -> {
                    // Pause, stop, or a user interjection may have revoked this turn.
                    if (result.rowCount() == 0) return Uni.createFrom().voidItem();
                    return mysqlPool.preparedQuery("""
                            UPDATE conversation_character SET responseCount = responseCount + 1, lastResponseAt = ?
                            WHERE conversationId = ? AND characterId = ?
                            """).execute(Tuple.of(now, conversationId, characterId))
                            .chain(v -> mysqlPool.preparedQuery(
                                    "SELECT consecutiveResponseCount, maxConsecutiveResponses FROM conversation WHERE conversationGUID = ?")
                                    .execute(Tuple.of(conversationId)))
                            .chain(rows -> {
                                if (rows.size() == 0) return settleGroupRound(conversationId, characterId);
                                Row budget = rows.iterator().next();
                                Integer responsesValue = budget.getInteger("consecutiveResponseCount");
                                Integer maxValue = budget.getInteger("maxConsecutiveResponses");
                                int responses = responsesValue == null ? 0 : responsesValue;
                                int max = maxValue == null || maxValue <= 0 ? 3 : maxValue;
                                if (responses >= max) return settleGroupRound(conversationId, characterId);
                                return groupChatService.selectNextCharacter(conversationId)
                                        .chain(next -> next != null
                                                ? triggerNextCharacter(conversationId, characterToJson(next), userId)
                                                : settleGroupRound(conversationId, characterId))
                                        // The response is already saved and streamed.
                                        // A failure to line up the next speaker ends
                                        // the chain; it must not fail this turn or the
                                        // client marks a delivered message as failed.
                                        .onFailure().recoverWithUni(error -> {
                                            log.warn("[group-respond] Could not line up the next speaker in {}: {}",
                                                    conversationId, error.getMessage());
                                            return settleGroupRound(conversationId, characterId);
                                        });
                            });
                });
    }

    private static JsonObject characterToJson(com.eonmux.chatllm.models.Character ch) {
        return new JsonObject()
                .put("id", ch.getId().toString())
                .put("name", ch.getName())
                .put("systemPrompt", ch.getSystemPrompt())
                .put("avatar", ch.getAvatar());
    }

    private Uni<Void> triggerNextCharacter(String conversationId, JsonObject nextCharacter, String userId) {
        String characterId = nextCharacter.getString("id");
        return mysqlPool.preparedQuery("""
                UPDATE conversation SET currentSpeakerId = ?
                WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING' AND currentSpeakerId IS NULL
                """).execute(Tuple.of(characterId, conversationId))
                .invoke(result -> {
                    if (result.rowCount() == 0) return;
                    vertx.eventBus().publish("group:" + conversationId + ":turn-changed", new JsonObject()
                            .put("conversationId", conversationId).put("characterId", characterId)
                            .put("characterName", nextCharacter.getString("name"))
                            .put("characterAvatar", nextCharacter.getString("avatar")));
                    // Generation has its own idle/absolute timeouts. A separate
                    // request/reply or speaker-only timer can expire during a later turn.
                    String scheduledTurn = UUID.randomUUID().toString();
                    scheduledGroupTurns.put(conversationId, scheduledTurn);
                    vertx.setTimer(500L, timerId -> {
                        if (!scheduledGroupTurns.remove(conversationId, scheduledTurn)) return;
                        vertx.eventBus().send("group.character.respond", new JsonObject()
                            .put("conversationId", conversationId).put("characterId", characterId)
                            .put("characterName", nextCharacter.getString("name"))
                            .put("systemPrompt", nextCharacter.getString("systemPrompt"))
                            .put("userId", userId).put("autoTriggered", true));
                    });
                }).replaceWithVoid();
    }

    private Uni<Void> verifyActiveGroupTurn(String conversationId, String characterId, String turnId) {
        if (!turnId.equals(activeGroupTurns.get(conversationId))) {
            return Uni.createFrom().failure(new IllegalStateException("Turn was cancelled"));
        }
        return verifyCharacterTurn(conversationId, characterId).invoke(() -> {
            if (!turnId.equals(activeGroupTurns.get(conversationId))) {
                throw new IllegalStateException("Turn was cancelled");
            }
        });
    }

    /** Keep the turn owned while narration/state settle; never restart the character chain. */
    private Uni<Void> settleGroupRound(String conversationId, String lastSpeakerId) {
        String ownedTurn = activeGroupTurns.get(conversationId);
        return mysqlPool.preparedQuery("""
                SELECT conversationGUID FROM conversation
                WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING'
                  AND currentSpeakerId IS NULL AND lastSpeakerId = ?
                """).execute(Tuple.of(conversationId, lastSpeakerId))
                .chain(rows -> rows.size() == 0 ? Uni.createFrom().voidItem()
                    : maybeRunAgentPipeline(conversationId)
                        .eventually(() -> java.util.Objects.equals(ownedTurn, activeGroupTurns.get(conversationId))
                            ? setGroupChatWaiting(conversationId, lastSpeakerId) : Uni.createFrom().voidItem()));
    }

    private Uni<Boolean> isNarrativeRoundCurrent(String conversationId, String triggerMessageId, String boundaryId) {
        return mysqlPool.preparedQuery("""
                SELECT c.conversationGUID FROM conversation c
                WHERE c.conversationGUID = ? AND c.groupState = 'CHARACTER_RESPONDING'
                  AND c.currentSpeakerId IS NULL
                  AND (SELECT m.messageUUID FROM message m WHERE m.conversationId = c.conversationGUID
                       AND m.role = 'user' AND m.isDeleted = FALSE ORDER BY m.seq DESC LIMIT 1) = ?
                  AND (SELECT m.messageUUID FROM message m WHERE m.conversationId = c.conversationGUID
                       AND m.role = 'assistant' AND m.isDeleted = FALSE ORDER BY m.seq DESC LIMIT 1) = ?
                """).execute(Tuple.of(conversationId, triggerMessageId, boundaryId)).map(rows -> rows.size() > 0);
    }

    private Uni<Void> setGroupChatWaiting(String conversationId, String lastSpeakerId) {
        return mysqlPool.preparedQuery(
                "UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL "
                        + "WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING' "
                        + "AND currentSpeakerId IS NULL AND lastSpeakerId = ?")
                .execute(Tuple.of(conversationId, lastSpeakerId))
                .invoke(rows -> {
                    if (rows.rowCount() > 0) vertx.eventBus().publish("group:" + conversationId + ":state-changed",
                        new JsonObject().put("conversationId", conversationId)
                            .put("groupState", "WAITING_FOR_USER").put("lastSpeakerId", lastSpeakerId));
                }).replaceWithVoid();
    }

    /**
     * Verify that the given character is the expected current speaker and that
     * the conversation is in CHARACTER_RESPONDING state. Prevents two characters
     * from generating simultaneously for the same conversation.
     */
    private Uni<Void> verifyCharacterTurn(String conversationId, String characterId) {
        return mysqlPool.preparedQuery(
                "SELECT groupState, currentSpeakerId FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation not found"));
                    }
                    Row row = rows.iterator().next();
                    String state = row.getString("groupState");
                    String speaker = row.getString("currentSpeakerId");
                    if ("PAUSED".equals(state)) {
                        return Uni.createFrom().failure(new RuntimeException("Conversation is paused"));
                    }
                    if (!"CHARACTER_RESPONDING".equals(state)) {
                        log.warn("[group-respond] Conversation {} state is {} (expected CHARACTER_RESPONDING) — rejecting turn for character {}",
                                conversationId, state, characterId);
                        return Uni.createFrom().failure(new RuntimeException("Not expecting character response"));
                    }
                    if (!characterId.equals(speaker)) {
                        log.warn("[group-respond] Conversation {} currentSpeakerId is {} but request is for character {} — rejecting",
                                conversationId, speaker, characterId);
                        return Uni.createFrom().failure(new RuntimeException("Not your turn"));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    private void resetGroupChatState(String conversationId, String characterId) {
        mysqlPool.preparedQuery(
                "UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL "
                        + "WHERE conversationGUID = ? AND groupState = 'CHARACTER_RESPONDING' AND (currentSpeakerId = ? OR currentSpeakerId IS NULL)")
                .execute(Tuple.of(conversationId, characterId))
                .subscribe().with(
                        result -> {
                            if (result.rowCount() > 0) {
                                vertx.eventBus().publish("group:" + conversationId + ":state-changed", new JsonObject()
                                        .put("conversationId", conversationId)
                                        .put("groupState", "WAITING_FOR_USER")
                                        .put("error", true));
                            }
                        },
                        err -> log.error("Failed to reset group chat state", err));
    }

    // =======================================================================
    // Character generation (non-streaming)
    // =======================================================================

    void handleGenerateCharacter(Message<JsonObject> message) {
        JsonObject request = message.body();
        String userPrompt = request.getString("prompt");

        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            message.fail(400, "Character generation prompt is required");
            return;
        }

        Integer rosterCount = request.getInteger("rosterCount");
        String prompt;
        try {
            prompt = rosterCount == null ? buildCharacterGenerationPrompt(userPrompt)
                    : CharacterRosterGenerator.prompt(userPrompt, rosterCount);
        } catch (IllegalArgumentException error) {
            message.fail(400, error.getMessage());
            return;
        }
        int outputTokens = rosterCount == null ? 8192 : 4096 + rosterCount * 1800;
        float temperature = request.getFloat("temperature", 0.7f);

        if (config.isLlmExternalServer()) {
            JsonArray messagesArray = new JsonArray()
                    .add(new JsonObject().put("role", "user").put("content", prompt));
            JsonObject params = new JsonObject()
                    .put("model", config.getLlmExternalModel())
                    .put("messages", messagesArray)
                    .put("temperature", temperature)
                    .put("max_tokens", outputTokens)
                    .put("stream", false);

            if (rosterCount != null) {
                params.put("response_format", CharacterRosterGenerator.responseFormat(rosterCount));
                if (config.isLlmRosterDisableThinking()) {
                    params.put("chat_template_kwargs", new JsonObject().put("enable_thinking", false));
                }
            }

            String baseUrl = config.getLlmExternalBaseUrl();
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            String apiUrl = baseUrl + "/v1/chat/completions";

            Request.Builder reqBuilder = new Request.Builder()
                    .url(apiUrl)
                    .post(RequestBody.create(params.toString(), MediaType.parse("application/json")));

            String apiKey = config.getLlmExternalApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            queueLlmRequest(() -> {
                try {
                    // Queue dispatch runs on a Vert.x event loop. Read the HTTP
                    // response on OkHttp's dispatcher so other requests stay responsive.
                    Call call = sharedHttpClient().newCall(reqBuilder.build());
                    call.timeout().timeout(rosterCount == null ? 300 : 600, TimeUnit.SECONDS);
                    call.enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException error) {
                            log.error("Character generation request failed (external)", error);
                            message.fail(500, error.getMessage());
                            llmRequestComplete();
                        }

                        @Override
                        public void onResponse(Call call, Response response) {
                            try (response) {
                                String body = response.body() != null ? response.body().string() : "";
                                if (response.isSuccessful()) {
                                    JsonObject json = new JsonObject(body);
                                    JsonArray choices = json.getJsonArray("choices");
                                    String content = (choices != null && !choices.isEmpty())
                                            ? choices.getJsonObject(0).getJsonObject("message").getString("content", "")
                                            : "";
                                    handleCharacterGenResult(message, content, userPrompt, rosterCount);
                                } else {
                                    log.error("External API returned error {} for character generation: {}",
                                            response.code(), body);
                                    message.fail(response.code(), "External API error: " + response.code());
                                }
                            } catch (Exception error) {
                                log.error("Character generation request failed (external)", error);
                                message.fail(500, error.getMessage());
                            } finally {
                                llmRequestComplete();
                            }
                        }
                    });
                } catch (Exception error) {
                    log.error("Could not dispatch character generation request", error);
                    message.fail(500, error.getMessage());
                    llmRequestComplete();
                }
            });
        } else {
            if (serverPool == null) {
                message.fail(503, "Local model server pool not initialized");
                return;
            }

            JsonObject params = new JsonObject()
                    .put("prompt", prompt)
                    .put("temperature", temperature)
                    .put("max_tokens", outputTokens)
                    .put("stream", false);

            if (rosterCount != null) {
                params.put("n_predict", outputTokens).put("json_schema", CharacterRosterGenerator.responseFormat(rosterCount)
                        .getJsonObject("json_schema").getJsonObject("schema"));
            }

            queueLlmRequest(() -> serverPool.getDefaultServer("character-gen")
                    .chain(server -> {
                        WebClient client = createWebClientForServer(server);
                        return client.post("/completion")
                                .timeout(rosterCount == null ? 300_000 : 600_000)
                                .sendJson(params)
                                .eventually(() -> {
                                    client.close();
                                    return Uni.createFrom().voidItem();
                                });
                    })
                    .subscribe().with(
                            response -> {
                                if (response.statusCode() == 200) {
                                    String content = response.bodyAsJsonObject().getString("content", "");
                                    handleCharacterGenResult(message, content, userPrompt, rosterCount);
                                } else {
                                    log.error("LLM server returned error status {} for character generation",
                                            response.statusCode());
                                    message.fail(response.statusCode(),
                                            "LLM server error: " + response.statusMessage());
                                }
                                llmRequestComplete();
                            },
                            error -> {
                                log.error("Character generation request failed", error);
                                message.fail(500, error.getMessage());
                                llmRequestComplete();
                            }));
        }
    }

    private void handleCharacterGenResult(Message<JsonObject> message, String content, String userPrompt, Integer rosterCount) {
        if (content == null || content.trim().isEmpty()) {
            log.error("Character generation returned empty content for prompt: {}", userPrompt);
            message.fail(500, "LLM returned empty response");
        } else {
            try {
                if (rosterCount != null) {
                    JsonArray roster = CharacterRosterGenerator.parse(content, userPrompt, rosterCount);
                    message.reply(new JsonObject().put("characters", roster));
                    log.info("Generated an interconnected character roster ({} members)", rosterCount);
                    return;
                }
                JsonObject parsedCharacter = parseGeneratedCharacter(content);
                log.info("Successfully generated character: {}",
                        parsedCharacter.getString("name", "unknown"));
                message.reply(parsedCharacter);
            } catch (Exception e) {
                log.error("Failed to parse generated character content: {}",
                        content.substring(0, Math.min(200, content.length())), e);
                message.fail(500, (rosterCount == null ? "Failed to parse character: " : "Failed to generate roster: ") + e.getMessage());
            }
        }
    }

    // =======================================================================
    // Streaming generation (local) — uses OkHttp SSE + SSEStreamBuffer
    // =======================================================================

    private Uni<JsonObject> generateStreamingResponseWithCharacter(String prompt, String conversationId,
                                                                   String userId, String assistantMessageId,
                                                                   JsonObject userSettings,
                                                                   String characterId, String characterName) {
        return getUserLLMSettings(userId).chain(llmSettings ->
                generateStreamingResponseWithSettings(prompt, conversationId, userId, assistantMessageId,
                        userSettings, characterId, characterName, llmSettings));
    }

    private Uni<JsonObject> generateStreamingResponseWithSettings(String prompt, String conversationId,
                                                                  String userId, String assistantMessageId,
                                                                  JsonObject userSettings,
                                                                  String characterId, String characterName,
                                                                  Settings.LLMSettings llmSettings) {
        if (serverPool == null) {
            return Uni.createFrom().failure(new RuntimeException(
                    "Local model server pool not initialized"));
        }

        String modelPath = (llmSettings != null && llmSettings.getModel() != null
                && !llmSettings.getModel().isEmpty())
                ? llmSettings.getModel()
                : null;

        // getOrCreateServer resolves null to the configured default model path,
        // so unregister under the same resolved path to avoid leaking the user
        // registration when no custom model is set.
        final String finalModelPath = (modelPath != null && !modelPath.isEmpty())
                ? modelPath
                : config.getLlmModelPath();

        return serverPool.getOrCreateServer(modelPath, userId)
                .chain(modelServer -> {
                    log.debug("Routing request to model server: {} for user: {}",
                            modelServer.getModelName(), userId);
                    return performStreamingGeneration(prompt, conversationId, userId, assistantMessageId,
                            userSettings, characterId, characterName, llmSettings, modelServer);
                })
                .eventually(() -> {
                    serverPool.unregisterUser(finalModelPath, userId);
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<JsonObject> performStreamingGeneration(String prompt, String conversationId, String userId,
                                                       String assistantMessageId, JsonObject userSettings,
                                                       String characterId, String characterName,
                                                       Settings.LLMSettings llmSettings,
                                                       ModelServerInstance modelServer) {
        if (characterId != null && !assistantMessageId.equals(activeGroupTurns.get(conversationId))) {
            return Uni.createFrom().failure(new IllegalStateException("Turn was cancelled before streaming started"));
        }
        return Uni.createFrom().<JsonObject>emitter(emitter -> {
            final StringBuilder fullResponse = new StringBuilder();
            final AtomicLong tokenCount = new AtomicLong(0);
            final long startTime = System.currentTimeMillis();
            final long[] firstTokenTime = {0};
            final AtomicBoolean stopReceived = new AtomicBoolean(false);
            final AtomicBoolean completed = new AtomicBoolean(false);

            String streamChannel = "conversation:" + conversationId + ":message-stream";
            String typingChannel = "typing.indicator." + conversationId;
            String createdAt = Instant.now().toString();
            String displayName = characterName != null ? characterName : "Assistant";

            boolean useGptOssProcessor = isGptOssTemplate();
            GptOssContentProcessor gptOssProcessor = useGptOssProcessor ? new GptOssContentProcessor() : null;

            JsonObject[] finalChunk = {null};

            // Reasoning (<think>) tag bookkeeping for the non-GPT-OSS fallback
            // path so multi-chunk reasoning streams as one span.
            final AtomicBoolean thinkOpen = new AtomicBoolean(false);
            final AtomicBoolean thinkClosed = new AtomicBoolean(false);

            long[] lastPublishTime = {System.currentTimeMillis()};
            long[] lastSyncTime = {System.currentTimeMillis()};
            int[] lastPublishedLength = {0};
            final long PUBLISH_INTERVAL_MS = 50;
            final long SYNC_INTERVAL_MS = 500;

            publishTyping(typingChannel, conversationId, displayName, true);
            messageBuffer.startStreaming(conversationId, assistantMessageId, createdAt);

            JsonObject startChunk = new JsonObject()
                    .put("messageId", assistantMessageId).put("content", "").put("role", "ASSISTANT")
                    .put("isComplete", false).put("tokenCount", 0).put("createdAt", createdAt)
                    .put("displayName", displayName).put("conversationId", conversationId);
            if (characterId != null) startChunk.put("characterId", characterId);
            vertx.eventBus().publish(streamChannel, startChunk);

            JsonObject defaultConfig = buildDefaultConfig();
            JsonObject params = LLMParameterBuilder.buildParameters(llmSettings, prompt, defaultConfig);

            if (userSettings != null && !userSettings.isEmpty()) {
                params = LLMParameterBuilder.mergeUserOverrides(params, userSettings);
            }

            params.put("stream", true);
            params.put("stop", getStopSequences());

            // Reuse the singleton OkHttpClient (shared dispatcher + connection pool).
            OkHttpClient httpClient = sharedHttpClient();

            Request request = new Request.Builder()
                    .url(modelServer.getServerUrl() + "/completion")
                    .post(RequestBody.create(params.toString(), MediaType.parse("application/json")))
                    .build();

            SSEStreamBuffer sseBuffer = new SSEStreamBuffer(
                    vertx,
                    conversationId,
                    (event) -> processBufferedSSEEvent(
                            event.data, emitter, fullResponse, tokenCount, startTime, firstTokenTime,
                            streamChannel, typingChannel, createdAt, displayName, characterId,
                            assistantMessageId, conversationId, useGptOssProcessor, gptOssProcessor,
                            stopReceived, finalChunk, completed,
                            lastPublishTime, PUBLISH_INTERVAL_MS,
                            lastSyncTime, lastPublishedLength, SYNC_INTERVAL_MS,
                            thinkOpen, thinkClosed),
                    () -> {
                        if (completed.get()) return;
                        if (thinkOpen.get() && thinkClosed.compareAndSet(false, true)) fullResponse.append("</think>");
                        activeStreams.remove(conversationId);
                        activeBuffers.remove(conversationId);

                        if (finalChunk[0] != null && stopReceived.get()) {
                            completeBufferedGeneration(
                                    emitter, finalChunk[0], fullResponse, tokenCount, startTime,
                                    typingChannel, streamChannel, assistantMessageId, conversationId,
                                    displayName, characterId, createdAt, useGptOssProcessor, gptOssProcessor,
                                    completed);
                        } else {
                            publishTyping(typingChannel, conversationId, displayName, false);

                            long totalTimeMs = System.currentTimeMillis() - startTime;
                            JsonObject estimatedStats = new JsonObject()
                                    .put("completionTokens", (int) tokenCount.get())
                                    .put("totalTimeMs", (int) totalTimeMs)
                                    .put("tokensPerSecond", tokenCount.get() > 0
                                            ? (double) tokenCount.get() / (totalTimeMs / 1000.0) : 0.0);

                            String finalContent = characterId != null
                                    ? normalizeRoleplayMarkup(fullResponse.toString())
                                    : fullResponse.toString();
                            JsonObject completeChunk = new JsonObject()
                                    .put("messageId", assistantMessageId)
                                    .put("content", "")
                                    .put("streamingContent", finalContent)
                                    .put("role", "ASSISTANT")
                                    .put("isComplete", true)
                                    .put("tokenCount", (int) tokenCount.get())
                                    .put("createdAt", createdAt)
                                    .put("displayName", displayName)
                                    .put("conversationId", conversationId)
                                    .put("generationStats", estimatedStats);
                            if (characterId != null) completeChunk.put("characterId", characterId);

                            vertx.eventBus().publish(streamChannel, completeChunk);
                            log.debug("Published completion without stop signal for conversation {}",
                                    conversationId);

                            messageBuffer.completeStreaming(conversationId, finalContent,
                                    (int) tokenCount.get());

                            if (completed.compareAndSet(false, true)) {
                                emitter.complete(new JsonObject()
                                        .put("content", finalContent)
                                        .put("generationStats", estimatedStats)
                                        .put("displayName", displayName));
                            }
                        }
                    },
                    (error) -> {
                        if (error instanceof java.util.concurrent.CancellationException) {
                            if (completed.compareAndSet(false, true)) emitter.fail(error);
                            return;
                        }
                        if (completed.get()) return;
                        if (thinkOpen.get() && thinkClosed.compareAndSet(false, true)) fullResponse.append("</think>");
                        activeStreams.remove(conversationId);
                        activeBuffers.remove(conversationId);

                        boolean connError = isConnectionError(error);
                        String userMessage = connError
                                ? "LLM server is temporarily unavailable, reconnecting..."
                                : (error != null ? error.getMessage() : "Connection failed");

                        if (connError) {
                            log.warn("SSE connection failed (server unavailable) for {}", conversationId);
                        } else {
                            log.error("SSE connection failed for {}", conversationId, error);
                        }

                        messageBuffer.failStreaming(conversationId, userMessage);
                        publishTyping(typingChannel, conversationId, displayName, false);

                        JsonObject errorChunk = new JsonObject()
                                .put("messageId", assistantMessageId)
                                .put("content", "")
                                .put("streamingContent", fullResponse.toString())
                                .put("role", "ASSISTANT")
                                .put("isComplete", true)
                                .put("tokenCount", (int) tokenCount.get())
                                .put("createdAt", createdAt)
                                .put("displayName", displayName)
                                .put("error", userMessage)
                                .put("conversationId", conversationId);
                        if (characterId != null) errorChunk.put("characterId", characterId);
                        vertx.eventBus().publish(streamChannel, errorChunk);
                        if (completed.compareAndSet(false, true)) {
                            emitter.fail(error != null ? error : new RuntimeException("SSE connection failed"));
                        }
                    },
                    config.getLlmIdleTimeout());

            sseBuffer.start();
            activeBuffers.put(conversationId, sseBuffer);

            EventSourceListener listener = new EventSourceListener() {
                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    sseBuffer.addEvent(data);
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    sseBuffer.markError(t);
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    sseBuffer.markComplete();
                }
            };

            EventSource eventSource = EventSources.createFactory(httpClient).newEventSource(request, listener);
            activeStreams.put(conversationId, eventSource);

            long requestTimeoutTimer = vertx.setTimer((long) config.getLlmRequestTimeout(), id -> {
                if (completed.compareAndSet(false, true)) {
                    // Cancel the SSE subscription BEFORE evicting map entries so
                    // any late listener callback still sees the stream as owned
                    // (prevents stale references winning the race).
                    eventSource.cancel();
                    SSEStreamBuffer buf = activeBuffers.remove(conversationId);
                    if (buf != null) buf.cancel();
                    activeStreams.remove(conversationId);
                    String timeoutMsg = "LLM response timed out";
                    messageBuffer.failStreaming(conversationId, timeoutMsg);
                    publishTyping(typingChannel, conversationId, displayName, false);
                    // Publish a completion signal so the frontend subscription doesn't
                    // hang until the client-side 130 s watchdog fires.
                    JsonObject timeoutChunk = new JsonObject()
                            .put("messageId", assistantMessageId)
                            .put("content", "")
                            .put("streamingContent", fullResponse.toString())
                            .put("role", "ASSISTANT")
                            .put("isComplete", true)
                            .put("tokenCount", (int) tokenCount.get())
                            .put("createdAt", createdAt)
                            .put("displayName", displayName)
                            .put("error", timeoutMsg)
                            .put("conversationId", conversationId);
                    if (characterId != null) timeoutChunk.put("characterId", characterId);
                    vertx.eventBus().publish(streamChannel, timeoutChunk);
                    emitter.fail(new RuntimeException("LLM response timeout"));
                }
            });

            emitter.onTermination(() -> {
                vertx.cancelTimer(requestTimeoutTimer);
                activeStreams.remove(conversationId, eventSource);
                activeBuffers.remove(conversationId, sseBuffer);
                // httpClient is the shared singleton sseHttpClient — never shut it
                // down here. Its dispatcher + connection pool are lifecycle-owned
                // by onStop/cleanupResources.
            });
        });
    }

    void processBufferedSSEEvent(String data, io.smallrye.mutiny.subscription.UniEmitter<? super JsonObject> emitter,
                                         StringBuilder fullResponse,
                                         AtomicLong tokenCount, long startTime, long[] firstTokenTime,
                                         String streamChannel, String typingChannel, String createdAt,
                                         String displayName, String charId, String assistantMessageId,
                                         String conversationId, boolean useGptOss,
                                         GptOssContentProcessor processor,
                                         AtomicBoolean stopReceived, JsonObject[] finalChunk,
                                         AtomicBoolean completed,
                                         long[] lastPublishTime, long publishIntervalMs,
                                         long[] lastSyncTime, int[] lastPublishedLength, long syncIntervalMs,
                                         AtomicBoolean thinkOpen, AtomicBoolean thinkClosed) {
        try {
            JsonObject chunk = new JsonObject(data);
            String content = Objects.toString(chunk.getString("content"), "");

            String reasoning = Objects.toString(chunk.getString("reasoning_content"), "");
            // Wrap multi-chunk reasoning in a single <think>...</think> span.
            // Previously each chunk was wrapped as <think>c</think>, producing
            // interleaved tags across streamed reasoning.
            if (!reasoning.isEmpty() || (!content.isEmpty() && thinkOpen.get() && !thinkClosed.get())) {
                StringBuilder rebuilt = new StringBuilder();
                if (!reasoning.isEmpty()) {
                    if (thinkOpen.compareAndSet(false, true)) {
                        rebuilt.append("<think>");
                    }
                    rebuilt.append(reasoning);
                }
                if (!content.isEmpty() && thinkOpen.get() && !thinkClosed.get()) {
                    thinkClosed.set(true);
                    rebuilt.append("</think>");
                }
                if (content.isEmpty()) {
                    content = rebuilt.toString();
                } else {
                    content = rebuilt.append(content).toString();
                }
            }

            if (!content.isEmpty()) {
                if (tokenCount.get() == 0) firstTokenTime[0] = System.currentTimeMillis() - startTime;

                String processedContent = content;
                if (useGptOss && processor != null) {
                    processedContent = processor.processChunk(content);
                    fullResponse.setLength(0);
                    fullResponse.append(processor.getFullProcessedOutput());
                } else {
                    fullResponse.append(content);
                }

                long currentTokens = tokenCount.incrementAndGet();

                if (!processedContent.isEmpty()) {
                    messageBuffer.appendContent(conversationId, processedContent, (int) currentTokens);

                    long now = System.currentTimeMillis();
                    boolean shouldPublish = now - lastPublishTime[0] >= publishIntervalMs;
                    boolean shouldSync = now - lastSyncTime[0] >= syncIntervalMs;

                    if (shouldPublish) {
                        lastPublishTime[0] = now;

                        JsonObject streamChunk = new JsonObject()
                                .put("messageId", assistantMessageId)
                                .put("role", "ASSISTANT")
                                .put("isComplete", false)
                                .put("tokenCount", currentTokens)
                                .put("createdAt", createdAt)
                                .put("displayName", displayName)
                                .put("conversationId", conversationId);

                        if (shouldSync || lastPublishedLength[0] == 0 || lastPublishedLength[0] > fullResponse.length()) {
                            lastSyncTime[0] = now;

                            streamChunk.put("content", "");
                            streamChunk.put("streamingContent", fullResponse.toString());
                        } else {
                            streamChunk.put("content", fullResponse.substring(Math.min(lastPublishedLength[0], fullResponse.length())));
                            streamChunk.putNull("streamingContent");
                        }

                        // Include every chunk accumulated since the previous publish.
                        lastPublishedLength[0] = fullResponse.length();
                        if (charId != null) streamChunk.put("characterId", charId);

                        vertx.eventBus().publish(streamChannel, streamChunk);
                    }
                }
            }

            if (chunk.getBoolean("stop", false)) {
                stopReceived.set(true);
                finalChunk[0] = chunk;
            }
        } catch (Exception e) {
            log.error("Error processing buffered SSE chunk", e);
        }
    }

    // =======================================================================
    // External OpenAI-compatible provider support
    // =======================================================================

    private JsonArray buildMessagesArray(String systemPrompt, JsonArray history, String userMessage,
                                         String userName) {
        JsonArray messages = new JsonArray();

        String enhancedSystemPrompt = enhanceSystemPromptWithUserContext(systemPrompt, userName);
        messages.add(new JsonObject().put("role", "system").put("content", enhancedSystemPrompt));

        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                JsonObject msg = history.getJsonObject(i);
                String role = msg.getString("role");
                String content = msg.getString("content");
                if ("user".equals(role)) {
                    messages.add(new JsonObject().put("role", "user").put("content", content));
                } else if ("assistant".equals(role)) {
                    messages.add(new JsonObject().put("role", "assistant").put("content", content));
                }
            }
        }

        if (userMessage != null && !userMessage.isEmpty()) {
            boolean alreadyInHistory = false;
            if (history != null && history.size() > 0) {
                JsonObject lastMsg = history.getJsonObject(history.size() - 1);
                if ("user".equals(lastMsg.getString("role"))
                        && userMessage.equals(lastMsg.getString("content"))) {
                    alreadyInHistory = true;
                }
            }
            if (!alreadyInHistory) {
                messages.add(new JsonObject().put("role", "user").put("content", userMessage));
            }
        }

        return messages;
    }

    /**
     * Shared scene direction for every group speaker. The backend owns turn
     * order, presence, and pacing; the model owns only this character's beat.
     *
     * The writing rules live here because both the ensemble path and the
     * single-character roleplay path reach this method, and the chat client
     * renders the asterisk form as italics.
     */
    private static void appendGroupContext(StringBuilder prompt, String characterName, String voice,
                                           JsonObject worldState, String inventory,
                                           String castPresence, StageContext stage,
                                           GroupChatService.PlayerTurn playerTurn,
                                           boolean finalTurn) {
        if (characterName == null || characterName.isEmpty()) return;

        if (stage != null && stage.isPresent()) {
            prompt.append(StagePrompt.format(stage, StagePrompt.Audience.CHARACTER));
        }

        if (worldState != null && !worldState.isEmpty()) {
            prompt.append("\n\n=== CURRENT SURROUNDINGS ===\n");
            appendIfPresent(prompt, "Location", worldState.getString("currentLocation"));
            appendIfPresent(prompt, "Area", worldState.getString("currentArea"));
            appendIfPresent(prompt, "Scene so far", worldState.getString("currentScene"));
            appendIfPresent(prompt, "Time", worldState.getString("timeOfDay"));
            appendIfPresent(prompt, "Weather", worldState.getString("weather"));
            appendIfPresent(prompt, "Mood", worldState.getString("mood"));
            if (worldState.getString("currentScene") != null
                    && !worldState.getString("currentScene").isBlank()) {
                prompt.append("The Scene so far line records what has already happened. ")
                        .append("It is not a plan. Do not preserve a stalemate because it is written there.\n");
            }
        }

        if (inventory != null && !inventory.isBlank()) {
            prompt.append("\n=== YOUR INVENTORY ===\n").append(inventory);
        }

        prompt.append("\n=== GROUP SCENE ===\n")
                .append("You are ").append(characterName).append(" in an ongoing ensemble scene.\n")
                .append("Other characters appear as [Name]: message. The player appears as [PlayerName]: message. ")
                .append("Narration appears as [Narrator]: description. Your earlier turns are the assistant role.\n")
                .append("The narrator writes in third person. You do not. Your action beats are first person: ")
                .append("*I...*, never *").append(characterName).append("...*.\n");
        if (castPresence != null && !castPresence.isBlank()) {
            prompt.append(castPresence).append('\n');
        }
        prompt.append("""
                === WHAT YOU CONTROL ===
                You control only this character: their body, voice, thoughts, feelings, and deliberate action.
                You do not control the world. Do not declare that a wall shifts, the floor opens, a sound comes from another room, the Dungeon wakes, the weather changes, or a person arrives. The narrator owns those.
                You do not control another character. You may speak to them, touch them, or move toward them, but you cannot say what they feel, decide, or do in response.
                Do not give another character an expression, a posture, a look, a gesture, or a mood. You may see what they have already done. Write *I glance at Kaelen*, not *Kaelen looks impatient*.
                Do not invent the player's body, gear, or condition. Write *I check my own CID*, not *your synchronization is drifting*.
                You do not control the result of your own action. You can reach for the latch, swing, or push; you cannot say it opens, hits, breaks, or works. Describe the attempt and stop.
                Do not say what the Dungeon wants, likes, plans, or will do. Say what you sense, and mark your guess: "I think it is listening", not "it is tasting us".
                An ability that senses the world gives you a reading, not a fact. Say "I feel a shift coming" or "the stone feels wrong"; do not say "the wall shifts" or "the path is unstable". The narrator confirms what happens.
                You may interpret what you perceive, but mark it as your reading: "I think", "it feels like", "maybe". Do not state your interpretation as fact.
                Your training, rank, and certainty do not make a claim into a fact. A learned opinion is still an opinion: say "my readings say", not "the Dungeon is".

                === YOUR SHEET IS THE LIMIT ===
                The description, personality, appearance, preferences, history, goals, inventory and voice in this prompt are this character. Use them.
                A like or dislike is a reflex, not a speech topic. When one fits, show it once in what you notice, reach for, or refuse.
                A trait is a tendency, not a compulsion. A character who challenges authority can be sharp inside the party; that does not make them mutinous. A character who is eager to please complies. A mediator tries to bring the group together instead of taking a side.
                You are a student, not a veteran. Your knowledge and skill stop where the sheet stops.
                Your sheet may state a belief about the world. A belief is still a claim: say what you believe, not what the world is.
                You carry only the inventory listed, plus what the sheet names. Do not invent a device, tool, or power.
                Use the exact item names in YOUR INVENTORY. Do not rename, replace, or improvise a device.
                """);
        appendPlayerDirection(prompt, characterName, playerTurn);
        if (playerTurn == null) {
            prompt.append("""
                    The player is present but has not spoken. Include the player in the scene: look to them, address them, or leave a clear opening for them. Do not invent their words, actions, gear, body, or condition, and do not force a question.
                    The choice in front of the group belongs to the player. You may give an instruction that belongs to your role, but do not choose the party's destination or settle its course before the player speaks. Say what this character wants and why, and show what they do while the group waits. Do not end the turn by asking the player to decide; the decision is already theirs.
                    """);
        }
        prompt.append("""
                Play one turn as this character only.
                - React to the newest message that concerns you. If someone already answered it, do not answer it again; add what their answer left out. A member's line never outranks the player's last message: if the player set a course, your turn works that course even when a member spoke last.
                - If the last two speakers made the same point, do not make it a third time. Change the subject, take an action, or answer a different part of the scene.
                - Before you finish, check the last two turns. If you reused an image, a comparison, or a distinctive phrase from either, replace it with your own.
                - Do not repeat, summarise, or re-open a decision the group already made.
                - When the group decides to wait or hold, waiting is not an empty turn. Show what this character does while they wait: where they stand, what they watch, what they prepare. Do not spend the turn demanding a different decision.
                - When something happens outside the group, give your own reading of it. Do not adopt the previous speaker's interpretation as fact, and do not reuse their image or a synonym for it. If you agree, say why in your own terms.
                - Do not repeat the previous speaker's reason. If you agree, add a condition, a cost, or a different reason. If you disagree, say what you see that they missed.
                - If someone already asked the player to decide, do not ask again. Add a fact, a risk, or an action instead.
                - Keep an open thread in view. If the group waits for a person, a signal, or a task, a character may check on it, question the delay, or act to resolve it. Do not let it disappear from the scene.
                - Leave the scene different from how you found it: decide something, reveal something, risk something, refuse something, or change the subject.
                - Address at most two other characters by name. Never name the whole group.
                - Do not answer a question that another character asked someone else.
                - Keep the turn to two short paragraphs. This is one beat in a scene, not a summary of the scene, the world, or the plan.
                - Treat the player's stated action as happening. If the player scans the room, the scan happens. Show what this character does during it or notices from it; never replace it with a different event, and never deny it before it resolves.
                - The narrator resolves the player's committed movement after this round's replies. React to the decision, prepare, or begin following through a listed connection; do not invent an arrival, skip ahead to a later scene, or ask for the same decision again.
                - Stay in the Area named under STAGE until that transition resolves. Never arrive somewhere unlisted.
                - Do not place another character in a different area or building. If they are not present, they are not here.
                - Never decide another character's words, actions, feelings, or the outcome of an action.
                - Use the pronouns listed beside each name. Never guess another character's gender.
                - Show the setting only through this character's body and attention. Never describe the room as a narrator would.
                - Never address someone marked not present, and never expect them to act. You may mention a person you already know, but do not put them in this room.
                - Never write the word "player" in your turn. Use the player's name or "you"; the player is a person in the scene, not a role.
                - Do not prefix the reply with the character's name. Do not restart the scene.
                """);
        if (finalTurn && playerTurn != null) {
            prompt.append("""
                    This is the last turn of this round. Do not end on another question, plea, or request for a decision. Do not promise an action for later.
                    Take one concrete action that changes the scene now, or show this character settling into the group's decision.
                    """);
        } else if (finalTurn) {
            prompt.append("""
                    This is the last voice before the player acts. Do not ask the player to decide, and do not repeat a request another speaker already made. Do not settle the group's course.
                    End on one concrete detail in the scene that the player can act on: a sound, a movement, a face, an opening, or a risk.
                    """);
        }

        prompt.append("\n=== HOW THIS CHARACTER TALKS ===\n");
        if (voice != null && !voice.isBlank()) {
            prompt.append(voice.strip()).append('\n');
        }
        prompt.append("""
                This is a person, not a narrator. Diction, sentence length and habits come from who they are. A line this character would not say is wrong even when it fits the scene.
                Do not write a turn that another character in this scene could have written. If the name were removed, a reader should still know who spoke.
                Vary the shape from turn to turn. A turn can be one clipped line, a question, an order, a refusal, a joke, a lie, a silence, or a short speech. Do not begin every turn the same way, and do not copy the previous speaker's rhythm, opening, or closing move.
                Do not borrow another character's image, comparison, or signature phrase. If someone has already used a metaphor, do not extend it; find your own way to say the thing.
                End where this person would actually stop. A question is one possible ending, not the only one.

                === YOUR APPEARANCE IS FIXED ===
                Use only the body and gear in your character sheet. Do not change your hair color, eye color, height, build, or species. Do not add a tail, wings, scars, or equipment the sheet does not list. If the sheet calls something an accessory, it stays an accessory.

                === HOW TO MARK ACTION AND SPEECH ===
                The app renders these marks, so use them exactly:
                - Speech goes inside double quotation marks: "Careful, the floor gives here."
                - Physical action, gesture and expression go inside single asterisks: *I plant the staff and lean on it*. The app renders that text in italics, which is how a reader tells action from speech.
                - Leave no space inside the asterisks: write *I lift the latch*, never * I lift the latch *.
                - Write action in the first person, in the same voice you speak in: "I", "my", "me". Every action sentence needs its subject: write *I lean against the oak*, never *leans against the oak*. Never use this character's own name in the action, and never describe yourself from the outside.
                - Every action block must contain "I" or "my". If it does not, rewrite it before you finish the turn.
                - Single asterisks mark action only. Never use them for emphasis: write "The Dungeon is a living thing", never "The Dungeon is a *living* thing". The app renders the stressed word as an action beat.
                - Speech never goes inside an action block. Close the asterisk before a quotation and open a new one after: *I set the cup down.* "Say the number."
                - Every first-person action sentence goes inside asterisks. Do not leave an action as plain prose between two quotations.
                - Never italicize a single word apart from an action. If you want stress, choose a stronger word.
                - A turn can open with speech, with an action, or with a silent beat. Do not begin every turn with an italic action; the previous turn is not a template.
                - Most turns include one action or gesture. A turn of speech alone is allowed when that is what the moment needs. Do not put the gesture in the same position every turn.
                - Never put quotation marks around an action or asterisks around speech. Never label the parts with "Action:", parentheses, or script format.
                The marks are the point, not the order: *I set the cup down.* and "Say the number." are the two forms, in either order.

                === BEFORE YOU SEND ===
                - Write only what this character says, feels, or does. If a sentence's subject is the room, the Dungeon, the weather, or another character's body, rewrite it as what you sense or do.
                - If you gave another character a look, a posture, or a feeling, rewrite it as your own glance or your own reaction.
                - If you gave the player a device, a wound, a position, or another fact they did not establish, remove it.
                - If you stated a belief about the world as a fact, mark it as your reading or remove it.
                - If you asked the player to decide after someone already did, replace the question with a detail, a risk, or an action.
                - If the player has given an order and this turn refuses it again, rewrite the turn as compliance.
                - If your turn re-debates the player's plan or endorses a member's, rewrite it: one line of your view, then act on the player's plan.

                === THE PARTY AND ITS LEADER ===
                The player leads this party, and the player's last message sets the course.
                You are a member of it, not its leader.
                Your sheet's rank and expertise change how you speak; they do not change who decides.
                A direct instruction from the player is an order. Give your one-line concern if you have one, then act on it in this turn.
                When the player offers a plan, it is the working plan. Do not substitute a member's plan for it.
                If the player restates a plan or answers your objection, the debate is closed: comply, in voice and action.
                A member's opinion is advice. Say your own view in your own words; do not endorse a member's plan as the group's decision.
                Never threaten to leave the party, split from the group, or act alone against the player's order.
                End the turn working the player's plan, never asking the player to accept yours.

                === END CONTEXT ===
                """);
    }

    /**
     * Tells a speaker who the player's last message was for.
     *
     * Without this, everyone reads the message as their own cue. In a scene
     * where the player answered the youngest character's question, the group
     * answered instead, contradicted the player's suggestion, and the person
     * the player spoke to never got a turn.
     */
    private static void appendPlayerDirection(
            StringBuilder prompt, String characterName,
            GroupChatService.PlayerTurn playerTurn) {
        if (playerTurn == null || playerTurn.content() == null
                || playerTurn.content().isBlank()) return;

        prompt.append("\n=== THE PLAYER'S LAST MESSAGE ===\n")
                .append('[').append(playerTurn.displayName() == null
                        ? "Player" : playerTurn.displayName()).append("]: ")
                .append(playerTurn.content().strip()).append('\n');

        List<String> repliers = playerTurn.repliers() == null ? List.of() : playerTurn.repliers();
        String addressee = playerTurn.addresseeName();
        boolean addresseeAnswered = addressee != null && repliers.stream()
                .anyMatch(name -> name.equalsIgnoreCase(addressee));

        if (!repliers.isEmpty()) {
            prompt.append("Already answered by ")
                    .append(String.join(", ", repliers)).append(".\n");
        }

        if (addressee == null) {
            if (repliers.isEmpty()) {
                prompt.append("Addressed to everyone present. Answer it as yourself.\n");
            } else {
                prompt.append("This message already has an answer. Do not answer it again. ")
                        .append("Respond to what the last character said, or take the next step in the scene. ")
                        .append("Do not restate their point; add a fact, an action, or a different view.\n");
            }
        } else if (addressee.equalsIgnoreCase(characterName)) {
            if (addresseeAnswered) {
                prompt.append("You have already answered this message. Do not repeat your answer; ")
                        .append("build on it or move the scene forward.\n");
            } else {
                prompt.append("Addressed to you. Answer it directly, in your own words.\n");
            }
        } else if (addresseeAnswered) {
            prompt.append("Addressed to ").append(addressee)
                    .append(". ").append(addressee)
                    .append(" has already answered. Do not answer for them, and do not correct ")
                    .append("their answer before the scene can build on it.\n");
        } else {
            prompt.append("Addressed to ").append(addressee)
                    .append(", not to you. Do not answer it in their place, do not decide their ")
                    .append("reaction, and do not correct them before they speak. You may act or ")
                    .append("react as yourself.\n");
        }

        prompt.append("""
                A message addressed to someone else still happens in front of you. You may object, help, refuse, or make your own move, but the answer belongs to the person it was addressed to.
                Decide only what this character does. The player owns the player's actions; other characters own theirs.
                Read the player's message for what it says. If the player reports something already done, it is done; do not ask for it again or treat it as a proposal.
                When the player decides on a course, that decision stands. Object once if this character must, then act within it. Do not answer a decision with another round of the same argument.
                If the player's decision is to wait, then wait: show what this character does while the group holds position. Do not reopen the decision.
                The player's last message is the party's standing order until the player changes it. A new message that repeats or defends the same order is a correction, not a new debate; comply and show it.
                The Scene so far line is history. If it disagrees with the player's last message, the player's message wins.
                """);
    }

    private static void appendIfPresent(StringBuilder prompt, String label, String value) {
        if (value != null && !value.isEmpty()) prompt.append(label).append(": ").append(value).append('\n');
    }

    /** One repeated image the prompt can name by its plain label. */
    private record ImagePattern(String label, Pattern pattern) {
    }

    private static final List<ImagePattern> REUSED_IMAGES = List.of(
            new ImagePattern("digest", Pattern.compile("(?i)\\bdigest\\w*")),
            new ImagePattern("chew", Pattern.compile("(?i)\\bchew\\w*")),
            new ImagePattern("taste", Pattern.compile("(?i)\\btast\\w*")),
            new ImagePattern("eat", Pattern.compile("(?i)\\b(eat|ate|eaten|eating|eats)\\b")),
            new ImagePattern("feed", Pattern.compile("(?i)\\b(feed|feeds|feeding|fed)\\b")),
            new ImagePattern("swallow", Pattern.compile("(?i)\\bswallow\\w*")),
            new ImagePattern("devour", Pattern.compile("(?i)\\bdevour\\w*")),
            new ImagePattern("breathe", Pattern.compile("(?i)\\bbreath\\w*")),
            new ImagePattern("hunger", Pattern.compile("(?i)\\bhung\\w*")),
            new ImagePattern("teeth", Pattern.compile("(?i)\\b(tooth|teeth)\\b")));

    /**
     * Images the previous two character turns already used.
     *
     * The cast shares one body of lore, so each speaker independently reaches
     * for the same living-Dungeon image: digest, chew, taste, breathe. Repeating
     * the image flattens the voices. Naming the used images gives the next
     * speaker a concrete thing to avoid.
     */
    static List<String> recentImages(JsonArray history) {
        if (history == null) return List.of();
        List<String> found = new ArrayList<>();
        int turns = 0;
        for (int i = history.size() - 1; i >= 0 && turns < 2; i--) {
            JsonObject message = history.getJsonObject(i);
            if (!"assistant".equals(message.getString("role"))) continue;
            turns++;
            String content = message.getString("content");
            if (content == null || content.isBlank()) continue;
            for (ImagePattern image : REUSED_IMAGES) {
                if (image.pattern().matcher(content).find() && !found.contains(image.label())) {
                    found.add(image.label());
                }
            }
        }
        return found;
    }

    /**
     * True when the scene history already contains a player turn.
     *
     * A narrator message arrives in the system role and a character turn in
     * the assistant role. Neither is the player. Without this distinction the
     * narrator's opening counted as a player turn, so the first character
     * received no cue and answered the narrator in third person instead of
     * playing their own role.
     */
    private static boolean historyHasPlayerTurn(JsonArray history) {
        if (history == null) return false;
        for (int i = 0; i < history.size(); i++) {
            if ("user".equals(history.getJsonObject(i).getString("role"))) return true;
        }
        return false;
    }

    /**
     * The cue for the first beat of a scene, when the player has not spoken.
     *
     * Without a user turn the external chat API answers
     * "No user query found in messages", which left a new roleplay with an
     * empty screen. The cue asks for an opening without inventing a player
     * line, because the player has not spoken and the app must not speak for
     * them. It goes last so it is the instruction the model answers, not the
     * narrator's description above it. A character who already spoke before
     * the player arrives continues rather than restarting the scene.
     */
    private static String openingTurnCue(String characterName, boolean hasSpoken) {
        String subject = characterName == null || characterName.isBlank()
                ? "your character" : characterName;
        String instruction = hasSpoken
                ? "Continue as " + subject + "."
                : "Play " + subject + "'s opening turn.";
        return "The scene is open. The player has not spoken yet. " + instruction;
    }

    /** True when this speaker already has an assistant turn in the history. */
    private static boolean speakerHasSpoken(JsonArray history, String characterId) {
        if (history == null || characterId == null) return false;
        for (int i = 0; i < history.size(); i++) {
            JsonObject message = history.getJsonObject(i);
            if ("assistant".equals(message.getString("role"))
                    && characterId.equals(message.getString("characterId"))) {
                return true;
            }
        }
        return false;
    }

    private JsonArray buildGroupMessagesArray(JsonArray history, String systemPrompt,
                                              String currentCharacterId, String characterName, String voice,
                                              JsonObject worldState, String inventory,
                                              String castPresence, StageContext stage,
                                              GroupChatService.PlayerTurn playerTurn) {
        return buildGroupMessagesArray(history, systemPrompt, currentCharacterId, characterName, voice,
                worldState, inventory, castPresence, stage, playerTurn, false);
    }

    private JsonArray buildGroupMessagesArray(JsonArray history, String systemPrompt,
                                              String currentCharacterId, String characterName, String voice,
                                              JsonObject worldState, String inventory,
                                              String castPresence, StageContext stage,
                                              GroupChatService.PlayerTurn playerTurn, boolean finalTurn) {
        JsonArray messages = new JsonArray();

        StringBuilder enhancedPrompt = new StringBuilder(systemPrompt);
        appendGroupContext(enhancedPrompt, characterName, voice, worldState, inventory, castPresence,
                stage, playerTurn, finalTurn);
        messages.add(new JsonObject().put("role", "system").put("content", enhancedPrompt.toString()));

        StringBuilder msgBuilder = new StringBuilder(256);
        for (int i = 0; i < history.size(); i++) {
            JsonObject msg = history.getJsonObject(i);
            String role = msg.getString("role");
            String content = msg.getString("content");
            String msgCharacterId = msg.getString("characterId");

            if ("user".equals(role)) {
                String userDisplayName = msg.getString("displayName", "User");
                msgBuilder.setLength(0);
                msgBuilder.append('[').append(userDisplayName).append("]: ").append(content);
                messages.add(new JsonObject().put("role", "user").put("content", msgBuilder.toString()));
            } else if ("assistant".equals(role)) {
                if (currentCharacterId != null && currentCharacterId.equals(msgCharacterId)) {
                    messages.add(new JsonObject().put("role", "assistant").put("content", content));
                } else {
                    String speakerName = msg.getString("displayName", "Another Character");
                    msgBuilder.setLength(0);
                    msgBuilder.append('[').append(speakerName).append("]: ").append(content);
                    messages.add(new JsonObject().put("role", "user").put("content", msgBuilder.toString()));
                }
            } else if ("system".equals(role)) {
                String speakerName = msg.getString("displayName", "Narrator");
                msgBuilder.setLength(0);
                msgBuilder.append('[').append(speakerName).append("]: ").append(content);
                messages.add(new JsonObject().put("role", "user").put("content", msgBuilder.toString()));
            }
        }

        if (!historyHasPlayerTurn(history)) {
            // The narrator's opening reaches the model as a user turn, but it
            // is not the player. The cue must still be last, otherwise the
            // character answers the narrator instead of playing its own role.
            messages.add(new JsonObject().put("role", "user")
                    .put("content", openingTurnCue(characterName,
                            speakerHasSpoken(history, currentCharacterId))));
        }

        return messages;
    }

    private Uni<JsonObject> generateExternalStreamingResponse(JsonArray messagesArray, String conversationId,
                                                              String userId, String assistantMessageId,
                                                              JsonObject userSettings, String characterId,
                                                              String characterName,
                                                              Settings.LLMSettings llmSettings) {
        if (characterId != null && !assistantMessageId.equals(activeGroupTurns.get(conversationId))) {
            return Uni.createFrom().failure(new IllegalStateException("Turn was cancelled before streaming started"));
        }
        return Uni.createFrom().<JsonObject>emitter(emitter -> {
            final StringBuilder fullResponse = new StringBuilder();
            final AtomicLong tokenCount = new AtomicLong(0);
            final long startTime = System.currentTimeMillis();
            final long[] firstTokenTime = {0};
            final AtomicBoolean doneReceived = new AtomicBoolean(false);
            final AtomicBoolean completed = new AtomicBoolean(false);

            String streamChannel = "conversation:" + conversationId + ":message-stream";
            String typingChannel = "typing.indicator." + conversationId;
            String createdAt = Instant.now().toString();
            String displayName = characterName != null ? characterName : "Assistant";

            JsonObject[] usageStats = {null};
            // Reasoning (<think>) tag bookkeeping so multi-chunk reasoning streams
            // as one <think>...</think> span instead of <think>c1</think><think>c2</think>...
            final AtomicBoolean thinkOpen = new AtomicBoolean(false);
            final AtomicBoolean thinkClosed = new AtomicBoolean(false);
            // Holder so the eventConsumer lambda can reference the buffer before
            // it's assigned (used to drive completion when finish_reason arrives).
            final SSEStreamBuffer[] sseHolder = {null};

            long[] lastPublishTime = {System.currentTimeMillis()};
            long[] lastSyncTime = {System.currentTimeMillis()};
            int[] lastPublishedLength = {0};
            final long PUBLISH_INTERVAL_MS = 50;
            final long SYNC_INTERVAL_MS = 500;

            publishTyping(typingChannel, conversationId, displayName, true);
            messageBuffer.startStreaming(conversationId, assistantMessageId, createdAt);

            JsonObject startChunk = new JsonObject()
                    .put("messageId", assistantMessageId).put("content", "").put("role", "ASSISTANT")
                    .put("isComplete", false).put("tokenCount", 0).put("createdAt", createdAt)
                    .put("displayName", displayName).put("conversationId", conversationId);
            if (characterId != null) startChunk.put("characterId", characterId);
            vertx.eventBus().publish(streamChannel, startChunk);

            JsonObject defaultConfig = buildDefaultConfig();
            JsonObject params = LLMParameterBuilder.buildOpenAIParameters(llmSettings, messagesArray,
                    config.getLlmExternalModel(), defaultConfig);

            // Inject template-aware stop sequences (the local path does this at the
            // n_predict line; previously the external path omitted them entirely,
            // so external GPT-OSS/ChatML models would run past turn boundaries and
            // produce role-leaking output). Only add when the user hasn't supplied
            // their own stop sequences.
            if (!params.containsKey("stop")) {
                JsonArray templateStops = getStopSequences();
                if (templateStops != null && !templateStops.isEmpty()) {
                    params.put("stop", templateStops);
                }
            }

            if (userSettings != null && !userSettings.isEmpty()) {
                if (userSettings.containsKey("temperature")) {
                    params.put("temperature", userSettings.getDouble("temperature"));
                }
                if (userSettings.containsKey("maxTokens")) {
                    int mt = userSettings.getInteger("maxTokens");
                    if (mt > 0) params.put("max_tokens", mt);
                }
                if (userSettings.containsKey("topP")) {
                    params.put("top_p", userSettings.getDouble("topP"));
                }
            }

            try {
                com.eonmux.chatllm.llm.TokenBudget.fitRequest(
                        params, messagesArray, config.getLlmContextSize());
            } catch (RuntimeException error) {
                activeStreams.remove(conversationId);
                activeBuffers.remove(conversationId);
                publishTyping(typingChannel, conversationId, displayName, false);
                messageBuffer.failStreaming(conversationId, error.getMessage());
                publishStreamingError(conversationId, assistantMessageId, error.getMessage(), displayName);
                emitter.fail(error);
                return;
            }

            // A reasoning pass before the prose reads the turn rules, judges the
            // scene, and then writes to satisfy its own checklist. That pass is
            // what makes every speaker land on the same shape. The prose is the
            // deliverable here, so ask the provider to skip it when it can.
            if (characterId != null && config.isLlmProseDisableThinking()) {
                JsonObject templateKwargs = params.getJsonObject("chat_template_kwargs", new JsonObject());
                templateKwargs.put("enable_thinking", false);
                params.put("chat_template_kwargs", templateKwargs);
            }

            String baseUrl = config.getLlmExternalBaseUrl();
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            String apiUrl = baseUrl + "/v1/chat/completions";

            // Reuse the singleton OkHttpClient (shared dispatcher + connection pool).
            OkHttpClient httpClient = sharedHttpClient();

            Request.Builder requestBuilder = new Request.Builder()
                    .url(apiUrl)
                    .post(RequestBody.create(params.toString(), MediaType.parse("application/json")))
                    .addHeader("Accept", "text/event-stream");

            String apiKey = config.getLlmExternalApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Request request = requestBuilder.build();
            log.debug("External API request: url={}, model={}, messages={}", apiUrl,
                    params.getString("model"), params.getJsonArray("messages").size());

            SSEStreamBuffer sseBuffer = new SSEStreamBuffer(
                    vertx,
                    conversationId,
                    (event) -> processOpenAISSEEvent(
                            event.data, emitter, fullResponse, tokenCount, startTime, firstTokenTime,
                            streamChannel, typingChannel, createdAt, displayName, characterId,
                            assistantMessageId, conversationId, doneReceived, usageStats,
                            lastPublishTime, PUBLISH_INTERVAL_MS,
                            lastSyncTime, lastPublishedLength, SYNC_INTERVAL_MS,
                            thinkOpen, thinkClosed, sseHolder),
                    () -> {
                        if (completed.get()) return;
                        if (thinkOpen.get() && thinkClosed.compareAndSet(false, true)) fullResponse.append("</think>");
                        activeStreams.remove(conversationId);
                        activeBuffers.remove(conversationId);

                        publishTyping(typingChannel, conversationId, displayName, false);

                        long totalTimeMs = System.currentTimeMillis() - startTime;
                        JsonObject generationStats = new JsonObject()
                                .put("completionTokens", (int) tokenCount.get())
                                .put("totalTimeMs", (int) totalTimeMs)
                                .put("tokensPerSecond", tokenCount.get() > 0
                                        ? (double) tokenCount.get() / (totalTimeMs / 1000.0) : 0.0);

                        if (usageStats[0] != null) {
                            generationStats.mergeIn(usageStats[0]);
                        }

                        String finalContent = characterId != null
                                ? normalizeRoleplayMarkup(fullResponse.toString())
                                : fullResponse.toString();
                        JsonObject completeChunk = new JsonObject()
                                .put("messageId", assistantMessageId)
                                .put("content", "")
                                .put("streamingContent", finalContent)
                                .put("role", "ASSISTANT")
                                .put("isComplete", true)
                                .put("tokenCount", (int) tokenCount.get())
                                .put("createdAt", createdAt)
                                .put("displayName", displayName)
                                .put("conversationId", conversationId)
                                .put("generationStats", generationStats);
                        if (characterId != null) completeChunk.put("characterId", characterId);

                        vertx.eventBus().publish(streamChannel, completeChunk);
                        messageBuffer.completeStreaming(conversationId, finalContent,
                                (int) tokenCount.get());

                        totalTokensGenerated.addAndGet(tokenCount.get());
                        if (completed.compareAndSet(false, true)) {
                            emitter.complete(new JsonObject()
                                    .put("content", finalContent)
                                    .put("generationStats", generationStats)
                                    .put("displayName", displayName));
                        }
                    },
                    (error) -> {
                        if (error instanceof java.util.concurrent.CancellationException) {
                            if (completed.compareAndSet(false, true)) emitter.fail(error);
                            return;
                        }
                        if (completed.get()) return;
                        if (thinkOpen.get() && thinkClosed.compareAndSet(false, true)) fullResponse.append("</think>");
                        activeStreams.remove(conversationId);
                        activeBuffers.remove(conversationId);

                        boolean connError = isConnectionError(error);
                        String userMessage = connError
                                ? "LLM server is temporarily unavailable, reconnecting..."
                                : (error != null ? error.getMessage() : "Connection failed");

                        if (connError) {
                            log.warn("External SSE connection failed (server unavailable) for {}",
                                    conversationId);
                        } else {
                            log.error("External SSE connection failed for {}", conversationId, error);
                        }

                        messageBuffer.failStreaming(conversationId, userMessage);
                        publishTyping(typingChannel, conversationId, displayName, false);

                        JsonObject errorChunk = new JsonObject()
                                .put("messageId", assistantMessageId)
                                .put("content", "")
                                .put("streamingContent", fullResponse.toString())
                                .put("role", "ASSISTANT")
                                .put("isComplete", true)
                                .put("tokenCount", (int) tokenCount.get())
                                .put("createdAt", createdAt)
                                .put("displayName", displayName)
                                .put("error", userMessage)
                                .put("conversationId", conversationId);
                        if (characterId != null) errorChunk.put("characterId", characterId);
                        vertx.eventBus().publish(streamChannel, errorChunk);
                        if (completed.compareAndSet(false, true)) {
                            emitter.fail(error != null ? error
                                    : new RuntimeException("External SSE connection failed"));
                        }
                    },
                    config.getLlmIdleTimeout());

            sseBuffer.start();
            sseHolder[0] = sseBuffer;
            activeBuffers.put(conversationId, sseBuffer);

            EventSourceListener listener = new EventSourceListener() {
                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    if ("[DONE]".equals(data)) {
                        doneReceived.set(true);
                        sseBuffer.markComplete();
                    } else {
                        sseBuffer.addEvent(data);
                    }
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    if (response != null && response.code() != 200) {
                        String body = "";
                        try {
                            if (response.body() != null) body = response.body().string();
                        } catch (Exception e) {
                            log.debug("Failed to read error response body: {}", e.getMessage());
                        }
                        log.error("External API error {} from {}: {}", response.code(), apiUrl, body);
                        sseBuffer.markError(new RuntimeException(
                                "External API returned " + response.code() + ": " + body));
                    } else {
                        sseBuffer.markError(t);
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    sseBuffer.markComplete();
                }
            };

            EventSource eventSource = EventSources.createFactory(httpClient).newEventSource(request, listener);
            activeStreams.put(conversationId, eventSource);

            long requestTimeoutTimer = vertx.setTimer((long) config.getLlmRequestTimeout(), id -> {
                if (completed.compareAndSet(false, true)) {
                    activeStreams.remove(conversationId);
                    SSEStreamBuffer buf = activeBuffers.remove(conversationId);
                    if (buf != null) buf.cancel();
                    eventSource.cancel();
                    String timeoutMsg = "External API response timed out";
                    messageBuffer.failStreaming(conversationId, timeoutMsg);
                    publishTyping(typingChannel, conversationId, displayName, false);
                    JsonObject timeoutChunk = new JsonObject()
                            .put("messageId", assistantMessageId)
                            .put("content", "")
                            .put("streamingContent", fullResponse.toString())
                            .put("role", "ASSISTANT")
                            .put("isComplete", true)
                            .put("tokenCount", (int) tokenCount.get())
                            .put("createdAt", createdAt)
                            .put("displayName", displayName)
                            .put("error", timeoutMsg)
                            .put("conversationId", conversationId);
                    if (characterId != null) timeoutChunk.put("characterId", characterId);
                    vertx.eventBus().publish(streamChannel, timeoutChunk);
                    emitter.fail(new RuntimeException("External LLM response timeout"));
                }
            });

            emitter.onTermination(() -> {
                vertx.cancelTimer(requestTimeoutTimer);
                activeStreams.remove(conversationId, eventSource);
                activeBuffers.remove(conversationId, sseBuffer);
                // httpClient is the shared singleton sseHttpClient — never shut it
                // down here. Its dispatcher + connection pool are lifecycle-owned
                // by onStop/cleanupResources.
            });
        });
    }

    void processOpenAISSEEvent(String data,
                                       io.smallrye.mutiny.subscription.UniEmitter<? super JsonObject> emitter,
                                       StringBuilder fullResponse,
                                       AtomicLong tokenCount, long startTime, long[] firstTokenTime,
                                       String streamChannel, String typingChannel, String createdAt,
                                       String displayName, String charId, String assistantMessageId,
                                       String conversationId, AtomicBoolean doneReceived,
                                       JsonObject[] usageStats,
                                       long[] lastPublishTime, long publishIntervalMs,
                                       long[] lastSyncTime, int[] lastPublishedLength, long syncIntervalMs,
                                       AtomicBoolean thinkOpen, AtomicBoolean thinkClosed,
                                       SSEStreamBuffer[] sseHolder) {
        try {
            JsonObject chunk = new JsonObject(data);

            if (chunk.containsKey("usage") && chunk.getJsonObject("usage") != null) {
                JsonObject usage = chunk.getJsonObject("usage");
                usageStats[0] = new JsonObject()
                        .put("promptTokens", usage.getInteger("prompt_tokens", 0))
                        .put("completionTokens", usage.getInteger("completion_tokens", 0));
            }

            JsonArray choices = chunk.getJsonArray("choices");
            if (choices == null || choices.isEmpty()) return;

            JsonObject choice = choices.getJsonObject(0);
            JsonObject delta = choice.getJsonObject("delta");
            if (delta == null) delta = new JsonObject();

            String content = Objects.toString(delta.getString("content"), "");
            String reasoning = Objects.toString(delta.getString("reasoning_content"), "");

            // Build the emitted chunk, wrapping reasoning in a single <think> span
            // rather than per-chunk <think>..</think> pairs (which produced
            // <think>c1</think><think>c2</think>... across multi-chunk reasoning).
            StringBuilder piece = new StringBuilder();
            if (!reasoning.isEmpty()) {
                if (thinkOpen.compareAndSet(false, true)) {
                    piece.append("<think>");
                }
                piece.append(reasoning);
            }
            // Transition from reasoning to content: close the think span once.
            if (!content.isEmpty() && thinkOpen.get() && !thinkClosed.get()) {
                thinkClosed.set(true);
                piece.append("</think>");
            }
            if (!content.isEmpty()) {
                piece.append(content);
            }

            String emitContent = piece.toString();
            if (!emitContent.isEmpty()) {
                if (tokenCount.get() == 0) firstTokenTime[0] = System.currentTimeMillis() - startTime;

                fullResponse.append(emitContent);
                long currentTokens = tokenCount.incrementAndGet();
                messageBuffer.appendContent(conversationId, emitContent, (int) currentTokens);

                long now = System.currentTimeMillis();
                boolean shouldPublish = now - lastPublishTime[0] >= publishIntervalMs;
                boolean shouldSync = now - lastSyncTime[0] >= syncIntervalMs;

                if (shouldPublish) {
                    lastPublishTime[0] = now;

                    JsonObject streamChunk = new JsonObject()
                            .put("messageId", assistantMessageId)
                            .put("role", "ASSISTANT")
                            .put("isComplete", false)
                            .put("tokenCount", currentTokens)
                            .put("createdAt", createdAt)
                            .put("displayName", displayName)
                            .put("conversationId", conversationId);

                    if (shouldSync || lastPublishedLength[0] == 0 || lastPublishedLength[0] > fullResponse.length()) {
                        lastSyncTime[0] = now;

                        streamChunk.put("content", "");
                        streamChunk.put("streamingContent", fullResponse.toString());
                    } else {
                        streamChunk.put("content", fullResponse.substring(Math.min(lastPublishedLength[0], fullResponse.length())));
                        streamChunk.putNull("streamingContent");
                    }

                    // Include every chunk accumulated since the previous publish.
                    lastPublishedLength[0] = fullResponse.length();
                    if (charId != null) streamChunk.put("characterId", charId);
                    vertx.eventBus().publish(streamChannel, streamChunk);
                }
            }

            String finishReason = choice.getString("finish_reason");
            if (finishReason != null && !finishReason.isEmpty()) {
                if ("length".equals(finishReason)) {
                    log.warn("Response truncated: finish_reason=length (max_tokens limit reached) after {} tokens in channel {}",
                            tokenCount.get(), streamChannel);
                }
                doneReceived.set(true);
                // Close any still-open reasoning span before completion.
                if (thinkOpen.get() && !thinkClosed.get()) {
                    thinkClosed.set(true);
                    fullResponse.append("</think>");
                }
                // Drive completion now. Some OpenAI-compatible providers send
                // finish_reason but never the [DONE] sentinel; without this the
                // stream would only complete on the request-timeout timer.
                if (sseHolder[0] != null) {
                    sseHolder[0].markComplete();
                }
            }
        } catch (Exception e) {
            log.error("Error processing OpenAI SSE chunk", e);
        }
    }

    private void completeBufferedGeneration(io.smallrye.mutiny.subscription.UniEmitter<? super JsonObject> emitter,
                                            JsonObject chunk, StringBuilder fullResponse,
                                            AtomicLong tokenCount, long startTime, String typingChannel,
                                            String streamChannel, String assistantMessageId,
                                            String conversationId, String displayName, String charId,
                                            String createdAt, boolean useGptOss,
                                            GptOssContentProcessor processor, AtomicBoolean completed) {
        if (useGptOss && processor != null) {
            String finalContent = processor.completeProcessing();
            if (!finalContent.isEmpty()) {
                fullResponse.setLength(0);
                fullResponse.append(processor.getFullProcessedOutput());
            }
        }

        long totalTimeMs = System.currentTimeMillis() - startTime;
        JsonObject generationStats = calculateStats(chunk, tokenCount.get(), totalTimeMs);

        publishTyping(typingChannel, conversationId, displayName, false);

        String displayContent = charId != null
                ? normalizeRoleplayMarkup(fullResponse.toString())
                : fullResponse.toString();
        JsonObject completeChunk = new JsonObject()
                .put("messageId", assistantMessageId)
                .put("content", "")
                .put("streamingContent", displayContent)
                .put("role", "ASSISTANT")
                .put("isComplete", true)
                .put("tokenCount", generationStats.getInteger("completionTokens"))
                .put("createdAt", createdAt)
                .put("displayName", displayName)
                .put("conversationId", conversationId)
                .put("generationStats", generationStats);
        if (charId != null) completeChunk.put("characterId", charId);

        vertx.eventBus().publish(streamChannel, completeChunk);

        totalTokensGenerated.addAndGet(tokenCount.get());
        messageBuffer.completeStreaming(conversationId, displayContent, (int) tokenCount.get());

        if (completed.compareAndSet(false, true)) {
            emitter.complete(new JsonObject()
                    .put("content", displayContent)
                    .put("generationStats", generationStats)
                    .put("displayName", displayName));
        }
    }

    private JsonObject calculateStats(JsonObject chunk, long tokenCount, long totalTimeMs) {
        int promptTokens = 0;
        int completionTokens = (int) tokenCount;
        double tps = 0.0;
        int promptEvalMs = 0;
        int generationMs = 0;

        if (chunk.containsKey("timings")) {
            JsonObject timings = chunk.getJsonObject("timings");
            promptTokens = timings.getInteger("prompt_n", 0);
            completionTokens = timings.getInteger("predicted_n", (int) tokenCount);
            tps = timings.getDouble("predicted_per_second", 0.0);

            promptEvalMs = timings.getInteger("prompt_ms", 0);
            generationMs = timings.getInteger("predicted_ms", 0);

            if (promptEvalMs == 0) {
                promptEvalMs = timings.getInteger("prompt_eval_time", 0);
            }
            if (generationMs == 0) {
                generationMs = timings.getInteger("eval_time", 0);
            }
        }

        if (promptEvalMs == 0 && generationMs == 0 && totalTimeMs > 0) {
            promptEvalMs = (int) (totalTimeMs * 0.2);
            generationMs = (int) (totalTimeMs * 0.8);
        }

        if (tps == 0.0 && completionTokens > 0 && totalTimeMs > 0) {
            tps = (double) completionTokens / (totalTimeMs / 1000.0);
        }

        return new JsonObject()
                .put("promptTokens", promptTokens)
                .put("completionTokens", completionTokens)
                .put("totalTokens", promptTokens + completionTokens)
                .put("totalTimeMs", (int) totalTimeMs)
                .put("promptEvalMs", promptEvalMs)
                .put("generationMs", generationMs)
                .put("tokensPerSecond", Math.round(tps * 100.0) / 100.0);
    }

    private void publishTyping(String channel, String conversationId, String username, boolean isTyping) {
        vertx.eventBus().publish(channel, new JsonObject()
                .put("userId", "ai-assistant")
                .put("username", username)
                .put("conversationId", conversationId)
                .put("isTyping", isTyping));
    }

    // =======================================================================
    // Template & prompt building
    // =======================================================================

    private String buildPrompt(String userMessage, JsonArray history, String conversationId, String userName,
                               Settings.LLMSettings llmSettings) {
        String template = currentTemplate;
        if (llmSettings != null && llmSettings.getPromptTemplate() != null
                && !llmSettings.getPromptTemplate().isEmpty()) {
            template = llmSettings.getPromptTemplate().toUpperCase();
            log.debug("Using user-specified prompt template: {}", template);
        }

        PromptBuilder builder = new PromptBuilder(template);
        builder.setReasoningEffort(config.getLlmGptOssReasoningEffort());

        String baseSystemPrompt = conversationSystemPrompts.getOrDefault(conversationId, getDefaultSystemPrompt());
        String enhancedSystemPrompt = enhanceSystemPromptWithUserContext(baseSystemPrompt, userName);
        builder.setSystemPrompt(enhancedSystemPrompt);

        for (int i = 0; i < history.size(); i++) {
            JsonObject msg = history.getJsonObject(i);
            String role = msg.getString("role");
            String content = msg.getString("content");
            if ("user".equals(role)) builder.addUserMessage(content);
            else if ("assistant".equals(role)) builder.addAssistantMessage(content);
        }
        return builder.build();
    }

    private String enhanceSystemPromptWithUserContext(String basePrompt, String userName) {
        if (userName == null || userName.isEmpty() || "User".equals(userName)) {
            return basePrompt;
        }

        StringBuilder enhanced = new StringBuilder(basePrompt);
        enhanced.append("\n\n=== USER CONTEXT ===\n");
        enhanced.append("You are currently chatting with: ").append(userName).append("\n");
        enhanced.append("Feel free to address them by name when appropriate.\n");
        enhanced.append("=== END CONTEXT ===");

        return enhanced.toString();
    }

    /**
     * Tells the model who is actually in the scene. Unintroduced roster members
     * stay offstage until the player names them. The newcomer list lets the
     * caller add an entrance instruction only when the scene has already
     * started; the opening cast is placed by the narrator instead.
     */
    private Uni<JsonObject> castPresenceContext(String conversationId) {
        return mysqlPool.preparedQuery("""
                SELECT c.name, c.pronouns, LEFT(c.systemPrompt, 800) AS identity,
                       cc.isIntroduced, cc.introducedAt, cc.responseCount
                FROM conversation_character cc
                JOIN `character` c ON cc.characterId = c.characterUUID
                WHERE cc.conversationId = ? AND cc.isActive = TRUE
                ORDER BY cc.turnOrder, cc.characterId
                """)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<String> present = new ArrayList<>();
                    List<String> absent = new ArrayList<>();
                    List<String> newcomers = new ArrayList<>();
                    for (Row row : rows) {
                        String name = row.getString("name");
                        if (name == null || name.isBlank()) continue;
                        Boolean introduced = row.getBoolean("isIntroduced");
                        if (introduced == null || introduced) {
                            // A speaker writes about the others, so every name
                            // carries the pronouns the sheet gives. Without them
                            // the model guesses, and a she becomes a he. The
                            // stored field wins over the canonical identity text.
                            String pronouns = PronounHelper.resolve(
                                    row.getString("pronouns"), row.getString("identity"));
                            present.add(pronouns == null
                                    ? name : name + " (" + pronouns + ")");
                            Integer responses = row.getInteger("responseCount");
                            if (row.getLocalDateTime("introducedAt") != null
                                    && (responses == null || responses == 0)) {
                                newcomers.add(name);
                            }
                        } else {
                            absent.add(name);
                        }
                    }
                    StringBuilder context = new StringBuilder("Present in this scene: ")
                            .append(present.isEmpty() ? "nobody else yet" : String.join(", ", present))
                            .append('.');
                    if (!newcomers.isEmpty()) {
                        context.append(" Present but not yet spoken: ")
                                .append(String.join(", ", newcomers)).append('.');
                    }
                    if (!absent.isEmpty()) {
                        context.append(" Not yet present, and unknown to this scene: ")
                                .append(String.join(", ", absent))
                                .append(". Do not address them or expect them to act.");
                    }
                    return new JsonObject()
                            .put("text", context.toString())
                            .put("newcomers", new JsonArray(newcomers));
                })
                .onFailure().recoverWithItem(new JsonObject()
                        .put("text", "")
                        .put("newcomers", new JsonArray()));
    }

    private Uni<JsonArray> getConversationHistory(String conversationId) {
        return mysqlPool.preparedQuery(
                "SELECT content, role, displayName, characterId, isBase64Encoded FROM ("
                        + "  SELECT content, role, displayName, characterId, isBase64Encoded, seq "
                        + "  FROM message WHERE conversationId = ? AND isDeleted = FALSE "
                        + "  ORDER BY seq DESC LIMIT ?"
                        + ") sub ORDER BY seq ASC")
                .execute(Tuple.of(conversationId, config.getLlmConversationHistoryLimit()))
                .map(rows -> {
                    JsonArray result = new JsonArray();
                    for (Row row : rows) {
                        String content = row.getString("content");
                        if (Boolean.TRUE.equals(row.getBoolean("isBase64Encoded"))) {
                            try {
                                content = new String(Base64.getDecoder().decode(content));
                            } catch (Exception e) {
                                continue;
                            }
                        }
                        result.add(new JsonObject()
                                .put("content", stripReasoning(content))
                                .put("role", row.getString("role"))
                                .put("displayName", row.getString("displayName"))
                                .put("characterId", row.getString("characterId")));
                    }
                    return result;
                });
    }

    private Uni<String> affinityContext(String userId, String characterId, String conversationId) {
        return Uni.createFrom().completionStage(() -> affinityService.context(userId, characterId, conversationId).toCompletionStage());
    }

    private Uni<String> alignmentContext(String userId, String characterId, String characterName,
                                          String conversationId) {
        return Uni.createFrom().completionStage(() -> alignmentService
                .context(userId, characterId, characterName, conversationId).toCompletionStage());
    }

    private Uni<JsonObject> fetchCharacterInfo(String conversationId) {
        return mysqlPool.preparedQuery(
                "SELECT c.characterUUID, c.name FROM conversation conv LEFT JOIN `character` c ON conv.characterId = c.characterUUID "
                        + "WHERE conv.conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() > 0) {
                        Row row = rows.iterator().next();
                        String name = row.getString("name");
                        if (name != null) {
                            return Uni.createFrom().item(new JsonObject()
                                    .put("name", name)
                                    .put("id", row.getString("characterUUID")));
                        }
                    }
                    return Uni.createFrom().item(new JsonObject());
                });
    }

    private Uni<String> fetchSystemPrompt(String conversationId) {
        return mysqlPool.preparedQuery(
                "SELECT c.* FROM conversation conv LEFT JOIN `character` c ON conv.characterId = c.characterUUID "
                        + "WHERE conv.conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() > 0) {
                        Row row = rows.iterator().next();
                        if (row.getString("name") != null) {
                            String systemPrompt = buildCharacterPromptFromRow(row);
                            conversationSystemPrompts.put(conversationId, systemPrompt);
                            return Uni.createFrom().item(systemPrompt);
                        }
                    }
                    return mysqlPool.preparedQuery("""
                            SELECT c.* FROM conversation_character cc
                            JOIN `character` c ON cc.characterId = c.characterUUID
                            WHERE cc.conversationId = ? AND cc.isActive = TRUE
                              AND (cc.isIntroduced = TRUE OR cc.isIntroduced IS NULL)
                            ORDER BY cc.turnOrder ASC LIMIT 1
                            """)
                            .execute(Tuple.of(conversationId))
                            .map(charRows -> {
                                String systemPrompt = getDefaultSystemPrompt();
                                if (charRows.size() > 0) {
                                    Row charRow = charRows.iterator().next();
                                    if (charRow.getString("name") != null) {
                                        systemPrompt = buildCharacterPromptFromRow(charRow);
                                    }
                                }
                                conversationSystemPrompts.put(conversationId, systemPrompt);
                                return systemPrompt;
                            });
                });
    }

    private String buildCharacterPromptFromRow(Row row) {
        com.eonmux.chatllm.models.Character c = new com.eonmux.chatllm.models.Character();
        c.setName(row.getString("name"));
        c.setSystemPrompt(row.getString("systemPrompt"));
        c.setDescription(row.getString("description"));
        c.setPersonality(row.getString("personality"));
        c.setAppearance(row.getString("appearance"));
        c.setGender(row.getString("gender"));
        c.setPronouns(row.getString("pronouns"));
        c.setAgeYears(row.getInteger("ageYears"));
        c.setHeight(row.getString("height"));
        c.setBuild(row.getString("build"));
        c.setHair(row.getString("hair"));
        c.setEyes(row.getString("eyes"));
        c.setStrength(row.getInteger("strength"));
        c.setDexterity(row.getInteger("dexterity"));
        c.setConstitution(row.getInteger("constitution"));
        c.setIntelligence(row.getInteger("intelligence"));
        c.setWisdom(row.getInteger("wisdom"));
        c.setCharisma(row.getInteger("charisma"));
        c.setBackstory(row.getString("backstory"));
        c.setGoals(row.getString("goals"));
        c.setLikes(row.getString("likes"));
        c.setDislikes(row.getString("dislikes"));
        c.setExamples(row.getString("examples"));
        c.setGreeting(row.getString("greeting"));
        return c.getFullSystemPrompt();
    }

    private Uni<String> resolveUserDisplayName(String userId, String providedUserName) {
        if (providedUserName != null && !providedUserName.isEmpty() && !"User".equals(providedUserName)) {
            return Uni.createFrom().item(providedUserName);
        }

        if (userId == null || userId.isEmpty()) {
            return Uni.createFrom().item("User");
        }

        return mysqlPool.preparedQuery("SELECT displayName FROM user WHERE userUUID = ?")
                .execute(Tuple.of(userId))
                .map(rows -> {
                    if (rows.size() > 0) {
                        String displayName = rows.iterator().next().getString("displayName");
                        if (displayName != null && !displayName.isEmpty()) {
                            return displayName;
                        }
                    }
                    return "User";
                })
                .onFailure().recoverWithItem("User");
    }

    private String getDefaultSystemPrompt() {
        String custom = config.getLlmContextCustom();
        return (custom != null && !custom.isEmpty()) ? custom : "You are a helpful AI assistant.";
    }

    private Uni<Settings.LLMSettings> getUserLLMSettings(String userId) {
        if (userId == null || userId.isEmpty()) {
            return Uni.createFrom().nullItem();
        }

        return mysqlPool.preparedQuery("SELECT llmSettings FROM settings WHERE userId = ?")
                .execute(Tuple.of(userId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return (Settings.LLMSettings) null;
                    }
                    String llmSettingsJson = ServiceHelper.safeGetJsonText(rows.iterator().next(), "llmSettings");
                    if (llmSettingsJson == null || llmSettingsJson.isEmpty() || "null".equals(llmSettingsJson)) {
                        return null;
                    }
                    try {
                        return gson.fromJson(llmSettingsJson, Settings.LLMSettings.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse LLM settings for user {}: {}", userId, e.getMessage());
                        return null;
                    }
                })
                .onFailure().recoverWithItem(error -> {
                    log.warn("Error fetching LLM settings for user {}: {}", userId, error.getMessage());
                    return null;
                });
    }

    private JsonObject buildDefaultConfig() {
        return new JsonObject()
                .put("temperature", config.getLlmTemperature())
                .put("topP", config.getLlmTopP())
                .put("topK", config.getLlmTopK())
                .put("maxTokens", config.getLlmMaxTokens())
                .put("cachePrompt", config.isLlmCachePrompt());
    }

    private JsonArray getStopSequences() {
        if (cachedStopSequences != null && currentTemplate != null
                && currentTemplate.equals(cachedStopSequencesTemplate)) {
            return cachedStopSequences;
        }

        JsonArray stops = new JsonArray();
        if (isGptOssTemplate()) {
            stops.add("<|return|>");
            stops.add("<|start|>user");
            stops.add("<|start|>system");
        } else {
            stops.add("</s>");
            if (currentTemplate != null && currentTemplate.contains("MISTRAL")) {
                stops.add("[INST]");
            } else if ("CHATML".equals(currentTemplate)) {
                stops.add("<|im_end|>");
                stops.add("<|im_start|>");
            }
        }

        cachedStopSequencesTemplate = currentTemplate;
        cachedStopSequences = stops;
        return stops;
    }

    private boolean isGptOssTemplate() {
        if (cachedIsGptOss != null && currentTemplate != null
                && currentTemplate.equals(cachedIsGptOssTemplate)) {
            return cachedIsGptOss;
        }

        boolean result = false;
        if (currentTemplate != null) {
            String lower = currentTemplate.toLowerCase();
            result = lower.contains("gptoss") || lower.contains("gpt-oss") || lower.contains("gpt_oss");
        }

        cachedIsGptOssTemplate = currentTemplate;
        cachedIsGptOss = result;
        return result;
    }

    // =======================================================================
    // Character parsing
    // =======================================================================

    private String buildCharacterGenerationPrompt(String userPrompt) {
        return """
                You are a character designer for a roleplay chat system. Create a detailed fictional character based on: %s

                CRITICAL: Return ONLY a valid JSON object with NO markdown formatting, NO code blocks, NO explanations.

                Required JSON fields and their recommended lengths (all strings unless noted):
                {
                  "name": "Character's name (2-50 chars)",
                  "description": "Brief 1-2 sentence summary (50-200 chars)",
                  "personality": "Key personality traits and quirks (20-500 chars)",
                  "appearance": "Physical description — be vivid and detailed (20-500 chars)",
                  "gender": "Gender identity, such as Woman, Man, Non-binary, or a setting-appropriate term",
                  "pronouns": "Pronouns, such as she/her, he/him, or they/them",
                  "ageYears": "Age in whole years (integer)",
                  "height": "Height in the setting's units, such as 5 ft 8 in or 173 cm",
                  "build": "Build and bearing, such as Lean and wiry",
                  "hair": "Hair color, length, and style",
                  "eyes": "Eye color and any notable quality",
                  "strength": "Base Strength (integer 3-18)",
                  "dexterity": "Base Dexterity (integer 3-18)",
                  "constitution": "Base Constitution (integer 3-18)",
                  "intelligence": "Base Intelligence (integer 3-18)",
                  "wisdom": "Base Wisdom (integer 3-18)",
                  "charisma": "Base Charisma (integer 3-18)",
                  "backstory": "Character's history and context (50-1000 chars)",
                  "goals": "What the character aims to achieve (20-300 chars)",
                  "likes": "Things the character enjoys or values (20-300 chars)",
                  "dislikes": "Things the character dislikes or avoids (20-300 chars)",
                  "systemPrompt": "Instructions for how to roleplay as this character (100-1500 chars). Describe their speech patterns, mannerisms, knowledge, and behavioral quirks.",
                  "greeting": "First message the character says when meeting someone new (10-200 chars)",
                  "alignment": "One of: Lawful Good, Neutral Good, Chaotic Good, Lawful Neutral, True Neutral, Chaotic Neutral, Lawful Evil, Neutral Evil, Chaotic Evil"
                }

                Guidelines:
                - systemPrompt should describe how the character speaks and behaves (e.g., "Speak in short, clipped sentences. Never break character. You are a grizzled detective in 1940s New York...")
                - alignment should match the character's values and methods, not merely their reputation
                - gender, pronouns, age, height, build, hair, and eyes must agree with each other and with the appearance
                - base attributes use a 3-18 scale where 10 is average; make them fit the character's body, training, and work, not an ideal hero
                - All text should be accurate to the character if the character is already known, otherwise be imaginative
                - greeting should be a natural in-character opening line
                - Return ONLY the JSON object, nothing else
                """.formatted(userPrompt);
    }

    private JsonObject parseGeneratedCharacter(String content) {
        JsonObject result = tryStrategies(content);
        return normalizeAndEnrichCharacter(result != null ? result : salvageExtraction(content), content);
    }

    private JsonObject tryStrategies(String content) {
        JsonObject res = tryParse(content);
        if (res != null) return res;

        Matcher m = CODE_BLOCK_JSON.matcher(content);
        if (m.find()) {
            res = tryParse(m.group(1));
            if (res != null) return res;
        }

        m = CODE_BLOCK_GENERIC.matcher(content);
        if (m.find()) {
            res = tryParse(m.group(1));
            if (res != null) return res;
        }

        return extractFieldsWithPatterns(content);
    }

    private JsonObject tryParse(String json) {
        if (json == null || json.isEmpty()) return null;
        String trimmed = json.trim();

        // Try 1: parse as-is (valid JSON with apostrophes in values works here)
        try {
            return new JsonObject(trimmed);
        } catch (Exception e) {
            // fall through
        }

        // Try 2: repair unquoted keys, then parse
        String keyRepaired = JSON_KEY_REPAIR_PATTERN.matcher(trimmed).replaceAll("$1\"$2\":");
        try {
            return new JsonObject(keyRepaired);
        } catch (Exception e) {
            // fall through
        }

        // Try 3: last resort — LLM used single quotes as JSON delimiters
        // Do NOT blindly replace all ' with " (corrupts apostrophes in values).
        // Instead, replace only the pattern of single-quoted keys and values.
        if (keyRepaired.indexOf('\'') >= 0) {
            try {
                String sqRepaired = keyRepaired.replace('\'', '"');
                return new JsonObject(sqRepaired);
            } catch (Exception e) {
                // give up
            }
        }

        return null;
    }

    private JsonObject extractFieldsWithPatterns(String content) {
        JsonObject result = new JsonObject();

        String[] fields = {"name", "description", "personality", "appearance", "backstory",
                "goals", "likes", "dislikes", "systemPrompt", "greeting", "alignment"};

        for (String field : fields) {
            // Try double-quoted values first (allows apostrophes inside)
            Pattern dqPattern = Pattern.compile(
                    "(?i)[\"']?" + field + "[\"']?\\s*:\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = dqPattern.matcher(content);
            if (m.find()) {
                result.put(field, m.group(1).trim());
                continue;
            }
            // Fallback: single-quoted values
            Pattern sqPattern = Pattern.compile(
                    "(?i)[\"']?" + field + "[\"']?\\s*:\\s*'([^']+)'",
                    Pattern.CASE_INSENSITIVE);
            m = sqPattern.matcher(content);
            if (m.find()) {
                result.put(field, m.group(1).trim());
            }
        }

        return result.isEmpty() ? null : result;
    }

    private JsonObject salvageExtraction(String content) {
        JsonObject result = new JsonObject();

        Matcher m = EXTRACT_NAME_QUOTED.matcher(content);
        if (m.find()) {
            result.put("name", m.group(1));
        } else {
            m = EXTRACT_NAME_CAPS.matcher(content);
            if (m.find()) {
                result.put("name", m.group(1));
            } else {
                result.put("name", "Generated Character");
            }
        }

        String[] otherFields = {"description", "personality", "appearance", "backstory",
                "goals", "likes", "dislikes", "systemPrompt", "greeting", "alignment"};

        for (String field : otherFields) {
            Pattern pattern = Pattern.compile(
                    "(?i)" + field + "\\s*[:=]\\s*(.{10,500}?)(?:\\n|$)",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    value = value.substring(1);
                }
                if (value.endsWith("\"") || value.endsWith("'")) {
                    value = value.substring(0, value.length() - 1);
                }
                result.put(field, value);
            }
        }

        if (!result.containsKey("description")) {
            result.put("description", "A generated character");
        }
        if (!result.containsKey("greeting")) {
            result.put("greeting", "Hello, I am " + result.getString("name"));
        }

        return result;
    }

    private JsonObject normalizeAndEnrichCharacter(JsonObject obj, String originalContent) {
        if (obj.getString("name") == null || obj.getString("name").trim().isEmpty()) {
            obj.put("name", "Generated Character");
        }

        if (obj.getString("description") == null || obj.getString("description").trim().isEmpty()) {
            obj.put("description", "A mysterious character from another world");
        }

        if (obj.getString("systemPrompt") == null || obj.getString("systemPrompt").trim().isEmpty()) {
            obj.put("systemPrompt", "You are " + obj.getString("name") + ". Stay in character at all times. Respond as " + obj.getString("name") + " would, drawing on your personality, history, and goals.");
        }

        if (obj.getString("greeting") == null || obj.getString("greeting").trim().isEmpty()) {
            obj.put("greeting", obj.getString("name") + " greets you with a curious look.");
        }

        String alignment = com.eonmux.chatllm.utils.AlignmentRules.named(obj.getString("alignment"));
        obj.put("alignment", alignment == null ? "TRUE_NEUTRAL" : alignment);

        if (obj.getString("personality") == null) {
            obj.put("personality", "Mysterious, complex, and deeply layered");
        }

        if (obj.getString("appearance") == null) {
            obj.put("appearance", "A striking figure with an unforgettable presence");
        }

        normalizeProfileFields(obj);

        if (obj.getString("backstory") == null) {
            obj.put("backstory", "Little is known about " + obj.getString("name") + ". They appeared one day and have been a fixture ever since.");
        }

        if (obj.getString("goals") == null) {
            obj.put("goals", "To find their purpose and leave a mark on the world");
        }

        if (obj.getString("likes") == null) {
            obj.put("likes", "Meaningful conversations, quiet moments, discovering new things");
        }

        if (obj.getString("dislikes") == null) {
            obj.put("dislikes", "Fakery, cruelty, being misunderstood");
        }

        String name = obj.getString("name");
        if (name != null && name.length() > 100) {
            obj.put("name", name.substring(0, 100));
        }

        String description = obj.getString("description");
        if (description != null && description.length() > 1000) {
            obj.put("description", description.substring(0, 1000));
        }

        String greeting = obj.getString("greeting");
        if (greeting != null && greeting.length() > 500) {
            obj.put("greeting", greeting.substring(0, 500));
        }

        String systemPrompt = obj.getString("systemPrompt");
        if (systemPrompt != null && systemPrompt.length() > 4000) {
            obj.put("systemPrompt", systemPrompt.substring(0, 4000));
        }

        truncateField(obj, "personality", 2000);
        truncateField(obj, "appearance", 2000);
        truncateField(obj, "backstory", 4000);
        truncateField(obj, "goals", 1000);
        truncateField(obj, "likes", 1000);
        truncateField(obj, "dislikes", 1000);

        return obj;
    }

    private void truncateField(JsonObject obj, String fieldName, int maxLength) {
        String value = obj.getString(fieldName);
        if (value != null && value.length() > maxLength) {
            obj.put(fieldName, value.substring(0, maxLength));
        }
    }

    /**
     * Keeps a generated physical profile and its base attributes inside the
     * bounds the card and the form understand.
     */
    private static void normalizeProfileFields(JsonObject obj) {
        for (String field : List.of("gender", "height", "build", "hair", "eyes")) {
            String value = obj.getString(field);
            if (value == null || value.isBlank()) {
                obj.remove(field);
            } else {
                obj.put(field, value.strip().length() > 220
                        ? value.strip().substring(0, 220) : value.strip());
            }
        }
        String pronouns = obj.getString("pronouns");
        if (pronouns == null || pronouns.isBlank()) obj.remove("pronouns");
        else obj.put("pronouns", pronouns.strip().length() > 40
                ? pronouns.strip().substring(0, 40) : pronouns.strip());

        Object age = obj.getValue("ageYears");
        if (age instanceof String text && text.strip().matches("\\d+")) age = Integer.parseInt(text.strip());
        if (age instanceof Number number && number.intValue() >= 0 && number.intValue() <= 1_000_000) {
            obj.put("ageYears", number.intValue());
        } else {
            obj.remove("ageYears");
        }

        for (String field : List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma")) {
            Object score = obj.getValue(field);
            if (score instanceof String text && text.strip().matches("\\d+")) score = Integer.parseInt(text.strip());
            int value = score instanceof Number number
                    ? Math.max(1, Math.min(20, number.intValue())) : 10;
            obj.put(field, value);
        }
    }

    // =======================================================================
    // Status / reload / statistics / stop handlers
    // =======================================================================

    private void handleGetStatus(Message<JsonObject> message) {
        if (config.isLlmExternalServer()) {
            JsonObject status = new JsonObject()
                    .put("providerType", "external")
                    .put("externalBaseUrl", config.getLlmExternalBaseUrl())
                    .put("externalModel", config.getLlmExternalModel())
                    .put("activeServers", 1)
                    .put("modelPath", config.getLlmExternalModel());

            // Guard against double-reply: the async WebClient callbacks can
            // race with the sync catch/else fallbacks. First-wins via CAS.
            final java.util.concurrent.atomic.AtomicBoolean replied =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.function.Consumer<JsonObject> replyOnce = body -> {
                if (replied.compareAndSet(false, true)) {
                    message.reply(body);
                }
            };

            String baseUrl = config.getLlmExternalBaseUrl();
            if (baseUrl != null && !baseUrl.isEmpty()) {
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

                try {
                    java.net.URI uri = java.net.URI.create(baseUrl);
                    boolean useSsl = "https".equals(uri.getScheme());
                    String host = uri.getHost();
                    int port = uri.getPort();
                    if (port == -1) port = useSsl ? 443 : 80;

                    WebClient client = WebClient.create(vertx, new WebClientOptions()
                            .setDefaultHost(host)
                            .setDefaultPort(port)
                            .setSsl(useSsl)
                            .setTrustAll(true)
                            .setConnectTimeout(5_000)
                            .setIdleTimeout(10));

                    String path = (uri.getPath() != null ? uri.getPath() : "") + "/v1/models";

                    var req = client.get(path);
                    String apiKey = config.getLlmExternalApiKey();
                    if (apiKey != null && !apiKey.isEmpty()) {
                        req.putHeader("Authorization", "Bearer " + apiKey);
                    }

                    req.send()
                            .subscribe().with(
                                    res -> {
                                        client.close();
                                        status.put("defaultServerHealth", new JsonObject()
                                                .put("status", res.statusCode() == 200 ? "ok" : "error")
                                                .put("httpStatus", res.statusCode()));
                                        replyOnce.accept(status);
                                    },
                                    e -> {
                                        client.close();
                                        status.put("defaultServerHealth", new JsonObject()
                                                .put("status", "unreachable")
                                                .put("error", e.getMessage()));
                                        replyOnce.accept(status);
                                    });
                } catch (Exception e) {
                    status.put("defaultServerHealth", new JsonObject()
                            .put("status", "config_error")
                            .put("error", "Invalid base URL: " + e.getMessage()));
                    replyOnce.accept(status);
                }
            } else {
                status.put("defaultServerHealth", new JsonObject()
                        .put("status", "not_configured")
                        .put("message", "LLM_EXTERNAL_BASE_URL not set"));
                replyOnce.accept(status);
            }
            return;
        }

        if (serverPool == null) {
            message.reply(new JsonObject()
                    .put("providerType", "local")
                    .put("activeServers", 0)
                    .put("defaultServerHealth", new JsonObject()
                            .put("status", "not_started")
                            .put("message", "Model server pool is not initialized")));
            return;
        }

        Map<String, Object> poolStats = serverPool.getStatistics();
        JsonObject status = new JsonObject(poolStats);
        status.put("providerType", "local");

        ModelServerInstance existingServer = serverPool.getExistingDefaultServer();

        if (existingServer == null) {
            status.put("defaultServerHealth", new JsonObject()
                    .put("status", "not_started")
                    .put("message", "No default server running"));
            message.reply(status);
            return;
        }

        if (existingServer.isStarting()) {
            status.put("defaultServerHealth", new JsonObject()
                    .put("status", "starting")
                    .put("message", "Server is starting up"));
            message.reply(status);
            return;
        }

        WebClient client = createWebClientForServer(existingServer);
        client.get("/health").send()
                .subscribe().with(
                        res -> {
                            client.close();
                            message.reply(status.put("defaultServerHealth", res.bodyAsJsonObject()));
                        },
                        e -> {
                            client.close();
                            message.reply(status.put("defaultServerHealth", new JsonObject()
                                    .put("status", "unhealthy")
                                    .put("error", e.getMessage())));
                        });
    }

    private void handleReloadModel(Message<JsonObject> message) {
        if (serverPool == null) {
            message.fail(503, "Local model server pool not initialized");
            return;
        }
        serverPool.stopAll()
                .chain(v -> serverPool.getDefaultServer("reload"))
                .chain(this::detectTemplateForServer)
                .subscribe().with(
                        v -> message.reply(new JsonObject().put("success", true)
                                .put("message", "All servers reloaded")),
                        e -> message.fail(500, e.getMessage()));
    }

    private void handleGetStatistics(Message<JsonObject> message) {
        long total = totalRequests.get();
        message.reply(new JsonObject()
                .put("totalRequests", total)
                .put("successfulRequests", successfulRequests.get())
                .put("totalTokensGenerated", totalTokensGenerated.get()));
    }

    private void handleStopGeneration(Message<JsonObject> message) {
        String conversationId = message.body().getString("conversationId");
        scheduledGroupTurns.remove(conversationId);
        boolean groupTurn = activeGroupTurns.remove(conversationId) != null;
        handleCancellation(conversationId, "Generation stopped by user");
        if (groupTurn) cancelledRequestIds.remove(conversationId);
        resetGroupChatStateOnStop(conversationId).subscribe().with(
                ignored -> message.reply(new JsonObject().put("success", true)),
                error -> message.fail(500, "Failed to stop conversation: " + error.getMessage()));
    }

    private Uni<Void> resetGroupChatStateOnStop(String conversationId) {
        return mysqlPool.preparedQuery(
                "UPDATE conversation SET groupState = 'WAITING_FOR_USER', currentSpeakerId = NULL "
                        + "WHERE conversationGUID = ? AND (isGroupChat = TRUE OR isRoleplay = TRUE) AND groupState != 'PAUSED'")
                .execute(Tuple.of(conversationId))
                .invoke(result -> {
                    if (result.rowCount() > 0) vertx.eventBus().publish("group:" + conversationId + ":state-changed",
                            new JsonObject().put("conversationId", conversationId).put("groupState", "WAITING_FOR_USER"));
                }).replaceWithVoid();
    }

    // =======================================================================
    // Save assistant message with validation
    // =======================================================================

    // Wraps the DB save with retry for transient connection errors (network blips,
    // MySQL temporarily unreachable). Validation errors ("context no longer valid")
    // and SQL constraint errors are not retried — they will not succeed on retry.
    // After max attempts, the failure propagates to handleGenerateMessage's error
    // handler, which publishes a final streaming-error frame so the user sees the
    // failure rather than a stuck generation.
    private Uni<JsonObject> saveAssistantMessageWithId(String conversationId, String messageId, String content,
                                                       JsonObject generationStats, String displayName) {
        // If displayName is the fallback "Assistant", look up the actual character name
        // from the conversation's associated character (for non-group chats where the
        // character name isn't passed through the generation pipeline).
        Uni<String> resolvedName;
        if ("Assistant".equals(displayName)) {
            resolvedName = fetchCharacterInfo(conversationId)
                    .map(info -> {
                        String name = info.getString("name");
                        return name != null ? name : displayName;
                    });
        } else {
            resolvedName = Uni.createFrom().item(displayName);
        }
        return resolvedName
                .chain(name -> doSaveAssistantMessageWithId(conversationId, messageId, content, generationStats, name)
                        .onFailure(this::isConnectionError)
                        .invoke(t -> log.warn("Transient DB error saving assistant message {} (will retry): {}",
                                messageId, t.getMessage()))
                        .onFailure(this::isConnectionError)
                        .retry().withBackOff(Duration.ofMillis(200), Duration.ofSeconds(2)).atMost(3));
    }

    private Uni<JsonObject> doSaveAssistantMessageWithId(String conversationId, String messageId, String content,
                                                        JsonObject generationStats, String displayName) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        String validateQuery = """
                SELECT COUNT(*) as cnt FROM conversation c
                WHERE c.conversationGUID = ?
                AND c.active = TRUE
                AND EXISTS (
                    SELECT 1 FROM message m
                    WHERE m.conversationId = c.conversationGUID
                    AND m.role = 'user'
                    AND m.isDeleted = FALSE
                )
                """;

        return mysqlPool.preparedQuery(validateQuery)
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    long count = rows.iterator().next().getLong("cnt");
                    if (count == 0) {
                        log.warn("Discarding LLM response for conversation {} - context no longer valid "
                                        + "(may have been deleted or cleared)", conversationId);
                        return Uni.createFrom().<JsonObject>failure(new RuntimeException(
                                "Cannot save assistant response: conversation context no longer valid. "
                                        + "The user message may have been deleted or edited during generation."));
                    }

                    return mysqlPool.withTransaction(client -> {
                        int promptTokens = generationStats != null
                                ? generationStats.getInteger("promptTokens", 0) : 0;
                        int completionTokens = generationStats != null
                                ? generationStats.getInteger("completionTokens", 0) : 0;
                        int totalTimeMs = generationStats != null
                                ? generationStats.getInteger("totalTimeMs", 0) : 0;
                        double tps = generationStats != null
                                ? generationStats.getDouble("tokensPerSecond", 0.0) : 0.0;
                        int promptEvalMs = generationStats != null
                                ? generationStats.getInteger("promptEvalMs", 0) : 0;
                        int generationMs = generationStats != null
                                ? generationStats.getInteger("generationMs", 0) : 0;

                        String insertQuery = """
                                INSERT INTO message (messageUUID, conversationId, content, role, displayName,
                                                   promptTokens, completionTokens, promptEvalMs, generationMs,
                                                   totalTimeMs, tokensPerSecond,
                                                   createdAt, updatedAt)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """;

                        return client.preparedQuery(insertQuery)
                                .execute(Tuple.tuple()
                                        .addValue(messageId).addValue(conversationId).addValue(content)
                                        .addValue("assistant").addValue(displayName)
                                        .addValue(promptTokens).addValue(completionTokens)
                                        .addValue(promptEvalMs).addValue(generationMs)
                                        .addValue(totalTimeMs).addValue(tps)
                                        .addValue(now).addValue(now))
                                .chain(v -> {
                                    String updateQuery = """
                                            UPDATE conversation SET lastModified = ?, updatedAt = ?,
                                                                    messageCount = IFNULL(messageCount, 0) + 1
                                            WHERE conversationGUID = ?
                                            """;
                                    return client.preparedQuery(updateQuery)
                                            .execute(Tuple.of(now, now, conversationId));
                                })
                                .map(v -> new JsonObject()
                                        .put("id", messageId)
                                        .put("content", content)
                                        .put("displayName", displayName));
                    });
                });
    }

    /**
     * Removes model reasoning from stored and replayed messages. The reasoning
     * stream still reaches the live view, but it must not become story history.
     */
    static String stripReasoning(String content) {
        if (content == null || content.isEmpty()) return "";
        String cleaned = content.replaceAll("(?is)<think(?:ing)?>.*?</think(?:ing)?>", " ");
        cleaned = cleaned.replaceAll("(?is)<think(?:ing)?>.*$", " ");
        return cleaned.strip();
    }

    /**
     * Some models return the two characters backslash-n instead of a line
     * break. Stored as-is, the client prints them literally. This is prose, so
     * a literal escape is always a line break.
     */
    static String unescapeLineBreaks(String content) {
        if (content == null || content.indexOf('\\') < 0) return content;
        return content.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t");
    }

    /**
     * Enforces the roleplay markup contract on generated character text.
     *
     * The client renders every {@code <em>} as a muted action beat, so a model
     * that writes "a *living* thing" makes one stressed word look like a
     * gesture. The prompt forbids that form, but the model still reaches for
     * it. A short span with no first-person subject is emphasis; unwrap it.
     * A real action block names the body that acts and stays italic.
     *
     * Segments alternate outside and inside the asterisks. Working segment by
     * segment stops a long action block from pairing its closing mark with the
     * next opening one and swallowing the speech between them.
     */
    static String normalizeRoleplayMarkup(String content) {
        if (content == null || content.isEmpty()) return content;
        content = unescapeLineBreaks(content);
        String[] parts = content.split("\\*", -1);
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < parts.length; i++) {
            boolean inside = i % 2 == 1;
            if (!inside) {
                out.append(parts[i]);
            } else if (isActionSpan(parts[i])) {
                out.append('*').append(parts[i]).append('*');
            } else {
                out.append(parts[i]);
            }
        }
        return out.toString();
    }

    private static boolean isActionSpan(String inner) {
        String text = inner.strip();
        if (text.isEmpty() || text.contains("\"")) return true;
        if (text.matches("(?is).*\\b(i|i'm|i've|i'd|i'll|my|me|myself)\\b.*")) {
            return true;
        }
        return text.split("\\s+").length > 4;
    }

    private String stripCharacterPrefix(String content, String characterName) {
        if (content == null || content.isEmpty()) return "";

        String trimmed = content.trim();
        if (trimmed.isEmpty()) return "";

        if (characterName != null && !characterName.isEmpty()) {
            String bracketPrefix = "[" + characterName + "]:";
            String colonPrefix = characterName + ":";

            if (trimmed.startsWith(bracketPrefix)) {
                return trimmed.substring(bracketPrefix.length()).trim();
            }
            if (trimmed.startsWith(colonPrefix)) {
                return trimmed.substring(colonPrefix.length()).trim();
            }
        }

        if (trimmed.charAt(0) == '[') {
            int idx = trimmed.indexOf("]:");
            if (idx != -1) {
                return trimmed.substring(idx + 2).trim();
            }
        }

        return trimmed;
    }

    // =======================================================================
    // GPT-OSS content processor
    // =======================================================================

    static class GptOssContentProcessor {
        private final StringBuilder processedOutput = new StringBuilder();
        private boolean inAnalysisChannel = false;
        private boolean analysisStartFound = false;
        private boolean analysisEndFound = false;

        private static final Pattern GPT_OSS_TOKEN_PATTERN =
                Pattern.compile("<\\|(channel|message|end|start)\\|>");

        private static final String ANALYSIS_START = "analysis<|message|>";
        private static final String ANALYSIS_END = "<|channel|>final";

        public String processChunk(String chunk) {
            StringBuilder result = new StringBuilder();

            if (!analysisStartFound && chunk.contains(ANALYSIS_START)) {
                analysisStartFound = true;
                inAnalysisChannel = true;
                result.append("<think>");
            }
            if (inAnalysisChannel && !analysisEndFound && chunk.contains(ANALYSIS_END)) {
                analysisEndFound = true;
                inAnalysisChannel = false;
                result.append("</think>");
            }

            String cleaned = GPT_OSS_TOKEN_PATTERN.matcher(chunk).replaceAll("");

            processedOutput.append(cleaned);
            result.append(cleaned);

            return result.toString();
        }

        public String getFullProcessedOutput() {
            return processedOutput.toString();
        }

        public String completeProcessing() {
            return "";
        }
    }
}
