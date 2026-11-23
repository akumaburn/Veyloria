package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.CharacterAffinity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterAffinityDefaultsTest {
    private static CharacterAffinity saved(String from, String to, int points) {
        CharacterAffinity affinity = new CharacterAffinity();
        affinity.setAffinityId("saved-" + from + "-" + to);
        affinity.setFromCharacterId(from);
        affinity.setToCharacterId(to);
        affinity.setFromName(from == null ? "You" : "Maya");
        affinity.setToName(to == null ? "You" : "Maya");
        affinity.setPoints(points);
        affinity.setTier(com.eonmux.chatllm.utils.AffinityRules.tier(points));
        return affinity;
    }

    @Test void configuredStartBecomesPromptContextBeforeAnyReaction() {
        List<CharacterAffinity> affinities = new ArrayList<>();
        CharacterAffinityService.applyStartingDefaults(affinities, "maya", "Maya", 60, -20);
        assertEquals(2, affinities.size());
        String prompt = CharacterAffinityService.promptContext(affinities);
        assertTrue(prompt.contains("Maya feels love toward the user (60/100)"));
        assertTrue(prompt.contains("The user feels dislike toward Maya (-20/100)"));
    }

    @Test void neutralConfiguredStartAddsNothing() {
        List<CharacterAffinity> affinities = new ArrayList<>();
        CharacterAffinityService.applyStartingDefaults(affinities, "maya", "Maya", 0, 0);
        assertTrue(affinities.isEmpty());
        assertEquals("", CharacterAffinityService.promptContext(affinities));
    }

    @Test void savedDirectionWinsAndOnlyTheMissingDirectionUsesTheStart() {
        List<CharacterAffinity> affinities = new ArrayList<>(List.of(saved("maya", null, 100)));
        CharacterAffinityService.applyStartingDefaults(affinities, "maya", "Maya", 60, -20);
        assertEquals(2, affinities.size());
        String prompt = CharacterAffinityService.promptContext(affinities);
        assertTrue(prompt.contains("Maya feels love toward the user (100/100)"));
        assertTrue(prompt.contains("The user feels dislike toward Maya (-20/100)"));
    }

    @Test void bothSavedDirectionsSkipTheConfiguredStart() {
        List<CharacterAffinity> affinities = new ArrayList<>(
                List.of(saved("maya", null, 30), saved(null, "maya", -40)));
        CharacterAffinityService.applyStartingDefaults(affinities, "maya", "Maya", 60, -20);
        assertEquals(2, affinities.size());
        String prompt = CharacterAffinityService.promptContext(affinities);
        assertTrue(prompt.contains("Maya feels like toward the user (30/100)"));
        assertTrue(prompt.contains("The user feels dislike toward Maya (-40/100)"));
    }
}
