package com.eonmux.chatllm.models;

import lombok.*;

/**
 * A character's place in a conversation's speaker palette. Slots follow the
 * order in which characters were introduced, so they never change once given.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SpeakerColorSlot {
    private String characterId;
    private int slot;
}
