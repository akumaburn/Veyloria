package com.eonmux.chatllm.models;

import lombok.Data;
import java.util.List;

/** A persistent moral and legal position. A null character endpoint is the owning user. */
@Data
public class CharacterAlignment {
    private String alignmentId;
    private String characterId;
    private String name;
    private int lawfulness;
    private int goodness;
    private String alignment;
    private String updatedAt;
    private List<AlignmentEvent> events = List.of();
}
