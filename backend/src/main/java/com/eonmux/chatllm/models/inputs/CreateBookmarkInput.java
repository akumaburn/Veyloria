package com.eonmux.chatllm.models.inputs;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CreateBookmarkInput")
public class CreateBookmarkInput {
    private String conversationId;
    private String messageId;
    private String label;
    private String description;
    private List<String> tags;
}
