package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("GenerateImageInput")
public class GenerateImageInput {
    private String conversationId;
    private String characterId;
    private String prompt;
    private String negativePrompt;
    private Integer width;
    private Integer height;
    private Integer steps;
    private Double cfg;
    private String triggerType;
    private String workflowId;
}
