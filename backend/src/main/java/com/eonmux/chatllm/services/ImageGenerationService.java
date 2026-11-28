package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.comfyui.PromptEnhancer;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.GeneratedImage;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class ImageGenerationService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    PromptEnhancer promptEnhancer;
    @Inject
    com.eonmux.chatllm.config.AppConfig config;

    /** A reference image ready to upload to ComfyUI. */
    public static class RefImage {
        public final String filename;
        public final byte[] data;

        public RefImage(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }
    }

    /**
     * Result of preparing an image job: the final prompts, the resolved
     * template (or null for the legacy built-in txt2img graph), its default
     * params, the dimensions to use, the world state used for prompting, and
     * the character's reference images for reference-conditioned graphs.
     */
    public static class ImageJob {
        public final String prompt;
        public final String negativePrompt;
        public final String workflowId;
        public final JsonObject template;
        public final JsonObject defaultParams;
        public final Integer width;
        public final Integer height;
        public final List<RefImage> referenceImages;
        public final JsonObject worldState;

        public ImageJob(String prompt, String negativePrompt, String workflowId,
                         JsonObject template, JsonObject defaultParams,
                         Integer width, Integer height,
                         List<RefImage> referenceImages, JsonObject worldState) {
            this.prompt = prompt;
            this.negativePrompt = negativePrompt;
            this.workflowId = workflowId;
            this.template = template;
            this.defaultParams = defaultParams;
            this.width = width;
            this.height = height;
            this.referenceImages = referenceImages;
            this.worldState = worldState;
        }
    }

    /**
     * A stored workflow template. The graph is kept pure (node ids to node
     * objects only) so builder code can iterate top-level keys as nodes
     * without meeting metadata entries.
     */
    public static class WorkflowTemplate {
        public final JsonObject graph;
        public final String workflowId;
        public final JsonObject defaultParams;

        public WorkflowTemplate(JsonObject graph, String workflowId, JsonObject defaultParams) {
            this.graph = graph;
            this.workflowId = workflowId;
            this.defaultParams = defaultParams;
        }
    }

    /** Load a stored workflow template by id; null when absent. */
    public Uni<WorkflowTemplate> getWorkflowTemplate(String workflowId) {
        String query = "SELECT workflowId, workflowJson, defaultParams FROM comfyui_workflow WHERE workflowId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(workflowId))
            .map(rows -> rows.size() > 0 ? templateFromRow(rows.iterator().next()) : null)
            .onFailure().recoverWithItem(err -> {
                log.warn("Failed to load workflow template {}: {}", workflowId, err.getMessage());
                return null;
            });
    }

    /** Load the newest built-in template for a trigger type; null when absent. */
    public Uni<WorkflowTemplate> getBuiltInWorkflowByTrigger(String triggerType) {
        String query = "SELECT workflowId, workflowJson, defaultParams FROM comfyui_workflow "
            + "WHERE triggerType = ? AND isBuiltIn = TRUE ORDER BY updatedAt DESC, createdAt DESC LIMIT 1";
        return pool.preparedQuery(query)
            .execute(Tuple.of(triggerType))
            .map(rows -> rows.size() > 0 ? templateFromRow(rows.iterator().next()) : null)
            .onFailure().recoverWithItem(err -> {
                log.warn("Failed to load built-in workflow for trigger {}: {}", triggerType, err.getMessage());
                return null;
            });
    }

    /** Load the conversation's world_state row as a tag source; null when absent. */
    public Uni<JsonObject> getWorldState(String conversationId) {
        String query = "SELECT currentScene, currentLocation, currentArea, timeOfDay, weather, mood "
            + "FROM world_state WHERE conversationId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() == 0) return null;
                Row row = rows.iterator().next();
                return new JsonObject()
                    .put("currentScene", row.getString("currentScene"))
                    .put("currentLocation", row.getString("currentLocation"))
                    .put("currentArea", row.getString("currentArea"))
                    .put("timeOfDay", row.getString("timeOfDay"))
                    .put("weather", row.getString("weather"))
                    .put("mood", row.getString("mood"));
            })
            .onFailure().recoverWithItem(err -> {
                log.warn("Failed to load world state for conversation {}: {}", conversationId, err.getMessage());
                return null;
            });
    }

    /**
     * Resolve everything a generation needs before touching ComfyUI: the
     * workflow template (explicit id wins, then built-in by trigger type),
     * the prompts (enhanced from narrative + world state when the caller
     * supplied no prompt), and the effective dimensions (caller wins, then
     * template defaults, then 512).
     */
    public Uni<ImageJob> prepareGeneration(String conversationId, String characterId,
                                           String prompt, String negativePrompt,
                                           Integer width, Integer height,
                                           String triggerType, String workflowId) {
        Uni<WorkflowTemplate> templateUni = (workflowId != null && !workflowId.isEmpty())
            ? getWorkflowTemplate(workflowId)
            : (triggerType != null && !triggerType.isEmpty())
                ? getBuiltInWorkflowByTrigger(triggerType)
                 : Uni.createFrom().item((WorkflowTemplate) null);

        Uni<JsonObject> worldUni = (conversationId != null && !conversationId.isEmpty())
            ? getWorldState(conversationId)
            : Uni.createFrom().item((JsonObject) null);

        return worldUni.chain(worldState -> templateUni.chain(template -> {
            JsonObject params = template != null && template.defaultParams != null
                ? template.defaultParams : new JsonObject();
            int resolvedWidth = width != null ? width
                : params.getInteger("width", 512);
            int resolvedHeight = height != null ? height
                : params.getInteger("height", 512);
            String resolvedWorkflowId = template != null ? template.workflowId : workflowId;
            JsonObject graph = template != null ? template.graph : null;

            Uni<ImageJob> jobUni;
            if (prompt == null || prompt.isBlank()) {
                String narrative = composeNarrative(worldState);
                jobUni = promptEnhancer.enhance(narrative, characterId,
                        triggerType != null ? triggerType : "scene_change", worldState)
                    .chain(imagePrompt -> finishJob(imagePrompt.positive,
                        joinNegative(negativePrompt, imagePrompt.negative),
                        resolvedWorkflowId, graph, params,
                        resolvedWidth, resolvedHeight, characterId, worldState));
            } else {
                jobUni = finishJob(prompt, negativePrompt, resolvedWorkflowId, graph,
                    params, resolvedWidth, resolvedHeight, characterId, worldState);
            }

            return jobUni;
        }));
    }

    /** Load the character's reference images from disk for reference-conditioned graphs. */
    private Uni<List<RefImage>> loadReferenceImages(String characterId) {
        if (characterId == null || characterId.isEmpty()) {
            return Uni.createFrom().item(new ArrayList<RefImage>());
        }
        String query = "SELECT referencePath FROM character_visual_ref "
            + "WHERE characterId = ? AND isActive = TRUE AND referencePath IS NOT NULL AND referencePath <> '' "
            + "ORDER BY weight DESC LIMIT 5";
        return pool.preparedQuery(query)
            .execute(Tuple.of(characterId))
            .map(rows -> {
                List<RefImage> refs = new ArrayList<>();
                for (Row row : rows) {
                    String path = row.getString("referencePath");
                    byte[] data = readReferenceFile(path);
                    if (data == null) continue;
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    refs.add(new RefImage(name, data));
                }
                return refs;
            })
            .onFailure().recoverWithItem(err -> {
                log.warn("Failed to load reference images for character {}: {}", characterId, err.getMessage());
                return new ArrayList<RefImage>();
            });
    }

    /** Read a reference image; absolute paths are used as-is, relative ones resolve under the uploads directory. */
    private byte[] readReferenceFile(String referencePath) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(referencePath);
            if (!path.isAbsolute()) {
                path = java.nio.file.Paths.get(config.getUploadsDirectory()).resolve(referencePath);
            }
            if (!java.nio.file.Files.isRegularFile(path)) {
                log.warn("Reference image not found: {}", path);
                return null;
            }
            return java.nio.file.Files.readAllBytes(path);
        } catch (Exception e) {
            log.warn("Failed to read reference image {}: {}", referencePath, e.getMessage());
            return null;
        }
    }

    private Uni<ImageJob> finishJob(String resolvedPrompt, String resolvedNegative,
                                    String resolvedWorkflowId, JsonObject template,
                                    JsonObject params, int resolvedWidth, int resolvedHeight,
                                    String characterId, JsonObject worldState) {
        return loadReferenceImages(characterId)
            .map(refs -> new ImageJob(resolvedPrompt, resolvedNegative, resolvedWorkflowId,
                template, params, resolvedWidth, resolvedHeight, refs, worldState));
    }

    private static WorkflowTemplate templateFromRow(Row row) {
        String json = ServiceHelper.safeGetJsonText(row, "workflowJson");
        if (json == null || json.isEmpty()) return null;
        String paramsJson = ServiceHelper.safeGetJsonText(row, "defaultParams");
        return new WorkflowTemplate(new JsonObject(json), row.getString("workflowId"),
            new JsonObject(paramsJson != null ? paramsJson : "{}"));
    }

    private static String composeNarrative(JsonObject worldState) {
        if (worldState == null) return "";
        List<String> parts = new ArrayList<>();
        for (String key : new String[]{"currentScene", "currentLocation", "currentArea"}) {
            String value = worldState.getString(key);
            if (value != null && !value.isBlank()) parts.add(value.trim());
        }
        return String.join(", ", parts);
    }

    private static String joinNegative(String... fragments) {
        List<String> parts = new ArrayList<>();
        for (String fragment : fragments) {
            if (fragment != null && !fragment.isBlank()) parts.add(fragment.trim());
        }
        return String.join(", ", parts);
    }

    public Uni<List<GeneratedImage>> getImages(String conversationId, Integer limit) {
        String query = "SELECT * FROM generated_image WHERE conversationId = ? ORDER BY createdAt DESC LIMIT ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, limit != null ? limit : 50))
            .map(rows -> {
                List<GeneratedImage> images = new ArrayList<>();
                for (Row row : rows) images.add(mapRowToImage(row));
                return images;
            });
    }

    public Uni<GeneratedImage> saveImage(String conversationId, String messageId, String characterId,
                                             String prompt, String negativePrompt, String workflowId,
                                             String filePath, Integer width, Integer height, Long seed,
                                             String sceneContext) {
        String id = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO generated_image (imageId, conversationId, messageId, characterId, prompt, negativePrompt, workflowId, filePath, width, height, seed, sceneContext)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(messageId).addValue(characterId)
                .addValue(prompt).addValue(negativePrompt).addValue(workflowId).addValue(filePath)
                .addValue(width).addValue(height).addValue(seed).addValue(sceneContext))
            .map(v -> GeneratedImage.builder()
                .imageId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .prompt(prompt)
                .filePath(filePath)
                .width(width)
                .height(height)
                .seed(seed)
                .sceneContext(sceneContext)
                .build());
    }

    private GeneratedImage mapRowToImage(Row row) {
        GeneratedImage img = new GeneratedImage();
        img.setImageId(UUID.fromString(row.getString("imageId")));
        img.setConversationId(UUID.fromString(row.getString("conversationId")));
        if (row.getString("messageId") != null) img.setMessageId(UUID.fromString(row.getString("messageId")));
        if (row.getString("characterId") != null) img.setCharacterId(UUID.fromString(row.getString("characterId")));
        img.setPrompt(row.getString("prompt"));
        img.setNegativePrompt(row.getString("negativePrompt"));
        img.setWorkflowId(row.getString("workflowId"));
        img.setFilePath(row.getString("filePath"));
        img.setThumbnailPath(row.getString("thumbnailPath"));
        img.setWidth(row.getInteger("width"));
        img.setHeight(row.getInteger("height"));
        img.setSeed(row.getLong("seed"));
        img.setMetadata(ServiceHelper.safeGetJsonObject(row, "metadata"));
        img.setSceneContext(row.getString("sceneContext"));
        img.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return img;
    }
}
