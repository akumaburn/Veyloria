package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Name("BattleVote")
public class BattleVote {
    private UUID id;
    private UUID battleId;
    private Integer roundNumber;
    private UUID userId;
    private Integer optionIndex;
    private String createdAt;
}
