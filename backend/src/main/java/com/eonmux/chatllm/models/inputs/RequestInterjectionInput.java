package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("RequestInterjectionInput")
public class RequestInterjectionInput {
    private String conversationId;
    private String characterId;
    private String reason;
    private Integer priority;
}
