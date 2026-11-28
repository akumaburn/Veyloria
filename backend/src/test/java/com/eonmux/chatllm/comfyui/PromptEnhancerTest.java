package com.eonmux.chatllm.comfyui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt-identity tests: the per-character fragment must make two distinct
 * characters produce distinct prompts, which is what keeps ComfyUI's
 * execution cache from serving one rendered sheet to the whole roster.
 */
class PromptEnhancerTest {

    private PromptEnhancer.CharacterProfile profile(String name, String hair, String eyes,
                                                    String gender, Integer age, String build,
                                                    String description) {
        PromptEnhancer.CharacterProfile p = new PromptEnhancer.CharacterProfile();
        p.name = name;
        p.hair = hair;
        p.eyes = eyes;
        p.gender = gender;
        p.ageYears = age;
        p.build = build;
        p.description = description;
        return p;
    }

    @Test
    void distinctCharactersYieldDistinctFragments() {
        PromptEnhancer.CharacterProfile viper =
            profile("Viper", "black hair", "ice-blue eyes", "female", 34, "lean",
                "Corporate Assassin. A silent killer in the employ of Zenith Corp.");
        PromptEnhancer.CharacterProfile jinx =
            profile("Jinx", "pink hair", "green eyes", "female", 28, "athletic",
                "Rail-yard demolition crew chief with a taste for controlled burns.");

        String a = PromptEnhancer.identityFragment(viper);
        String b = PromptEnhancer.identityFragment(jinx);
        assertNotEquals(a, b, "two roster members must not share an identity fragment");
        assertTrue(a.contains("Viper"));
        assertTrue(b.contains("Jinx"));
        assertTrue(a.contains("black hair"));
        assertTrue(b.contains("green eyes"));
    }

    @Test
    void skipsNullFields() {
        PromptEnhancer.CharacterProfile minimal =
            profile("Ezra", null, null, null, null, null, "Quiet dockhand.");
        String frag = PromptEnhancer.identityFragment(minimal);
        assertTrue(frag.contains("Ezra"));
        assertTrue(frag.contains("Quiet dockhand"));
        assertFalse(frag.contains("null"));
        assertFalse(frag.contains(", ,"));
    }

    @Test
    void nameOnlyWhenNoOtherFields() {
        PromptEnhancer.CharacterProfile nameOnly = profile("Lyra", null, null, null, null, null, null);
        assertEquals("Lyra", PromptEnhancer.identityFragment(nameOnly));
    }

    @Test
    void ageRenderedAsYearsOld() {
        PromptEnhancer.CharacterProfile aged = profile("Mara", null, null, null, 41, null, null);
        assertTrue(PromptEnhancer.identityFragment(aged).contains("41 years old"));
    }

    @Test
    void longDescriptionIsTruncated() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) big.append("very long descriptive phrase number ").append(i).append(" ");
        PromptEnhancer.CharacterProfile longDesc =
            profile("Tessa", null, null, null, null, null, big.toString());
        String frag = PromptEnhancer.identityFragment(longDesc);
        assertTrue(frag.length() < 400, "identity fragment stays bounded: " + frag.length());
    }
}
