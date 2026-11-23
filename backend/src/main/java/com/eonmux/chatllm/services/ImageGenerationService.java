package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
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
