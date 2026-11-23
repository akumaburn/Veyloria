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
public class ComfyUIWorkflow {
    private UUID workflowId;
    private String name;
    private String description;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject workflowJson;
    private String triggerType;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject defaultParams;
    @Builder.Default
    private Boolean isBuiltIn = false;
    private UUID ownerId;
    private String createdAt;
    private String updatedAt;
}
