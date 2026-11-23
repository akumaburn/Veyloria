package com.eonmux.chatllm.map.generation;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Procedural name generator using culture-specific phoneme tables and four naming strategies:
 * <ol>
 *   <li>Root+Suffix (40%) — syllable-built names from culture phonemes</li>
 *   <li>Compound (25%) — two root words joined (e.g. "Stormhold")</li>
 *   <li>The+Descriptor (20%) — "The" + adjective + noun (e.g. "The Sunken Halls")</li>
 *   <li>Possessive (15%) — generated name + "'s" + landmark (e.g. "Borin's Crossing")</li>
 * </ol>
 * Uniqueness is guaranteed per instance via a HashSet with retry logic.
 */
public class NameGenerator {

    private static final int MAX_RETRIES = 10;

    private final Set<String> usedNames = new HashSet<>();

    /**
     * Generates a unique name for a location using the given culture and location type.
     *
     * @param culture the cultural archetype governing phonemes and word pools
     * @param type    the location type (influences suffix selection)
     * @param rng     seeded random for deterministic generation
     * @return a unique procedurally generated name
     */
    public String generate(Culture culture, LocationType type, Random rng) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String name = generateCandidate(culture, type, rng);
            if (usedNames.add(name)) {
                return name;
            }
        }
        // Fallback: append numeral for uniqueness
        String base = generateCandidate(culture, type, rng);
        for (int i = 2; i < 100; i++) {
            String numbered = base + " " + toRomanNumeral(i);
            if (usedNames.add(numbered)) {
                return numbered;
            }
        }
        usedNames.add(base);
        return base;
    }

    /** Register a name as already used (prevents duplicates with pre-existing locations). */
    public void registerExisting(String name) {
        if (name != null) {
            usedNames.add(name);
        }
    }

    /** Resets the used names tracker. Call between map generations. */
    public void reset() {
        usedNames.clear();
    }

    private String generateCandidate(Culture culture, LocationType type, Random rng) {
        float roll = rng.nextFloat();
        if (roll < 0.40f) {
            return generateSyllabic(culture, type, rng);
        } else if (roll < 0.65f) {
            return generateCompound(culture, rng);
        } else if (roll < 0.85f) {
            return generateDescriptive(culture, rng);
        } else {
            return generatePossessive(culture, rng);
        }
    }

    /**
     * Strategy 1: Build a name from culture phonemes (onset + nucleus + coda).
     * 2-3 syllables, capitalized.
     */
    private String generateSyllabic(Culture culture, LocationType type, Random rng) {
        int syllableCount = 2 + (rng.nextFloat() < 0.35f ? 1 : 0);
        StringBuilder name = new StringBuilder();

        for (int i = 0; i < syllableCount; i++) {
            boolean isFirst = (i == 0);
            boolean isLast = (i == syllableCount - 1);

            // First syllable: 30% chance to start with vowel (skip onset)
            if (!isFirst || rng.nextFloat() > 0.30f) {
                name.append(pick(culture.getOnsets(), rng));
            }
            name.append(pick(culture.getNuclei(), rng));

            // Last syllable always gets coda; middle syllables 30% chance
            if (isLast || rng.nextFloat() < 0.30f) {
                name.append(pick(culture.getCodas(), rng));
            }
        }

        String result = capitalize(name.toString());

        // Optionally append a type-flavored suffix for settlements
        if (rng.nextFloat() < 0.25f) {
            String suffix = getTypeSuffix(type, rng);
            if (suffix != null) {
                result = result + suffix;
            }
        }

        return result;
    }

    /**
     * Strategy 2: Join two root words from the culture's word pools.
     * e.g. "Stormhold", "Silverbrook"
     */
    private String generateCompound(Culture culture, Random rng) {
        String prefix = pick(culture.getPrefixRoots(), rng);
        String suffix = pick(culture.getSuffixRoots(), rng);
        // Avoid self-duplication like "Forgeforge"
        if (prefix.equalsIgnoreCase(suffix)) {
            suffix = pick(culture.getSuffixRoots(), rng);
        }
        return prefix + suffix.toLowerCase();
    }

    /**
     * Strategy 3: "The" + adjective + noun.
     * e.g. "The Frozen Throne", "The Whispering Glade"
     */
    private String generateDescriptive(Culture culture, Random rng) {
        String adj = pick(culture.getAdjectives(), rng);
        String noun = pick(culture.getNouns(), rng);
        return "The " + capitalize(adj) + " " + noun;
    }

    /**
     * Strategy 4: Short generated name + "'s" + landmark.
     * e.g. "Borin's Crossing", "Thalia's Rest"
     */
    private String generatePossessive(Culture culture, Random rng) {
        // Generate a short 1-2 syllable personal name
        StringBuilder personalName = new StringBuilder();
        int syllables = 1 + (rng.nextFloat() < 0.6f ? 1 : 0);
        for (int i = 0; i < syllables; i++) {
            if (i == 0 && rng.nextFloat() < 0.25f) {
                // vowel start
                personalName.append(pick(culture.getNuclei(), rng));
            } else {
                personalName.append(pick(culture.getOnsets(), rng));
                personalName.append(pick(culture.getNuclei(), rng));
            }
            if (i == syllables - 1) {
                personalName.append(pick(culture.getCodas(), rng));
            }
        }

        String person = capitalize(personalName.toString());
        String landmark = pick(culture.getLandmarks(), rng);

        // Handle names ending in 's' — use just apostrophe
        if (person.endsWith("s")) {
            return person + "' " + landmark;
        }
        return person + "'s " + landmark;
    }

    private String getTypeSuffix(LocationType type, Random rng) {
        return switch (type.id()) {
            case "town", "city", "village" -> pick(new String[]{"ton", "bury", "heim", "stead"}, rng);
            case "fortress", "castle", "keep" -> pick(new String[]{"guard", "wall", "helm"}, rng);
            case "cave", "mine", "dungeon" -> pick(new String[]{"deep", "maw", "pit"}, rng);
            default -> null;
        };
    }

    private static String pick(String[] array, Random rng) {
        return array[rng.nextInt(array.length)];
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String toRomanNumeral(int n) {
        return switch (n) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            default -> String.valueOf(n);
        };
    }
}
