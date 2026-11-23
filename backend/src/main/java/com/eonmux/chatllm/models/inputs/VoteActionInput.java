package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("VoteActionInput")
public class VoteActionInput {
    private String battleId;
    private int optionIndex;
    private Integer roundNumber;
}
