package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** How a generated character feels toward another member of the same roster. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CharacterDraftRelationship {
    private String targetName;
    private String tier;
    private String reason;
}
