package com.eonmux.chatllm.utils;

import java.util.Locale;

/**
 * Two independent alignment axes. Order runs from lawful (+100) through
 * neutral (0) to chaotic (-100). Morality runs from good (+100) through
 * neutral (0) to evil (-100). The nine named alignments are derived bands,
 * not stored values, so small point changes move a character between them.
 */
public final class AlignmentRules {
    private AlignmentRules() {}

    /** Half-width of the neutral band. Points at or beyond it occupy a named band. */
    public static final int BAND = 34;

    /** Canonical alignment names in chart order. */
    public static final java.util.List<String> NAMES = java.util.List.of(
            "LAWFUL_GOOD", "NEUTRAL_GOOD", "CHAOTIC_GOOD",
            "LAWFUL_NEUTRAL", "TRUE_NEUTRAL", "CHAOTIC_NEUTRAL",
            "LAWFUL_EVIL", "NEUTRAL_EVIL", "CHAOTIC_EVIL");

    /** Accepts "Lawful Good", "lawful-good", or "LAWFUL_GOOD"; null when unrecognized. */
    public static String named(String value) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return NAMES.contains(normalized) ? normalized : null;
    }

    /** Representative points for a named alignment, used by generated characters. */
    public static int[] pointsFor(String alignment) {
        String name = named(alignment);
        if (name == null) name = "TRUE_NEUTRAL";
        int order = name.startsWith("LAWFUL") ? 70 : name.startsWith("CHAOTIC") ? -70 : 0;
        int moral = name.endsWith("_GOOD") ? 70 : name.endsWith("_EVIL") ? -70 : 0;
        return new int[]{order, moral};
    }

    public static int clamp(int points) { return Math.max(-100, Math.min(100, points)); }

    public static void validatePoints(int points) {
        if (points < -100 || points > 100)
            throw new IllegalArgumentException("Alignment points must be between -100 and 100.");
    }

    public static void validateDelta(int delta) {
        if (delta < -100 || delta > 100)
            throw new IllegalArgumentException("Choose a change of at most 100 points.");
    }

    public static String order(int points) {
        if (points >= BAND) return "LAWFUL";
        if (points <= -BAND) return "CHAOTIC";
        return "NEUTRAL";
    }

    public static String moral(int points) {
        if (points >= BAND) return "GOOD";
        if (points <= -BAND) return "EVIL";
        return "NEUTRAL";
    }

    public static String alignment(int lawfulness, int goodness) {
        String order = order(lawfulness), moral = moral(goodness);
        if (order.equals("NEUTRAL") && moral.equals("NEUTRAL")) return "TRUE_NEUTRAL";
        if (order.equals("NEUTRAL")) return "NEUTRAL_" + moral;
        if (moral.equals("NEUTRAL")) return order + "_NEUTRAL";
        return order + "_" + moral;
    }

    public static String label(String alignment) {
        return switch (alignment == null ? "" : alignment) {
            case "LAWFUL_GOOD" -> "Lawful Good";
            case "NEUTRAL_GOOD" -> "Neutral Good";
            case "CHAOTIC_GOOD" -> "Chaotic Good";
            case "LAWFUL_NEUTRAL" -> "Lawful Neutral";
            case "TRUE_NEUTRAL" -> "True Neutral";
            case "CHAOTIC_NEUTRAL" -> "Chaotic Neutral";
            case "LAWFUL_EVIL" -> "Lawful Evil";
            case "NEUTRAL_EVIL" -> "Neutral Evil";
            case "CHAOTIC_EVIL" -> "Chaotic Evil";
            default -> "Uncharted";
        };
    }

    public static String describe(String alignment) {
        return switch (alignment == null ? "" : alignment) {
            case "LAWFUL_GOOD" -> "values honor, justice, and the protection of others within a just code";
            case "NEUTRAL_GOOD" -> "follows conscience and compassion without binding to law or custom";
            case "CHAOTIC_GOOD" -> "follows conscience freely and resists rules that harm people";
            case "LAWFUL_NEUTRAL" -> "values order, duty, and predictable rules without a strong moral pull";
            case "TRUE_NEUTRAL" -> "weighs each situation without a fixed moral or legal commitment";
            case "CHAOTIC_NEUTRAL" -> "values personal freedom and impulse over rules or causes";
            case "LAWFUL_EVIL" -> "pursues self-interest within the letter of a code or law";
            case "NEUTRAL_EVIL" -> "pursues self-interest without regard for others or rules";
            case "CHAOTIC_EVIL" -> "acts on cruelty, rage, or destruction without restraint";
            default -> "has no settled moral alignment";
        };
    }

    /** Applies a delta and clamps each axis independently. */
    public static int[] apply(int lawfulness, int goodness, int lawDelta, int goodDelta) {
        validatePoints(lawfulness);
        validatePoints(goodness);
        validateDelta(lawDelta);
        validateDelta(goodDelta);
        return new int[]{clamp(lawfulness + lawDelta), clamp(goodness + goodDelta)};
    }

    public static String action(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("SET") && !normalized.equals("ADJUST"))
            throw new IllegalArgumentException("Choose a supported alignment change.");
        return normalized;
    }
}
