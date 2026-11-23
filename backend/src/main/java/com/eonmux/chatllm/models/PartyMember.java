package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyMember {
    private UUID id;
    private UUID partyId;
    private UUID userId;
    private UUID characterId;
    @Builder.Default
    private String role = "member";
    private String joinedAt;

    public boolean isLeader() {
        return "leader".equals(role);
    }
}
