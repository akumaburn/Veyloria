package com.eonmux.chatllm.utils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative personal-name casing: preserve handles and intentional mixed case. */
public final class DisplayNameFormatter {
    private static final Pattern SPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern HANDLE = Pattern.compile("[_@\\p{N}]|\\p{L}{2,}\\.\\p{L}{2,}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:['’\\-‐‑][\\p{L}\\p{M}]+)*");
    private static final Pattern PART = Pattern.compile("[\\p{L}\\p{M}]+");
    private static final Set<String> PARTICLES = Set.of(
            "al", "bin", "ibn", "da", "das", "de", "del", "della", "den", "der", "di", "do", "dos", "du", "la", "le", "van", "von");

    private DisplayNameFormatter() {}

    public static String normalize(String name) {
        if (name == null) return null;
        String clean = SPACE.matcher(name).replaceAll(" ").strip();
        if (clean.isEmpty() || HANDLE.matcher(clean).find()) return clean;
        Matcher words = WORD.matcher(clean);
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (words.find()) {
            String word = words.group();
            boolean upper = word.codePoints().anyMatch(c -> Character.isUpperCase(c) || Character.isTitleCase(c));
            boolean lower = word.codePoints().anyMatch(Character::isLowerCase);
            String replacement = word;
            if (!(upper && lower)) {
                String lowered = word.toLowerCase(Locale.ROOT);
                if (index > 0 && PARTICLES.contains(lowered)) {
                    replacement = lowered;
                } else if (upper && (word.codePointCount(0, word.length()) <= 2
                        || (index > 0 && word.matches("[IVXLCDM]{1,8}")))) {
                    // Existing initials and Roman suffixes carry information we cannot infer.
                    replacement = word;
                } else {
                    Matcher parts = PART.matcher(lowered);
                    StringBuilder compound = new StringBuilder();
                    while (parts.find()) parts.appendReplacement(compound, Matcher.quoteReplacement(capitalize(parts.group())));
                    parts.appendTail(compound);
                    replacement = compound.toString();
                }
            }
            words.appendReplacement(result, Matcher.quoteReplacement(replacement));
            index++;
        }
        words.appendTail(result);
        return result.toString();
    }

    private static String capitalize(String word) {
        if (word.startsWith("mc") && word.length() > 4) return "Mc" + capitalize(word.substring(2));
        int firstLength = Character.charCount(word.codePointAt(0));
        return word.substring(0, firstLength).toUpperCase(Locale.ROOT) + word.substring(firstLength);
    }
}
