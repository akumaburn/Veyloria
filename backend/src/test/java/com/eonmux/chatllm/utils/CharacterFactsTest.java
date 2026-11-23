package com.eonmux.chatllm.utils;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterFactsTest {

    @Test void aProfileKeepsOnlyTheFactsTheCharacterHas() {
        JsonObject profile = CharacterFacts.profile(
                "Woman", 19, "5 ft 8 in / 173 cm", "Lean and quick", "Dark brown, braided", "Silver-gray");

        assertEquals("Woman", profile.getString("gender"));
        assertEquals(19, profile.getInteger("ageYears"));
        assertEquals("5 ft 8 in / 173 cm", profile.getString("height"));
        assertEquals("Lean and quick", profile.getString("build"));
        assertEquals("Dark brown, braided", profile.getString("hair"));
        assertEquals("Silver-gray", profile.getString("eyes"));
    }

    @Test void aProfileDropsBlankFieldsAndNegativeAges() {
        JsonObject profile = CharacterFacts.profile("Woman", -1, "  ", null, "", "Gray");

        assertEquals("Woman", profile.getString("gender"));
        assertFalse(profile.containsKey("ageYears"));
        assertFalse(profile.containsKey("height"));
        assertFalse(profile.containsKey("build"));
        assertFalse(profile.containsKey("hair"));
        assertEquals("Gray", profile.getString("eyes"));
    }

    @Test void theProfileLineIsLabeledAndOrdered() {
        String line = CharacterFacts.describeProfile(CharacterFacts.profile(
                "Woman", 19, "5 ft 8 in", "Lean", "Dark brown", "Gray"));

        assertEquals("gender Woman; age 19; height 5 ft 8 in; build Lean; hair Dark brown; eyes Gray", line);
    }

    @Test void aPartialProfileProducesNoEmptyLabels() {
        String line = CharacterFacts.describeProfile(
                new JsonObject().put("gender", "Man").put("eyes", "Hazel"));

        assertEquals("gender Man; eyes Hazel", line);
        assertEquals("", CharacterFacts.describeProfile(null));
        assertEquals("", CharacterFacts.describeProfile(new JsonObject()));
    }

    @Test void attributesArePrintedWithTheLabelsTheCardUses() {
        JsonObject scores = CharacterFacts.attributes(9, 13, 11, 16, 12, 10);

        assertEquals("STR 9; DEX 13; CON 11; INT 16; WIS 12; CHA 10",
                CharacterFacts.describeAttributes(scores));
    }

    @Test void missingAttributesProduceAnEmptyLine() {
        JsonObject scores = CharacterFacts.attributes(9, null, 11, null, 12, null);

        assertEquals("STR 9; CON 11; WIS 12", CharacterFacts.describeAttributes(scores));
        assertEquals("", CharacterFacts.describeAttributes(null));
        assertEquals("", CharacterFacts.describeAttributes(new JsonObject()));
    }

    @Test void aProfileIsNotChangedWhenTheSourceObjectIsRead() {
        JsonObject profile = CharacterFacts.profile("Woman", 19, "5 ft 8 in", "", "", "");
        String line = CharacterFacts.describeProfile(profile);

        assertTrue(line.startsWith("gender Woman; age 19; height 5 ft 8 in"));
    }
}
