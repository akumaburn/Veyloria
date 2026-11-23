package com.eonmux.chatllm.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AffinityRulesTest {
    @Test void tierBoundariesAreSymmetricAndIncludeNeutral() {
        int[] points = {-100, -60, -59, -20, -19, 0, 19, 20, 59, 60, 100};
        String[] tiers = {"HATE", "HATE", "DISLIKE", "DISLIKE", "NEUTRAL", "NEUTRAL", "NEUTRAL", "LIKE", "LIKE", "LOVE", "LOVE"};
        for (int i = 0; i < points.length; i++) assertEquals(tiers[i], AffinityRules.tier(points[i]));
    }
    @Test void reactionsAccumulateClampReverseAndReset() {
        assertEquals(10, AffinityRules.apply(0, "LIKE"));
        assertEquals(25, AffinityRules.apply(0, "LOVE"));
        assertEquals(-10, AffinityRules.apply(0, "DISLIKE"));
        assertEquals(-25, AffinityRules.apply(0, "HATE"));
        assertEquals(100, AffinityRules.apply(95, "LOVE"));
        assertEquals(-100, AffinityRules.apply(-95, "HATE"));
        assertEquals(75, AffinityRules.apply(100, "HATE"));
        assertEquals(0, AffinityRules.apply(-100, "RESET"));
        assertEquals(0, AffinityRules.apply(100, "RESET"));
    }
    @Test void invalidActionsIdsAndSelfRelationshipsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AffinityRules.reaction("admire"));
        assertThrows(IllegalArgumentException.class, () -> AffinityRules.actorId("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> AffinityRules.validatePair(null, null));
        String id = java.util.UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> AffinityRules.validatePair(id, id));
        assertNull(AffinityRules.actorId(null));
        assertEquals("LOVE", AffinityRules.reaction("love"));
    }

    @Test void generatedTiersNormalizeAndMapToStartingPoints() {
        assertEquals("HATE", AffinityRules.tierName("hate"));
        assertEquals("NEUTRAL", AffinityRules.tierName(null));
        assertEquals("NEUTRAL", AffinityRules.tierName(" "));
        assertThrows(IllegalArgumentException.class, () -> AffinityRules.tierName("admire"));
        assertEquals(20, AffinityRules.pointsForTier("LIKE"));
        assertEquals(60, AffinityRules.pointsForTier("love"));
        assertEquals(-20, AffinityRules.pointsForTier("Dislike"));
        assertEquals(-60, AffinityRules.pointsForTier("HATE"));
        assertEquals(0, AffinityRules.pointsForTier("NEUTRAL"));
        for (String tier : java.util.List.of("LIKE", "LOVE", "DISLIKE", "HATE")) {
            assertEquals(tier, AffinityRules.tier(AffinityRules.pointsForTier(tier)));
        }
    }
}
