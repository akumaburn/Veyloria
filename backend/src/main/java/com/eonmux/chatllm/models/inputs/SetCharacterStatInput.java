package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("SetCharacterStatInput")
public class SetCharacterStatInput {
    private String conversationId;
    private String characterId;
    private String statName;
    private Double statValue;
    private Double statMax;
    private String statType;
    private String statCategory;
    private String textValue;
    private Boolean isVisible;
}
