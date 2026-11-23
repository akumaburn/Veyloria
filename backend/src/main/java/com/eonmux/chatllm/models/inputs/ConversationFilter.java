package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("ConversationFilter")
public class ConversationFilter {
    private Boolean isArchived;
    private Boolean isPinned;
    private String category;
    private String characterId;
}
