package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CreateRoleplayConversationInput")
public class CreateRoleplayConversationInput {
    private String title;
    private String description;
    private List<String> characterIds;
    private String rpMode;
    private String templateId;
    private Boolean agentPipelineEnabled;
    private String turnMode;
    private AgentModelConfigInput agentModels;
}
