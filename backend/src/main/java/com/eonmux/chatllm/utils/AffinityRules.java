package com.eonmux.chatllm.utils;

import java.util.Locale;

public final class AffinityRules {
    private AffinityRules() {}

    public static String tier(int points) {
        if (points <= -60) return "HATE";
        if (points <= -20) return "DISLIKE";
        if (points >= 60) return "LOVE";
        if (points >= 20) return "LIKE";
        return "NEUTRAL";
    }
    public static void validatePoints(int points) {
        if (points < -100 || points > 100)
            throw new IllegalArgumentException("Feeling points must be between -100 and 100.");
    }
    public static int apply(int points, String reaction) {
        return switch (reaction) {
            case "LIKE" -> Math.min(100, points + 10);
            case "LOVE" -> Math.min(100, points + 25);
            case "DISLIKE" -> Math.max(-100, points - 10);
            case "HATE" -> Math.max(-100, points - 25);
            case "RESET" -> 0;
            default -> throw new IllegalArgumentException("Choose Like, Love, Dislike, Hate, or Reset.");
        };
    }
    public static String actorId(String value) {
        return ActorRules.characterId(value);
    }
    public static String reaction(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        apply(0, normalized);
        return normalized;
    }
    /** Normalizes a stored feeling name. Null or blank resolves to neutral. */
    public static String tierName(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return "NEUTRAL";
        if (!java.util.Set.of("NEUTRAL", "LIKE", "LOVE", "DISLIKE", "HATE").contains(normalized))
            throw new IllegalArgumentException("Choose a supported feeling.");
        return normalized;
    }
    /** Points a generated tier stores as its starting value. */
    public static int pointsForTier(String tier) {
        return switch (tierName(tier)) {
            case "LIKE" -> 20;
            case "LOVE" -> 60;
            case "DISLIKE" -> -20;
            case "HATE" -> -60;
            default -> 0;
        };
    }
    public static void validatePair(String from, String to) {
        if (java.util.Objects.equals(from, to))
            throw new IllegalArgumentException("Choose two different participants.");
    }
}
