package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Party {
    private UUID partyId;
    private String name;
    private UUID leaderId;
    private String description;
    @Builder.Default
    private Integer maxMembers = 6;
    @Builder.Default
    private Boolean isPublic = false;
    @Builder.Default
    private Boolean isActive = true;
    private String inviteCode;
    private String createdAt;
    private String updatedAt;

    @Builder.Default
    private List<PartyMember> members = new ArrayList<>();
}
