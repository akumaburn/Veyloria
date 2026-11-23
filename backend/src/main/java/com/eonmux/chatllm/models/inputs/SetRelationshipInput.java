package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("SetRelationshipInput")
public class SetRelationshipInput {
    private String conversationId;
    private String fromCharacterId;
    private String toCharacterId;
    private String relationshipType;
    private Double intensity;
    private String description;
    // history is optional metadata; sent as JSON string by frontend if present
    private String history;
    private Boolean isReciprocal;
}
