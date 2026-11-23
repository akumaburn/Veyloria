package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("RollDiceInput")
public class RollDiceInput {
    private String conversationId;
    private String characterId;
    private String expression;
    private String purpose;
    private Boolean isHidden;
}
