package com.eonmux.chatllm.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterProfilePromptTest {

    @Test void thePromptCarriesTheProfileAndBaseAttributes() {
        Character character = Character.builder()
                .name("Elara Vane")
                .systemPrompt("You are Elara Vane.")
                .gender("Woman")
                .pronouns("she/her")
                .ageYears(19)
                .height("5 ft 8 in / 173 cm")
                .build("Lean and quick")
                .hair("Dark brown, braided")
                .eyes("Silver-gray")
                .strength(9)
                .dexterity(13)
                .constitution(11)
                .intelligence(16)
                .wisdom(12)
                .charisma(10)
                .build();

        String prompt = character.getFullSystemPrompt();

        assertTrue(prompt.contains("=== YOUR PHYSICAL PROFILE ==="));
        assertTrue(prompt.contains("Gender: Woman"));
        assertTrue(prompt.contains("Pronouns: she/her"));
        assertTrue(prompt.contains("Age: 19"));
        assertTrue(prompt.contains("Height: 5 ft 8 in / 173 cm"));
        assertTrue(prompt.contains("Eyes: Silver-gray"));
        assertTrue(prompt.contains("=== YOUR BASE ATTRIBUTES ==="));
        assertTrue(prompt.contains("They use a 1-20 scale, where 10 is average."));
        assertTrue(prompt.contains("Strength: 9"));
        assertTrue(prompt.contains("Intelligence: 16"));
    }

    @Test void aCharacterWithoutProfileFactsAddsNoEmptySection() {
        Character character = Character.builder()
                .name("Assistant")
                .systemPrompt("You are a helpful assistant.")
                .build();

        String prompt = character.getFullSystemPrompt();

        assertFalse(prompt.contains("=== YOUR PHYSICAL PROFILE ==="));
        assertFalse(prompt.contains("=== YOUR BASE ATTRIBUTES ==="));
    }
}
