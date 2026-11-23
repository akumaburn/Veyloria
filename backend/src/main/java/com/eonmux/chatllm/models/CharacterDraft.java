package com.eonmux.chatllm.models;

import lombok.Data;

import java.util.List;

/**
 * An unsaved character plus the alignment and feelings generated with it.
 * The character row keeps only narrative fields; alignment and affinity are
 * written to their own tables when the draft is saved.
 */
@Data
public class CharacterDraft {
    private Character character;
    private int lawfulness;
    private int goodness;
    private String alignment;
    private List<CharacterDraftRelationship> relationships = List.of();
}
