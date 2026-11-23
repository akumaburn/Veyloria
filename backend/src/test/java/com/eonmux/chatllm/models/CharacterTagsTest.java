package com.eonmux.chatllm.models;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterTagsTest {

    @Test void readsTheJsonArrayTheWritersStore() {
        Character character = new Character();
        character.setTagsFromString("[\"roster\",\"diver\"]");

        assertEquals(List.of("roster", "diver"), character.getTags());
    }

    @Test void readsLegacyCommaSeparatedTags() {
        Character character = new Character();
        character.setTagsFromString("roster, diver");

        assertEquals(List.of("roster", "diver"), character.getTags());
    }

    @Test void aMalformedJsonValueFallsBackToPlainText() {
        Character character = new Character();
        character.setTagsFromString("[not json");

        assertEquals(List.of("[not json"), character.getTags());
    }

    @Test void emptyTagsBecomeAnEmptyMutableList() {
        Character character = new Character();
        character.setTagsFromString("[\"roster\"]");
        character.setTagsFromString(null);

        assertEquals(List.of(), character.getTags());
        character.addTag("roster");
        assertEquals(List.of("roster"), character.getTags());
    }
}
