package com.eonmux.chatllm.utils;

import com.eonmux.chatllm.models.CharacterAlignment;
import com.eonmux.chatllm.services.CharacterAlignmentService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AlignmentPromptTest {
    private static CharacterAlignment alignment(String id, String name, int law, int good) {
        CharacterAlignment alignment = new CharacterAlignment();
        alignment.setCharacterId(id);
        alignment.setName(name);
        alignment.setLawfulness(law);
        alignment.setGoodness(good);
        alignment.setAlignment(AlignmentRules.alignment(law, good));
        return alignment;
    }

    @Test void emptyLedgerAddsNoInstructions() {
        assertEquals("", CharacterAlignmentService.promptContext(List.of(), "maya"));
    }

    @Test void namesTheSpeakersAlignmentSeparatelyFromTheUsersAndDoesNotAssumeAgreement() {
        CharacterAlignment speaker = alignment("maya", "Maya", 70, 70);
        CharacterAlignment user = alignment(null, "You", -20, -40);
        String prompt = CharacterAlignmentService.promptContext(List.of(speaker, user), "maya");
        assertTrue(prompt.contains("Your alignment: Lawful Good (order +70/100, morality +70/100)"));
        assertTrue(prompt.contains("The user's alignment: Neutral Evil (order -20/100, morality -40/100)"));
        assertTrue(prompt.contains("Values honor"));
        assertTrue(prompt.contains("never assume the user shares an alignment"));
        assertFalse(prompt.contains("The user feels"));
    }

    @Test void describesOtherParticipantsWithoutClaimingTheirVoice() {
        CharacterAlignment speaker = alignment("maya", "Maya", 0, 0);
        CharacterAlignment other = alignment("owen", "Owen", -70, 70);
        String prompt = CharacterAlignmentService.promptContext(List.of(speaker, other), "maya");
        assertTrue(prompt.contains("Your alignment: True Neutral"));
        assertTrue(prompt.contains("Owen's alignment: Chaotic Good"));
        assertFalse(prompt.contains("The user's alignment"));
    }
}
