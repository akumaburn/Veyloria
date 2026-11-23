package com.eonmux.chatllm.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActorRulesTest {
    @Test void canonicalUuidsAndNullPassThroughUnchanged() {
        String id = java.util.UUID.randomUUID().toString();
        assertEquals(id, ActorRules.characterId(id));
        assertNull(ActorRules.characterId(null));
    }

    @Test void caseVariantsCanonicalizeAndOtherSpellingsAreRejected() {
        String id = java.util.UUID.randomUUID().toString();
        // Case differences normalize to the canonical lowercase form, so one
        // character cannot hold two relationship ledgers.
        assertEquals(id, ActorRules.characterId(id.toUpperCase(java.util.Locale.ROOT)));
        // Spellings that differ beyond case are refused outright.
        assertThrows(IllegalArgumentException.class, () -> ActorRules.characterId("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> ActorRules.characterId(""));
        assertThrows(IllegalArgumentException.class, () -> ActorRules.characterId("{" + id + "}"));
        assertThrows(IllegalArgumentException.class, () -> ActorRules.characterId(" " + id));
    }
}