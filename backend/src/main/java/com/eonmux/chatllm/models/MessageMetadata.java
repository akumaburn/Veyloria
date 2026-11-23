package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Metadata for a message including processing details and statistics.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageMetadata {
    private UUID id;
    private UUID messageId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTimeMs;
    private Double temperature;
    private Double topP;
    private Integer topK;
    private Double repeatPenalty;
    private Integer maxTokens;
    private Double totalCost;
    private String stopSequences;
    private Double presencePenalty;
    private Double frequencyPenalty;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private Boolean edited;
    private String editedAt;
    private Integer editCount;
    private String editedBy;
    private Boolean regenerated;
    private UUID parentMessageId; // For regenerated messages
    private String stopReason; // max_tokens, stop_sequence, complete
    private Double confidence;
    private String createdAt;
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();

    /**
     * Calculate tokens per second.
     */
    public Double getTokensPerSecond() {
        if (completionTokens != null && totalTimeMs != null && totalTimeMs > 0) {
            return (double) completionTokens / (totalTimeMs / 1000.0);
        }
        return null;
    }

    /**
     * Get total tokens.
     */
    public Integer getTotalTokens() {
        if (promptTokens != null && completionTokens != null) {
            return promptTokens + completionTokens;
        }
        return completionTokens;
    }

    /**
     * Add extra metadata.
     */
    public void addExtra(String key, Object value) {
        if (extra == null) extra = new HashMap<>();
        extra.put(key, value);
    }

    /**
     * Get extra metadata value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key, Class<T> type) {
        if (extra == null) return null;
        Object value = extra.get(key);
        if (value != null && type.isAssignableFrom(value.getClass())) {
            return (T) value;
        }
        return null;
    }

    /**
     * Check if message was fast (under threshold).
     */
    public boolean isFast(int thresholdMs) {
        return totalTimeMs != null && totalTimeMs < thresholdMs;
    }

    /**
     * Check if message used a specific model.
     */
    public boolean usedModel(String modelName) {
        return model != null && model.contains(modelName);
    }

    /**
     * Create metadata for user message.
     */
    public static MessageMetadata forUserMessage(UUID messageId) {
        return MessageMetadata.builder()
            .messageId(messageId)
            .totalTimeMs(0)
            .completionTokens(0)
            .build();
    }

    /**
     * Create metadata for assistant message.
     */
    public static MessageMetadata forAssistantMessage(UUID messageId, String model, int completionTokens, int totalTimeMs) {
        return MessageMetadata.builder()
            .messageId(messageId)
            .model(model)
            .completionTokens(completionTokens)
            .totalTimeMs(totalTimeMs)
            .build();
    }
}
