package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Progress event emitted by the image-generation pipeline.
 * Streamed to the client via the imageGenerationProgress subscription.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageGenerationProgress {
    /** ComfyUI prompt/queue ID for this job (same value as imageId when created locally). */
    private String promptId;
    private String conversationId;
    /** Normalized 0.0–1.0 completion ratio. */
    private Double progress;
    private Integer currentStep;
    private Integer totalSteps;
    /** Lifecycle state: pending, running, completed, cancelled, failed. */
    private String status;
}
