package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("CreateGroupConversationInput")
public class CreateGroupConversationInput {
    private String title;
    private List<String> characterIds;
    private List<CharacterWeightInput> charactersWithWeights;
    private String turnMode;
    private Integer maxConsecutiveResponses;
    private String category;
    private List<String> tags;
}
