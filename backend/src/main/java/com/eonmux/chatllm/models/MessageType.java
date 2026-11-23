package com.eonmux.chatllm.models;

/**
 * Enum representing the type of a roleplay message.
 */
public enum MessageType {
    DIALOGUE,
    NARRATION,
    ACTION,
    OOC,
    SYSTEM,
    DICE;

    public String toLowerCaseString() {
        return this.name().toLowerCase();
    }

    public static MessageType fromString(String type) {
        if (type == null) return DIALOGUE;

        try {
            return valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DIALOGUE;
        }
    }
}
