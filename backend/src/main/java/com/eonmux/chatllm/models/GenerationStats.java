package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Generation statistics for LLM responses.
 * Matches the GraphQL GenerationStats type.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationStats {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer promptEvalMs;
    private Integer generationMs;
    private Integer totalTimeMs;
    private Double tokensPerSecond;

    /**
     * Create GenerationStats from individual values.
     */
    public static GenerationStats of(
            int promptTokens,
            int completionTokens,
            int promptEvalMs,
            int generationMs,
            int totalTimeMs,
            double tokensPerSecond) {
        return GenerationStats.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .promptEvalMs(promptEvalMs)
                .generationMs(generationMs)
                .totalTimeMs(totalTimeMs)
                .tokensPerSecond(tokensPerSecond)
                .build();
    }

    /**
     * Check if stats are populated (have meaningful values).
     */
    public boolean hasStats() {
        return (completionTokens != null && completionTokens > 0) ||
               (totalTimeMs != null && totalTimeMs > 0);
    }
}
