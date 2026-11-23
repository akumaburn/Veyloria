package com.eonmux.chatllm.models;

import lombok.Data;
import java.util.List;

/** Personal, directional feelings. A null character endpoint represents the owning user. */
@Data
public class CharacterAffinity {
    private String affinityId;
    private String fromCharacterId;
    private String toCharacterId;
    private String fromName;
    private String toName;
    private int points;
    private String tier;
    private String updatedAt;
    private List<AffinityEvent> events = List.of();
}
