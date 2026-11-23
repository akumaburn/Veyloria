package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("AgentModelConfigInput")
public class AgentModelConfigInput {
    private String defaultModel;
    private String highlighterModel;
    private String museModel;
    private String narratorModel;
    private String accountantModel;
    private String skepticModel;
    private String casterModel;
}
