package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("MessageSearchInput")
public class MessageSearchInput {
    private String query;
    private String conversationId;
    private Integer limit;
    private Integer offset;
}
