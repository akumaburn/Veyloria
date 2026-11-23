package com.eonmux.chatllm.utils;

import com.eonmux.chatllm.models.CharacterAffinity;
import com.eonmux.chatllm.services.CharacterAffinityService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AffinityPromptTest {
    @Test void emptyLedgerAddsNoInstructions() {
        assertEquals("", CharacterAffinityService.promptContext(List.of()));
    }
    @Test void distinguishesFeelingsInBothDirectionsAndDoesNotAssumeReciprocation() {
        CharacterAffinity a = new CharacterAffinity();
        a.setToCharacterId("maya"); a.setToName("Maya"); a.setPoints(70); a.setTier("LOVE");
        CharacterAffinity b = new CharacterAffinity();
        b.setFromCharacterId("maya"); b.setFromName("Maya"); b.setPoints(-30); b.setTier("DISLIKE");
        String prompt = CharacterAffinityService.promptContext(List.of(a, b));
        assertTrue(prompt.contains("The user feels love toward Maya (70/100)"));
        assertTrue(prompt.contains("Maya feels dislike toward the user (-30/100)"));
        assertTrue(prompt.contains("do not imply reciprocation"));
        assertTrue(prompt.contains("never assume romance or consent"));
    }
}
