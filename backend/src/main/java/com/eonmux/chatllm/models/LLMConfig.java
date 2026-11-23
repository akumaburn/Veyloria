package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LLMConfig {
    private String modelPath;
    private int gpuLayers;
    private int contextSize;
    private int batchSize;
    private int threads;
    private double temperature;
    private double topP;
    private int topK;
    private List<String> templates;
    private String currentTemplate;
    private String providerType;
    private String externalBaseUrl;
    private String externalModel;
}
