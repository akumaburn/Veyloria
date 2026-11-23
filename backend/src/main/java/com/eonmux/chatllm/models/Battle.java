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
@Name("Battle")
public class Battle {
    private UUID battleId;
    private UUID conversationId;
    @Builder.Default
    private String status = "ACTIVE";
    @Builder.Default
    private Integer roundNumber = 0;
    private String currentNarration;
    private String actionOptions;
    private String battleData;
    private String createdAt;
    private String updatedAt;
}
