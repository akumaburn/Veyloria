package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.eclipse.microprofile.graphql.Name;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Name("DiceRollResult")
public class DiceRoll {
    private UUID rollId;
    private UUID conversationId;
    private UUID characterId;
    private UUID userId;
    private String expression;
    private List<Integer> results;
    private Integer total;
    @Builder.Default
    private Integer modifier = 0;
    private String purpose;
    @Builder.Default
    private Boolean isHidden = false;
    private UUID messageId;
    private String createdAt;
}
