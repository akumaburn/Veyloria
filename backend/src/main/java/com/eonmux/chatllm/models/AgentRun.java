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
public class AgentRun {
    private UUID runId;
    private UUID conversationId;
    private UUID triggerMessageId;
    private String agentName;
    private String agentPass;
    private String modelUsed;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer durationMs;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject output;
    @Builder.Default
    private String status = "success";
    @Builder.Default
    private Integer retryCount = 0;
    private String errorMessage;
    private String createdAt;
}
