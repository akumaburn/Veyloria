package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RelationshipActor {
    private String characterId;
    private String name;
    /** How this character feels toward the user before any recorded reaction. */
    private int initialAffinityToUser;
    /** How the user feels toward this character before any recorded reaction. */
    private int initialAffinityFromUser;

    public RelationshipActor(String characterId, String name) {
        this(characterId, name, 0, 0);
    }
}
