package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentModelConfig {
    private UUID configId;
    private UUID conversationId;
    private String defaultModel;
    private String highlighterModel;
    private String museModel;
    private String narratorModel;
    private String accountantModel;
    private String skepticModel;
    private String casterModel;
    private String createdAt;
    private String updatedAt;

    public String getModelForAgent(String agentName) {
        return switch (agentName) {
            case "highlighter" -> highlighterModel;
            case "muse" -> museModel;
            case "narrator" -> narratorModel;
            case "accountant" -> accountantModel;
            case "skeptic" -> skepticModel;
            case "caster" -> casterModel;
            default -> null;
        };
    }

    public String resolveModel(String agentName) {
        String override = getModelForAgent(agentName);
        return override != null && !override.isEmpty() ? override : defaultModel;
    }
}
