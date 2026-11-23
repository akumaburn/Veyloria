package com.eonmux.chatllm.utils;

import java.util.UUID;

/** Shared identity rules for actors in personal relationship records. */
public final class ActorRules {
    private ActorRules() {}

    /** Returns null for the owning user, or the canonical UUID for a character. */
    public static String characterId(String value) {
        if (value == null) return null;
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Choose a valid character.");
        }
    }
}
