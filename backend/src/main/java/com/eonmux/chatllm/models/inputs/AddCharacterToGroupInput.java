package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("AddCharacterToGroupInput")
public class AddCharacterToGroupInput {
    private String conversationId;
    private String characterId;
    private Integer turnOrder;
    private Double weight;
}
