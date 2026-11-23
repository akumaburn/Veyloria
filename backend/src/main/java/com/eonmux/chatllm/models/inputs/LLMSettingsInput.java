package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("LLMSettingsInput")
public class LLMSettingsInput {
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Integer topK;
    private Double repeatPenalty;
    private Double presencePenalty;
    private Double frequencyPenalty;
    private Double minP;
    private Double tfsZ;
    private Double typicalP;
    private Integer seed;
    private List<String> stopSequences;
    private Integer nPredict;
    private Integer nCtx;
    private Integer contextSize;
    private Integer nBatch;
    private Integer nThreads;
    private Integer nGpuLayers;
    private Integer mainGpu;
    private String promptTemplate;
    private String systemPrompt;
    private String chatFormat;
    private Boolean streamingEnabled;
    private Integer mirostat;
    private Double mirostatTau;
    private Double mirostatEta;
    private String currentTemplate;
}
