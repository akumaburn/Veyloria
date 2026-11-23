package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LLMStatus {
    private boolean isRunning;
    private boolean modelLoaded;
    private String currentModel;
    private long availableMemory;
    private long usedMemory;
    private String providerType;
}
