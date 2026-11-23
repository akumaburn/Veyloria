package com.eonmux.chatllm.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlignmentRulesTest {
    @Test void bandsIncludeTheNeutralRangeAndMatchTheChart() {
        assertEquals("CHAOTIC", AlignmentRules.order(-100));
        assertEquals("CHAOTIC", AlignmentRules.order(-34));
        assertEquals("NEUTRAL", AlignmentRules.order(-33));
        assertEquals("NEUTRAL", AlignmentRules.order(33));
        assertEquals("LAWFUL", AlignmentRules.order(34));
        assertEquals("LAWFUL", AlignmentRules.order(100));
        assertEquals("EVIL", AlignmentRules.moral(-100));
        assertEquals("EVIL", AlignmentRules.moral(-34));
        assertEquals("NEUTRAL", AlignmentRules.moral(-33));
        assertEquals("NEUTRAL", AlignmentRules.moral(33));
        assertEquals("GOOD", AlignmentRules.moral(34));
        assertEquals("GOOD", AlignmentRules.moral(100));
    }

    @Test void allNineAlignmentsAreReachable() {
        assertEquals("LAWFUL_GOOD", AlignmentRules.alignment(34, 34));
        assertEquals("NEUTRAL_GOOD", AlignmentRules.alignment(0, 34));
        assertEquals("CHAOTIC_GOOD", AlignmentRules.alignment(-34, 34));
        assertEquals("LAWFUL_NEUTRAL", AlignmentRules.alignment(34, 0));
        assertEquals("TRUE_NEUTRAL", AlignmentRules.alignment(0, 0));
        assertEquals("CHAOTIC_NEUTRAL", AlignmentRules.alignment(-34, 0));
        assertEquals("LAWFUL_EVIL", AlignmentRules.alignment(34, -34));
        assertEquals("NEUTRAL_EVIL", AlignmentRules.alignment(0, -34));
        assertEquals("CHAOTIC_EVIL", AlignmentRules.alignment(-34, -34));
    }

    @Test void deltasClampEachAxisIndependently() {
        assertArrayEquals(new int[]{55, 45}, AlignmentRules.apply(45, 30, 10, 15));
        assertArrayEquals(new int[]{100, -100}, AlignmentRules.apply(98, -98, 10, -10));
        assertArrayEquals(new int[]{-100, 100}, AlignmentRules.apply(-98, 98, -10, 10));
    }

    @Test void invalidPointsDeltasAndIdentitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AlignmentRules.validatePoints(101));
        assertThrows(IllegalArgumentException.class, () -> AlignmentRules.validatePoints(-101));
        assertThrows(IllegalArgumentException.class, () -> AlignmentRules.validateDelta(101));
        assertThrows(IllegalArgumentException.class, () -> AlignmentRules.action("MOVE"));
        assertThrows(IllegalArgumentException.class, () -> ActorRules.characterId("not-a-uuid"));
        assertNull(ActorRules.characterId(null));
        assertEquals("SET", AlignmentRules.action(" set "));
        assertEquals("ADJUST", AlignmentRules.action("adjust"));
    }

    @Test void labelsAndDescriptionsCoverEveryAlignment() {
        String[] names = {"LAWFUL_GOOD", "NEUTRAL_GOOD", "CHAOTIC_GOOD", "LAWFUL_NEUTRAL",
                "TRUE_NEUTRAL", "CHAOTIC_NEUTRAL", "LAWFUL_EVIL", "NEUTRAL_EVIL", "CHAOTIC_EVIL"};
        for (String name : names) {
            assertFalse(AlignmentRules.label(name).isBlank());
            assertFalse(AlignmentRules.describe(name).isBlank());
        }
        assertEquals("Lawful Good", AlignmentRules.label("LAWFUL_GOOD"));
        assertEquals("True Neutral", AlignmentRules.label("TRUE_NEUTRAL"));
        assertEquals("Uncharted", AlignmentRules.label("unknown"));
    }

    @Test void generatedNamesNormalizeToCanonicalAlignmentsAndAnchors() {
        assertEquals("LAWFUL_GOOD", AlignmentRules.named("Lawful Good"));
        assertEquals("CHAOTIC_EVIL", AlignmentRules.named("chaotic-evil"));
        assertEquals("TRUE_NEUTRAL", AlignmentRules.named("TRUE_NEUTRAL"));
        assertNull(AlignmentRules.named("Usually Nice"));

        for (String name : AlignmentRules.NAMES) {
            int[] points = AlignmentRules.pointsFor(name);
            assertEquals(name, AlignmentRules.alignment(points[0], points[1]),
                    name + " must resolve back to its own band");
        }
        assertArrayEquals(new int[]{0, 0}, AlignmentRules.pointsFor("something else"));
    }
}
