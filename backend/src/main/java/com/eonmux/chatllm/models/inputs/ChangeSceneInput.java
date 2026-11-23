package com.eonmux.chatllm.models.inputs;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("ChangeSceneInput")
public class ChangeSceneInput {
    private String conversationId;
    private String sceneName;
    private String location;
    private String description;
    private List<String> characterIds;
}
