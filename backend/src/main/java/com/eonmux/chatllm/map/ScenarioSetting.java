package com.eonmux.chatllm.map;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the story text for the kind of place the opening scene needs.
 *
 * <p>The world generator places many location types. Choosing a random
 * settlement for a story about a dungeon puts the opening in a town, and the
 * local layout then describes a town. This class keeps the opening node and
 * the story on the same footing.</p>
 */
public final class ScenarioSetting {
    private record Hint(String word, List<String> types) {
    }

    private static final List<Hint> HINTS = List.of(
            new Hint("academy", List.of("city", "town", "village")),
            new Hint("school", List.of("city", "town", "village")),
            new Hint("dungeon", List.of("dungeon", "cave", "mine", "ruins")),
            new Hint("crypt", List.of("dungeon", "ruins", "temple")),
            new Hint("cave", List.of("cave", "mine", "dungeon")),
            new Hint("mine", List.of("mine", "cave", "dungeon")),
            new Hint("castle", List.of("castle", "keep", "fortress", "tower")),
            new Hint("palace", List.of("castle", "city", "keep")),
            new Hint("fortress", List.of("fortress", "keep", "castle")),
            new Hint("tower", List.of("tower", "castle", "keep")),
            new Hint("temple", List.of("temple", "shrine", "ruins")),
            new Hint("shrine", List.of("shrine", "temple", "ruins")),
            new Hint("monastery", List.of("temple", "shrine", "ruins")),
            new Hint("port", List.of("port", "city", "town")),
            new Hint("harbor", List.of("port", "city", "town")),
            new Hint("dock", List.of("port", "city", "town")),
            new Hint("forest", List.of("forest", "grove", "camp")),
            new Hint("wood", List.of("forest", "grove", "camp")),
            new Hint("mountain", List.of("mountain", "cave", "camp")),
            new Hint("ruin", List.of("ruins", "dungeon", "temple")),
            new Hint("camp", List.of("camp", "forest", "village")),
            new Hint("village", List.of("village", "town", "camp")),
            new Hint("city", List.of("city", "town", "port")),
            new Hint("town", List.of("town", "village", "city")),
            new Hint("market", List.of("town", "city", "village")),
            new Hint("tavern", List.of("town", "village", "city")),
            new Hint("swamp", List.of("swamp", "forest", "ruins")),
            new Hint("desert", List.of("oasis", "ruins", "camp")),
            new Hint("oasis", List.of("oasis", "camp", "ruins")),
            new Hint("lake", List.of("lake", "river_crossing", "camp")),
            new Hint("river", List.of("river_crossing", "lake", "town"))
    );

    private ScenarioSetting() {
    }

    /**
     * Preferred world-map location types, most specific first. An empty list
     * means the generator keeps its normal settlement preference.
     */
    public static List<String> preferredStartingTypes(String scenario) {
        if (scenario == null || scenario.isBlank()) return List.of();
        String text = " " + scenario.toLowerCase(Locale.ROOT) + " ";
        Set<String> types = new LinkedHashSet<>();
        for (Hint hint : HINTS) {
            if (containsWord(text, hint.word())) types.addAll(hint.types());
        }
        return List.copyOf(types);
    }

    /**
     * The story text contains the hint as a whole word, so "portent" does not
     * count as "port" and "dungeon" does not match inside a longer name.
     */
    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) return false;
            boolean left = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + word.length();
            boolean right = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (left && right) return true;
            from = at + 1;
        }
    }

    /** A human-readable summary used in logs and tests. */
    public static String describe(String scenario) {
        List<String> types = preferredStartingTypes(scenario);
        return types.isEmpty() ? "settlement" : String.join(", ", new ArrayList<>(types));
    }
}
