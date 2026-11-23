package com.eonmux.chatllm.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a character's pronouns from the canonical identity line.
 *
 * The seeded roster carries lines such as {@code pronouns she/her} in the
 * system prompt. Prompts that name other characters need that value so the
 * model does not guess a gender when it writes about them.
 */
public final class PronounHelper {

    private static final Pattern PRONOUNS =
            Pattern.compile("(?i)pronouns?\\s+([a-z]+/[a-z]+)");

    private PronounHelper() {
    }

    public static String fromIdentity(String identity) {
        if (identity == null || identity.isBlank()) return null;
        Matcher matcher = PRONOUNS.matcher(identity);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * The stored pronoun field is authoritative. The canonical identity line is
     * the fallback for characters created before the field existed.
     */
    public static String resolve(String pronouns, String identity) {
        if (pronouns != null) {
            String stored = pronouns.strip().toLowerCase(Locale.ROOT);
            if (!stored.isEmpty()) return stored;
        }
        return fromIdentity(identity);
    }
}
