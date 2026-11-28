package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.comfyui.ComfyUIQueueManager;
import com.eonmux.chatllm.comfyui.ComfyUIWorkflowBuilder;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.models.ComfyUIStatus;
import com.eonmux.chatllm.models.ComfyUIWorkflow;
import com.eonmux.chatllm.models.GeneratedImage;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;

/**
 * GraphQL resolver for ComfyUI/image generation operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class ImageResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    @Inject
    ComfyUIQueueManager queueManager;

    @Inject
    com.eonmux.chatllm.comfyui.ComfyUIClient comfyuiClient;

    @Inject
    ComfyUIWorkflowBuilder workflowBuilder;

    @Inject
    com.eonmux.chatllm.services.ImageGenerationService imageGenerationService;

    @Inject
    com.eonmux.chatllm.services.SheetRefService sheetRefService;

    @Inject
    AppConfig config;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    // Held so @PreDestroy can unregister it. Quarkus dev-mode live reload
    // re-creates this bean while the Vert.x event bus persists, so without
    // deregistration every reload stacks another consumer on
    // comfyui.job.complete and one completion event N-fires the whole
    // completion path (row update, sheet-crop derivation).
    private io.vertx.core.eventbus.MessageConsumer<JsonObject> completionConsumer;
    private io.vertx.core.eventbus.MessageConsumer<JsonObject> failureConsumer;

    /**
     * Verify the caller owns or actively participates in the conversation
     * before reading/creating/cancelling image-generation rows.
     */
    private io.smallrye.mutiny.Uni<Boolean> verifyConversationAccess(String conversationId, String userId) {
        return serviceHelper.verifyConversationAccess(conversationId, userId);
    }

    @PostConstruct
    public void init() {
        // A queuePrompt rejection (peer 400, missing node, client down) is
        // handled inside the queue manager and never reaches the submit Uni,
        // so without this consumer the row the resolver inserted `pending`
        // would stay `pending` forever.
        failureConsumer = vertx.eventBus().<JsonObject>consumer("comfyui.job.failed", message -> {
            JsonObject body = message.body();
            String imageId = body.getString("jobId");
            String reason = body.getString("reason");
            if (imageId == null) return;
            String updateQuery = "UPDATE generated_image SET metadata = JSON_SET(COALESCE(metadata, '{}'), '$.status', 'failed', '$.failReason', ?) WHERE imageId = ?";
            mysqlPool.preparedQuery(updateQuery)
                .execute(Tuple.of(reason != null ? reason : "queue failure", imageId))
                .subscribe().with(
                    ignored -> log.info("Marked image {} as failed after queue rejection", imageId),
                    err -> log.error("Failed to mark image {} as failed: {}", imageId, err.getMessage()));
        });
        completionConsumer = vertx.eventBus().<JsonObject>consumer("comfyui.job.complete", message -> {
            JsonObject body = message.body();
            String imageId = body.getString("jobId");
            String conversationId = body.getString("conversationId");
            String resultUrl = body.getString("resultUrl");
            Long usedSeed = body.getLong("seed");

            String updateQuery = "UPDATE generated_image SET filePath = ?, seed = COALESCE(?, seed), metadata = JSON_SET(COALESCE(metadata, '{}'), '$.status', 'complete') WHERE imageId = ?";
            mysqlPool.preparedQuery(updateQuery)
                .execute(Tuple.of(resultUrl, usedSeed, imageId))
                .subscribe().with(
                    result -> {
                        String selectQuery = "SELECT imageId, conversationId, characterId, prompt, negativePrompt, width, height, seed, filePath, thumbnailPath, sceneContext, createdAt FROM generated_image WHERE imageId = ?";
                        mysqlPool.preparedQuery(selectQuery)
                            .execute(Tuple.of(imageId))
                            .subscribe().with(
                                rows -> {
                                    if (rows.size() > 0) {
                                        var row = rows.iterator().next();
                                        JsonObject event = new JsonObject()
                                            .put("imageId", row.getString("imageId"))
                                            .put("conversationId", row.getString("conversationId"))
                                            .put("characterId", row.getString("characterId"))
                                            .put("prompt", row.getString("prompt"))
                                            .put("negativePrompt", row.getString("negativePrompt"))
                                            .put("filePath", row.getString("filePath"))
                                            .put("thumbnailPath", row.getString("thumbnailPath"))
                                            .put("width", row.getInteger("width"))
                                            .put("height", row.getInteger("height"))
                                            .put("seed", row.getLong("seed"))
                                            .put("sceneContext", row.getString("sceneContext"))
                                            .put("createdAt", row.getLocalDateTime("createdAt") != null ? Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")) : null);
                                        vertx.eventBus().publish(RoleplayEventAddresses.IMAGE_GENERATED + conversationId, event);

                                        // A finished 1024x1024 character sheet is the input of the
                                        // D2 asset pipeline: derive the eight reference crops and
                                        // replace the character's active image references.
                                        Integer w = row.getInteger("width");
                                        Integer h = row.getInteger("height");
                                        String refCharacterId = row.getString("characterId");
                                        if (w != null && h != null && w == 1024 && h == 1024
                                            && refCharacterId != null && !refCharacterId.isEmpty()) {
                                            // The finished sheet is the character's face: point the
                                            // avatar at it so every avatar surface (message list,
                                            // participant list, character card) picks it up for free.
                                            String avatarQuery = "UPDATE `character` SET avatar = ? WHERE characterUUID = ?";
                                            mysqlPool.preparedQuery(avatarQuery)
                                                .execute(Tuple.of(resultUrl, refCharacterId))
                                                .subscribe().with(
                                                    ignored -> log.info("Set character {} avatar from image {}", refCharacterId, imageId),
                                                    err -> log.warn("Failed to set avatar for character {}: {}", refCharacterId, err.getMessage()));
                                            sheetRefService.deriveReferences(refCharacterId, resultUrl)
                                                .subscribe().with(
                                                    paths -> log.info("Derived {} sheet reference crops for character {} from image {}",
                                                        paths.size(), refCharacterId, imageId),
                                                    err -> log.error("Failed to derive sheet references for character {} from image {}: {}",
                                                        refCharacterId, imageId, err.getMessage()));
                                        }
                                    }
                                },
                                err -> log.warn("Failed to fetch generated image {} after completion: {}", imageId, err.getMessage()));
                    },
                    err -> log.warn("Failed to update generated image {} on completion: {}", imageId, err.getMessage())
                );
        });
    }

    @PreDestroy
    public void destroy() {
        if (completionConsumer != null) {
            completionConsumer.unregister();
        }
        if (failureConsumer != null) {
            failureConsumer.unregister();
        }
    }

    /**
     * Get generated images for a conversation, optionally narrowed to one
     * character (e.g. portrait lookups from a character card).
     * Schema: generatedImages(conversationId: ID!, characterId: ID, limit: Int): [GeneratedImage!]! @auth
     */
    @Query
    public CompletableFuture<List<GeneratedImage>> generatedImages(@Name("conversationId") String conversationId,
                                                                    @Name("characterId") String characterId,
                                                                    @Name("limit") Integer limit) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        final int effectiveLimit = limit != null ? limit : 50;

        CompletableFuture<List<GeneratedImage>> future = new CompletableFuture<>();

        verifyConversationAccess(conversationId, userId)
            .subscribe().with(hasAccess -> {
                if (!hasAccess) {
                    future.completeExceptionally(new RuntimeException("Conversation not found or access denied"));
                    return;
                }
                doGeneratedImages(conversationId, characterId, effectiveLimit, future);
            }, err -> {
                log.error("Failed to verify access for generatedImages conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void doGeneratedImages(String conversationId, String characterId, Integer limit, CompletableFuture<List<GeneratedImage>> future) {
        String query = characterId != null && !characterId.isBlank()
            ? "SELECT * FROM generated_image WHERE conversationId = ? AND characterId = ? ORDER BY createdAt DESC LIMIT ?"
            : "SELECT * FROM generated_image WHERE conversationId = ? ORDER BY createdAt DESC LIMIT ?";
        mysqlPool.preparedQuery(query)
            .execute(characterId != null && !characterId.isBlank()
                ? Tuple.of(conversationId, characterId, limit)
                : Tuple.of(conversationId, limit))
            .invoke(rows -> {
                List<GeneratedImage> images = new ArrayList<>();
                rows.forEach(row -> {
                    String imageIdStr = row.getString("imageId");
                    String convIdStr = row.getString("conversationId");
                    String messageIdStr = row.getString("messageId");
                    String characterIdStr = row.getString("characterId");

                    // Extract metadata JSON if present
                    JsonObject metadata = null;
                    try {
                        String metadataStr = ServiceHelper.safeGetJsonText(row, "metadata");
                        if (metadataStr != null && !metadataStr.isEmpty()) {
                            metadata = new JsonObject(metadataStr);
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse metadata for image {}", imageIdStr);
                    }

                    GeneratedImage image = GeneratedImage.builder()
                        .imageId(imageIdStr != null ? UUID.fromString(imageIdStr) : null)
                        .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
                        .messageId(messageIdStr != null ? UUID.fromString(messageIdStr) : null)
                        .characterId(characterIdStr != null ? UUID.fromString(characterIdStr) : null)
                        .prompt(row.getString("prompt"))
                        .negativePrompt(row.getString("negativePrompt"))
                        .workflowId(row.getString("workflowId"))
                        .width(row.getInteger("width"))
                        .height(row.getInteger("height"))
                        .seed(row.getLong("seed"))
                        .filePath(row.getString("filePath"))
                        .thumbnailPath(row.getString("thumbnailPath"))
                        .metadata(metadata)
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .build();

                    images.add(image);
                });
                future.complete(images);
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch generated images for conversation {}", conversationId, err);
                future.completeExceptionally(err);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);
    }

    /**
     * Get ComfyUI connection status by querying the actual ComfyUI server.
     * Schema: comfyuiStatus: ComfyUIStatus! @auth
     */
    @Query
    public CompletableFuture<ComfyUIStatus> comfyuiStatus() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<ComfyUIStatus> future = new CompletableFuture<>();

        // Use the CDI-injected config. The previous implementation read
        // vertx.sharedData().getLocalMap("config").get("appConfig"), but no code
        // ever populates that map, so the lookup was always null and this
        // resolver always answered connected=false without probing.
        if (config == null || !config.isComfyuiEnabled()) {
            future.complete(ComfyUIStatus.builder()
                .connected(false)
                .queueSize(0)
                .availableModels(List.of())
                .availableLoras(List.of())
                .build());
            return future;
        }

        String host = config.getComfyuiHost();
        int port = config.getComfyuiPort();

        // One client per call, closed only when the whole probe chain
        // (queue + both /object_info lookups) finishes, so a status poll
        // never leaks connections.
        var client = vertx.createHttpClient();
        client
            .request(io.vertx.core.http.HttpMethod.GET, port, host, "/queue")
            .compose(req -> req.send())
            .compose(resp -> resp.body())
            .compose(body -> {
                int queueSize;
                try {
                    JsonObject json = new JsonObject(body.toString());
                    io.vertx.core.json.JsonArray running = json.getJsonArray("queue_running", new io.vertx.core.json.JsonArray());
                    io.vertx.core.json.JsonArray pending = json.getJsonArray("queue_pending", new io.vertx.core.json.JsonArray());
                    queueSize = running.size() + pending.size();
                } catch (Exception e) {
                    queueSize = 0;
                }
                final int qSize = queueSize;
                var models = probeModelNames(client, host, port, "CheckpointLoaderSimple", "ckpt_name");
                var loras = probeModelNames(client, host, port, "LoraLoader", "lora_name");
                return models.compose(ms -> loras.map(ls -> ComfyUIStatus.builder()
                    .connected(true)
                    .queueSize(qSize)
                    .availableModels(ms)
                    .availableLoras(ls)
                    .build()));
            })
            .onSuccess(future::complete)
            .onFailure(err -> {
                log.warn("comfyuiStatus: probe to {}:{} failed: {}", host, port, err.getMessage());
                future.complete(ComfyUIStatus.builder()
                    .connected(false)
                    .queueSize(0)
                    .availableModels(List.of())
                    .availableLoras(List.of())
                    .build());
            })
            .eventually(() -> client.close());

        return future;
    }

    /**
     * Fetch the selectable names of a node class' combo input from
     * /object_info/{nodeClass}; the first entry of the field spec holds the
     * name list. Any failure degrades to an empty list so a slow
     * /object_info can never break the status probe.
     */
    private io.vertx.core.Future<List<String>> probeModelNames(
            io.vertx.core.http.HttpClient client, String host, int port, String nodeClass, String fieldName) {
        return client
            .request(io.vertx.core.http.HttpMethod.GET, port, host, "/object_info/" + nodeClass)
            .compose(req -> req.send())
            .compose(resp -> resp.body())
            .map(body -> {
                JsonObject json = new JsonObject(body.toString());
                JsonObject node = json.getJsonObject(nodeClass);
                if (node == null) {
                    return List.<String>of();
                }
                JsonObject input = node.getJsonObject("input");
                JsonObject required = input == null ? null : input.getJsonObject("required");
                io.vertx.core.json.JsonArray field = required == null ? null : required.getJsonArray(fieldName);
                if (field == null || field.isEmpty()) {
                    return List.<String>of();
                }
                io.vertx.core.json.JsonArray names = field.getJsonArray(0);
                java.util.List<String> out = new java.util.ArrayList<>(names.size());
                for (int i = 0; i < names.size(); i++) {
                    out.add(names.getString(i));
                }
                return out;
            })
            .recover(err -> {
                log.warn("comfyuiStatus: /object_info/{} failed: {}", nodeClass, err.getMessage());
                // This Vert.x build's recover() expects the mapper to yield a
                // Future, so wrap the degraded empty list.
                return io.vertx.core.Future.succeededFuture(java.util.List.<String>of());
            });
    }

    /**
     * Get active ComfyUI workflows.
     * Schema: comfyuiWorkflows: [ComfyUIWorkflow!]! @auth
     */
    @Query
    public CompletableFuture<List<ComfyUIWorkflow>> comfyuiWorkflows() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<List<ComfyUIWorkflow>> future = new CompletableFuture<>();

        String query = "SELECT * FROM comfyui_workflow WHERE isBuiltIn = TRUE OR ownerId IS NOT NULL ORDER BY name";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.tuple())
            .invoke(rows -> {
                List<ComfyUIWorkflow> workflows = new ArrayList<>();
                rows.forEach(row -> {
                    String workflowIdStr = row.getString("workflowId");
                    String ownerIdStr = row.getString("ownerId");

                    JsonObject workflowJson = null;
                    try {
                        String workflowJsonStr = ServiceHelper.safeGetJsonText(row, "workflowJson");
                        if (workflowJsonStr != null && !workflowJsonStr.isEmpty()) {
                            workflowJson = new JsonObject(workflowJsonStr);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse workflowJson for workflow {}", workflowIdStr, e);
                    }

                    JsonObject defaultParams = null;
                    try {
                        String defaultParamsStr = ServiceHelper.safeGetJsonText(row, "defaultParams");
                        if (defaultParamsStr != null && !defaultParamsStr.isEmpty()) {
                            defaultParams = new JsonObject(defaultParamsStr);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse defaultParams for workflow {}", workflowIdStr, e);
                    }

                    ComfyUIWorkflow workflow = ComfyUIWorkflow.builder()
                        .workflowId(workflowIdStr != null ? UUID.fromString(workflowIdStr) : null)
                        .name(row.getString("name"))
                        .description(row.getString("description"))
                        .workflowJson(workflowJson)
                        .triggerType(row.getString("triggerType"))
                        .defaultParams(defaultParams)
                        .isBuiltIn(row.getBoolean("isBuiltIn"))
                        .ownerId(ownerIdStr != null ? UUID.fromString(ownerIdStr) : null)
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
                        .build();

                    workflows.add(workflow);
                });
                future.complete(workflows);
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch ComfyUI workflows", err);
                future.completeExceptionally(err);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);

        return future;
    }

    /**
     * Generate an image via ComfyUI.
     * Schema: generateImage(input: GenerateImageInput!): GeneratedImage! @auth
     */
    @Mutation
    public CompletableFuture<GeneratedImage> generateImage(@Name("input") GenerateImageInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String characterId = (String) input.get("characterId");
        String prompt = (String) input.get("prompt");
        String negativePrompt = (String) input.get("negativePrompt");
        Integer width = (Integer) input.get("width");
        Integer height = (Integer) input.get("height");
        Integer steps = (Integer) input.get("steps");
        Double cfg = input.get("cfg") != null ? ((Number) input.get("cfg")).doubleValue() : null;
        String triggerType = (String) input.get("triggerType");
        String workflowId = (String) input.get("workflowId");

        String imageId = UUID.randomUUID().toString();

        CompletableFuture<GeneratedImage> future = new CompletableFuture<>();

        // The generated image is attached to a caller-supplied conversation —
        // verify access before inserting, otherwise any user could inject
        // image-generation rows into any conversation.
        verifyConversationAccess(conversationId, auth.getUserId().toString())
            .subscribe().with(hasAccess -> {
                if (!hasAccess) {
                    future.completeExceptionally(new RuntimeException("Conversation not found or access denied"));
                    return;
                }
                doGenerateImage(conversationId, characterId, prompt, negativePrompt,
                    width, height, steps, cfg, triggerType, workflowId, imageId, future);
            }, err -> {
                log.error("Failed to verify access for generateImage conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void doGenerateImage(String conversationId, String characterId,
                                 String prompt, String negativePrompt, Integer width, Integer height,
                                 Integer steps, Double cfg, String triggerType, String workflowId,
                                 String imageId,
                                 CompletableFuture<GeneratedImage> future) {
        // Resolve template, prompts (enhanced from narrative + world state when
        // no prompt was supplied), and effective dimensions before inserting.
        imageGenerationService.prepareGeneration(conversationId, characterId, prompt, negativePrompt,
                width, height, triggerType, workflowId)
            .subscribe().with(job -> doGenerateImageResolved(job, conversationId, characterId,
                steps, cfg, imageId, future),
                err -> {
                    log.error("Failed to prepare image generation for conversation {}", conversationId, err);
                    future.completeExceptionally(err);
                });
    }

    private void doGenerateImageResolved(com.eonmux.chatllm.services.ImageGenerationService.ImageJob job,
                                         String conversationId, String characterId,
                                         Integer steps, Double cfg, String imageId,
                                         CompletableFuture<GeneratedImage> future) {
        int effectiveSteps = steps != null ? steps : job.defaultParams.getInteger("steps", 20);
        double effectiveCfg = cfg != null ? cfg : job.defaultParams.getDouble("cfg", 7.0);

        // A null job negative lets the template's own negative node stand; a
        // supplied one replaces it. No append mechanism: the "no people" tags
        // for backdrops live in the scene template's negative text itself.
        final String effectiveNegative = job.negativePrompt;

        // The seed is chosen here so the row, the submitted graph, and the
        // completion update all agree on it (reproducibility).
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        boolean ref2VA = job.template != null
            && containsNodeClass(job.template, "MiniMaxH3ReferenceToVideo");
        boolean needsRefs = ref2VA && containsNodeClass(job.template, "LoadImage");

        // Upload reference images into ComfyUI's input directory before the
        // graph is built; LoadImage nodes are filled with the returned names.
        io.smallrye.mutiny.Uni<List<String>> uploadsUni =
            (config.isComfyuiEnabled() && needsRefs)
                ? uploadReferenceImages(job.referenceImages)
                : io.smallrye.mutiny.Uni.createFrom().item(new ArrayList<String>());

        uploadsUni
            .chain(names -> {
                // The character has no usable reference files: the
                // reference-conditioned graph cannot run, so fall back to the
                // standard template/legacy path below.
                boolean useRef2VA = ref2VA && (!needsRefs || !names.isEmpty());
                JsonObject workflow;
                if (useRef2VA) {
                    workflow = workflowBuilder.buildRef2VAWorkflow(
                        job.template, resolveRef2VAPrompt(job), seed,
                        job.width, job.height, names);
                    return insertAndSubmit(job, conversationId, characterId,
                        workflow, effectiveNegative, effectiveSteps, effectiveCfg,
                        imageId, seed, future);
                }

                if (job.template != null) {
                    JsonObject overrides = new JsonObject()
                        .put("positive_prompt", job.prompt != null ? job.prompt : "")
                        .put("negative_prompt", effectiveNegative)
                        .put("width", job.width)
                        .put("height", job.height)
                        .put("steps", effectiveSteps)
                        .put("cfg", effectiveCfg)
                        .put("checkpoint", config.getComfyuiDefaultCheckpoint());
                    String lora = config.getComfyuiDefaultLora();
                    if (!lora.isEmpty()) overrides.put("lora", lora);
                    workflow = workflowBuilder.buildFromTemplate(job.template, overrides);
                } else {
                    workflow = workflowBuilder.buildTxt2ImgWorkflow(
                        job.prompt != null ? job.prompt : "",
                        effectiveNegative,
                        job.width,
                        job.height,
                        seed,
                        config.getComfyuiDefaultCheckpoint(),
                        effectiveSteps);
                }
                return insertAndSubmit(job, conversationId, characterId,
                    workflow, effectiveNegative, effectiveSteps, effectiveCfg,
                    imageId, seed, future);
            })
            .subscribe().with(ignored -> {}, future::completeExceptionally);
    }

    private io.smallrye.mutiny.Uni<java.lang.Void> insertAndSubmit(
            com.eonmux.chatllm.services.ImageGenerationService.ImageJob job,
            String conversationId, String characterId, JsonObject workflow,
            String effectiveNegative, int effectiveSteps, double effectiveCfg,
            String imageId, long seed, CompletableFuture<GeneratedImage> future) {
        // Build metadata JSON with generation parameters and pending status
        JsonObject metadata = new JsonObject()
            .put("steps", effectiveSteps)
            .put("cfg", effectiveCfg)
            .put("status", "pending");

        String insertQuery = """
            INSERT INTO generated_image (imageId, conversationId, characterId, prompt, negativePrompt, workflowId, width, height, seed, metadata, filePath)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        String pendingFilePath = "pending/" + imageId;

        return mysqlPool.preparedQuery(insertQuery)
            .execute(Tuple.tuple()
                .addValue(imageId)
                .addValue(conversationId)
                .addValue(characterId)
                .addValue(job.prompt)
                .addValue(effectiveNegative)
                .addValue(job.workflowId)
                .addValue(job.width)
                .addValue(job.height)
                .addValue(seed)
                .addValue(metadata.encode())
                .addValue(pendingFilePath))
            .map(insertResult -> {
                GeneratedImage image = GeneratedImage.builder()
                    .imageId(UUID.fromString(imageId))
                    .conversationId(conversationId != null ? UUID.fromString(conversationId) : null)
                    .characterId(characterId != null ? UUID.fromString(characterId) : null)
                    .prompt(job.prompt)
                    .negativePrompt(effectiveNegative)
                    .workflowId(job.workflowId)
                    .width(job.width)
                    .height(job.height)
                    .seed(seed)
                    .filePath(pendingFilePath)
                    .thumbnailPath(null)
                    .metadata(metadata)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                    .build();

                // Publish typed ImageGenerationProgress event so the GraphQL
                // subscription can deserialize into the matching model.
                JsonObject eventData = new JsonObject()
                    .put("promptId", imageId)
                    .put("conversationId", conversationId)
                    .put("progress", 0.0)
                    .put("currentStep", 0)
                    .put("totalSteps", effectiveSteps)
                    .put("status", "pending");

                vertx.eventBus().publish(RoleplayEventAddresses.IMAGE_PROGRESS + conversationId, eventData);

                // Submit to ComfyUI when enabled. Use the DB imageId as the job
                // correlation ID so the comfyui.job.complete consumer (which
                // reads body.getString("jobId") and UPDATEs WHERE imageId = ?)
                // matches the correct row.
                if (config.isComfyuiEnabled()) {
                    try {
                        queueManager.submitWithId(imageId, conversationId, characterId, workflow)
                            .subscribe().with(
                                jobId -> log.info("Submitted ComfyUI job {} for image {}", jobId, imageId),
                                err -> {
                                    log.error("Failed to submit ComfyUI job for image {}: {}", imageId, err.getMessage());
                                    markSubmitFailed(imageId, err.getMessage());
                                });
                    } catch (Exception e) {
                        log.error("Failed to build ComfyUI workflow for image {}: {}", imageId, e.getMessage());
                        markSubmitFailed(imageId, e.getMessage());
                    }
                }

                future.complete(image);
                return null;
            })
            .replaceWithVoid()
            .onFailure().invoke(err -> {
                log.error("Failed to create image generation request for conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });
    }

    /**
     * Mark a row `failed` when the ComfyUI queue rejects the job (queue full,
     * client down, ...). The row is inserted `pending` before the fire-and-forget
     * submit, so a rejection is otherwise invisible in the GraphQL response and
     * the row stays `pending` forever.
     */
    private void markSubmitFailed(String imageId, String reason) {
        String updateQuery = """
            UPDATE generated_image
            SET metadata = JSON_SET(COALESCE(metadata, '{}'), '$.status', 'failed', '$.failReason', ?)
            WHERE imageId = ?
            """;
        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.tuple()
                .addValue(reason != null ? reason : "queue rejection")
                .addValue(imageId))
            .subscribe().with(
                ignored -> log.info("Marked image {} as failed after submit rejection", imageId),
                err -> log.error("Failed to mark image {} as failed: {}", imageId, err.getMessage()));
    }

    /** Upload reference images sequentially; returns the ComfyUI file names in order. */
    private io.smallrye.mutiny.Uni<List<String>> uploadReferenceImages(
            List<com.eonmux.chatllm.services.ImageGenerationService.RefImage> refs) {
        if (refs == null || refs.isEmpty()) {
            return io.smallrye.mutiny.Uni.createFrom().item(new ArrayList<String>());
        }
        return refs.stream()
            .reduce(
                io.smallrye.mutiny.Uni.createFrom().item(new ArrayList<String>()),
                (acc, ref) -> acc.chain(names ->
                    comfyuiClient.uploadImage(ref.filename, ref.data, "")
                        .map(name -> {
                            names.add(name);
                            return names;
                        })),
                (a, b) -> a);
    }

    /**
     * Compose the H3 prompt: the mode text (avatar/portrait from the
     * template's defaultParams; the sheet text already lives in the graph),
     * world-state slots filled, then the enhanced prompt appended as
     * additional guidance.
     */
    private String resolveRef2VAPrompt(com.eonmux.chatllm.services.ImageGenerationService.ImageJob job) {
        String base = null;
        JsonObject modes = job.defaultParams.getJsonObject("modes", new JsonObject());
        String mode = modeNameFor(job.width, job.height);
        if (mode != null) base = modes.getString(mode + "Prompt");
        if (base == null) base = templatePromptText(job.template);
        String text = com.eonmux.chatllm.comfyui.ComfyUIWorkflowBuilder.fillPromptSlots(base, job.worldState);
        if (job.prompt != null && !job.prompt.isBlank() && !text.contains(job.prompt.trim())) {
            text = text + " " + job.prompt.trim();
        }
        return text;
    }

    private static String modeNameFor(int width, int height) {
        if (width == 1024 && height == 1024) return "sheet";
        if (width == 768 && height == 768) return "avatar";
        if (width == 832 && height == 1216) return "portrait";
        return null;
    }

    private static String templatePromptText(JsonObject template) {
        if (template == null) return "";
        for (String nodeId : template.fieldNames()) {
            if (template.getValue(nodeId) instanceof JsonObject node
                    && "CLIPTextEncode".equals(node.getString("class_type", ""))) {
                return node.getJsonObject("inputs").getString("text", "");
            }
        }
        return "";
    }

    private static boolean containsNodeClass(JsonObject workflow, String classType) {
        if (workflow == null) return false;
        for (String nodeId : workflow.fieldNames()) {
            if (workflow.getValue(nodeId) instanceof JsonObject node
                    && classType.equals(node.getString("class_type", ""))) return true;
        }
        return false;
    }

    /**
     * Cancel an in-progress image generation.
     * Schema: cancelImageGeneration(promptId: ID!): Boolean! @auth
     */
    @Mutation
    public CompletableFuture<Boolean> cancelImageGeneration(@Name("promptId") String promptId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Only the owner/participants of the image's conversation may cancel
        // it — previously any authenticated user could cancel any imageId.
        String findQuery = "SELECT conversationId FROM generated_image WHERE imageId = ?";
        mysqlPool.preparedQuery(findQuery)
            .execute(Tuple.of(promptId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return io.smallrye.mutiny.Uni.createFrom().failure(new RuntimeException("Image not found"));
                }
                String conversationId = rows.iterator().next().getString("conversationId");
                return verifyConversationAccess(conversationId, userId)
                    .map(hasAccess -> {
                        if (!hasAccess) {
                            throw new RuntimeException("Access denied");
                        }
                        return conversationId;
                    });
            })
            .subscribe().with(conversationId -> {
                String updateQuery = "UPDATE generated_image SET metadata = JSON_SET(COALESCE(metadata, '{}'), '$.status', 'cancelled') WHERE imageId = ?";
                mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(promptId))
                    .invoke(result -> {
                        boolean cancelled = result.rowCount() > 0;
                        if (cancelled) {
                            // Notify subscribers about cancellation via progress event
                            String selectQuery = "SELECT conversationId FROM generated_image WHERE imageId = ?";
                            mysqlPool.preparedQuery(selectQuery)
                                .execute(Tuple.of(promptId))
                                .subscribe().with(
                                    rows -> {
                                        if (rows.size() > 0) {
                                            String convId = rows.iterator().next().getString("conversationId");
                                            if (convId != null) {
                                                JsonObject eventData = new JsonObject()
                                                    .put("promptId", promptId)
                                                    .put("conversationId", convId)
                                                    .put("progress", 0.0)
                                                    .put("currentStep", 0)
                                                    .put("totalSteps", 0)
                                                    .put("status", "cancelled");
                                                vertx.eventBus().publish(RoleplayEventAddresses.IMAGE_PROGRESS + convId, eventData);
                                            }
                                        }
                                    },
                                    err -> log.warn("Failed to look up conversationId for cancelled image {}", promptId, err));
                        }
                        future.complete(cancelled);
                    })
                    .onFailure().invoke(err -> {
                        log.error("Failed to cancel image generation {}", promptId, err);
                        future.completeExceptionally(err);
                    }).subscribe().with(ignored -> {}, future::completeExceptionally);
            }, err -> {
                log.warn("Failed to authorize cancellation for image {}: {}", promptId, err.getMessage());
                future.completeExceptionally(err);
            });

        return future;
    }
}
