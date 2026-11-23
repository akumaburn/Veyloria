package com.eonmux.chatllm.models.inputs;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("PublishStoryInput")
public class PublishStoryInput {
    private String conversationId;
    private String title;
    private String synopsis;
    private String genre;
    private List<String> tags;
    private String coverImageId;
}
