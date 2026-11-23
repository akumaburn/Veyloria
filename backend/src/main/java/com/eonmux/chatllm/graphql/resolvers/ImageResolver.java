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
    ComfyUIWorkflowBuilder workflowBuilder;

    @Inject
    AppConfig config;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    /**
     * Verify the caller owns or actively participates in the conversation
     * before reading/creating/cancelling image-generation rows.
     */
    private io.smallrye.mutiny.Uni<Boolean> verifyConversationAccess(String conversationId, String userId) {
        return serviceHelper.verifyConversationAccess(conversationId, userId);
    }

    @PostConstruct
    public void init() {
        vertx.eventBus().<JsonObject>consumer("comfyui.job.complete", message -> {
            JsonObject body = message.body();
            String imageId = body.getString("jobId");
            String conversationId = body.getString("conversationId");
            String resultUrl = body.getString("resultUrl");

            String updateQuery = "UPDATE generated_image SET filePath = ?, metadata = JSON_SET(COALESCE(metadata, '{}'), '$.status', 'complete') WHERE imageId = ?";
            mysqlPool.preparedQuery(updateQuery)
                .execute(Tuple.of(resultUrl, imageId))
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
                                    }
                                },
                                err -> log.warn("Failed to fetch generated image {} after completion: {}", imageId, err.getMessage()));
                    },
                    err -> log.warn("Failed to update generated image {} on completion: {}", imageId, err.getMessage())
                );
        });
    }

    /**
     * Get generated images for a conversation.
     * Schema: generatedImages(conversationId: ID!, limit: Int): [GeneratedImage!]! @auth
     */
    @Query
    public CompletableFuture<List<GeneratedImage>> generatedImages(@Name("conversationId") String conversationId, @Name("limit") Integer limit) {
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
                doGeneratedImages(conversationId, effectiveLimit, future);
            }, err -> {
                log.error("Failed to verify access for generatedImages conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void doGeneratedImages(String conversationId, Integer limit, CompletableFuture<List<GeneratedImage>> future) {
        String query = "SELECT * FROM generated_image WHERE conversationId = ? ORDER BY createdAt DESC LIMIT ?";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId, limit))
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

        com.eonmux.chatllm.config.AppConfig config =
            (com.eonmux.chatllm.config.AppConfig) vertx.sharedData().getLocalMap("config").get("appConfig");

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

        vertx.createHttpClient()
            .request(io.vertx.core.http.HttpMethod.GET, port, host, "/queue")
            .compose(req -> req.send())
            .compose(resp -> resp.body())
            .onSuccess(body -> {
                try {
                    JsonObject json = new JsonObject(body.toString());
                    io.vertx.core.json.JsonArray running = json.getJsonArray("queue_running", new io.vertx.core.json.JsonArray());
                    io.vertx.core.json.JsonArray pending = json.getJsonArray("queue_pending", new io.vertx.core.json.JsonArray());
                    int queueSize = running.size() + pending.size();
                    future.complete(ComfyUIStatus.builder()
                        .connected(true)
                        .queueSize(queueSize)
                        .availableModels(List.of())
                        .availableLoras(List.of())
                        .build());
                } catch (Exception e) {
                    future.complete(ComfyUIStatus.builder()
                        .connected(true)
                        .queueSize(0)
                        .availableModels(List.of())
                        .availableLoras(List.of())
                        .build());
                }
            })
            .onFailure(err -> {
                future.complete(ComfyUIStatus.builder()
                    .connected(false)
                    .queueSize(0)
                    .availableModels(List.of())
                    .availableLoras(List.of())
                    .build());
            });

        return future;
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
        Integer width = input.get("width") != null ? (Integer) input.get("width") : 512;
        Integer height = input.get("height") != null ? (Integer) input.get("height") : 512;
        Integer steps = input.get("steps") != null ? (Integer) input.get("steps") : 20;
        Double cfg = input.get("cfg") != null ? ((Number) input.get("cfg")).doubleValue() : 7.0;

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
                    width, height, steps, cfg, imageId, future);
            }, err -> {
                log.error("Failed to verify access for generateImage conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void doGenerateImage(String conversationId, String characterId,
                                 String prompt, String negativePrompt, Integer width, Integer height,
                                 Integer steps, Double cfg, String imageId,
                                 CompletableFuture<GeneratedImage> future) {
        // Build metadata JSON with generation parameters and pending status
        JsonObject metadata = new JsonObject()
            .put("steps", steps)
            .put("cfg", cfg)
            .put("status", "pending");

        String insertQuery = """
            INSERT INTO generated_image (imageId, conversationId, characterId, prompt, negativePrompt, width, height, seed, metadata, filePath)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
            """;

        String pendingFilePath = "pending/" + imageId;

        mysqlPool.preparedQuery(insertQuery)
            .execute(Tuple.tuple()
                .addValue(imageId)
                .addValue(conversationId)
                .addValue(characterId)
                .addValue(prompt)
                .addValue(negativePrompt)
                .addValue(width)
                .addValue(height)
                .addValue(metadata.encode())
                .addValue(pendingFilePath))
            .invoke(insertResult -> {
                GeneratedImage image = GeneratedImage.builder()
                    .imageId(UUID.fromString(imageId))
                    .conversationId(conversationId != null ? UUID.fromString(conversationId) : null)
                    .characterId(characterId != null ? UUID.fromString(characterId) : null)
                    .prompt(prompt)
                    .negativePrompt(negativePrompt)
                    .workflowId(null)
                    .width(width)
                    .height(height)
                    .seed(null)
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
                    .put("totalSteps", steps)
                    .put("status", "pending");

                vertx.eventBus().publish(RoleplayEventAddresses.IMAGE_PROGRESS + conversationId, eventData);

                // Submit to ComfyUI when enabled. Use the DB imageId as the job
                // correlation ID so the comfyui.job.complete consumer (which
                // reads body.getString("jobId") and UPDATEs WHERE imageId = ?)
                // matches the correct row. Previously generateImage never called
                // submit, leaving every row stuck in "pending" forever.
                if (config.isComfyuiEnabled()) {
                    try {
                        JsonObject workflow = workflowBuilder.buildTxt2ImgWorkflow(
                            prompt != null ? prompt : "",
                            negativePrompt,
                            width,
                            height,
                            0L, // seed <= 0 -> auto-generated
                            config.getComfyuiDefaultCheckpoint(),
                            steps);
                        queueManager.submitWithId(imageId, conversationId, characterId, workflow)
                            .subscribe().with(
                                jobId -> log.info("Submitted ComfyUI job {} for image {}", jobId, imageId),
                                err -> log.error("Failed to submit ComfyUI job for image {}: {}", imageId, err.getMessage()));
                    } catch (Exception e) {
                        log.error("Failed to build ComfyUI workflow for image {}: {}", imageId, e.getMessage());
                    }
                }

                future.complete(image);
            })
            .onFailure().invoke(err -> {
                log.error("Failed to create image generation request for conversation {}", conversationId, err);
                future.completeExceptionally(err);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);
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
