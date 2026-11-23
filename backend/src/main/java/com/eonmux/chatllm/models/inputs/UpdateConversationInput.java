package com.eonmux.chatllm.models.inputs;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("UpdateConversationInput")
public class UpdateConversationInput {
    private String title;
    private String description;
    private String category;
    private Boolean isPinned;
    private Boolean isArchived;
    private List<String> tags;
}
