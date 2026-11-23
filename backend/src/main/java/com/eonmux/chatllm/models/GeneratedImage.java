package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedImage {
    private UUID imageId;
    private UUID conversationId;
    private UUID messageId;
    private UUID characterId;
    private String prompt;
    private String negativePrompt;
    private String workflowId;
    private String filePath;
    private String thumbnailPath;
    private Integer width;
    private Integer height;
    private Long seed;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private String sceneContext;
    private String createdAt;
}
