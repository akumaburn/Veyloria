package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("InitiateBattleInput")
public class InitiateBattleInput {
    private String conversationId;
    private String enemyName;
    private String enemyDescription;
    private Double enemyHp;
    private String narrativeContext;
}
