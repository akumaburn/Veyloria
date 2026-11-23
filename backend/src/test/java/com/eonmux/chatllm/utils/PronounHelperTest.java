package com.eonmux.chatllm.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PronounHelperTest {

    @Test void readsPronounsFromTheCanonicalIdentity() {
        String identity = "Canonical identity: Rin Neko; Nekorian Mage and Student at "
                + "The Royal Academy; age 19; pronouns she/her. Keep identity and "
                + "chronology consistent with the roster.";

        assertEquals("she/her", PronounHelper.fromIdentity(identity));
    }

    @Test void readsNonBinaryPronouns() {
        String identity = "Canonical identity: Vexis; Dungeon Entity; age 1000; "
                + "pronouns it/it. Keep identity consistent.";

        assertEquals("it/it", PronounHelper.fromIdentity(identity));
    }

    @Test void returnsNullWhenNoPronounsAreListed() {
        assertNull(PronounHelper.fromIdentity("You are a mysterious figure."));
        assertNull(PronounHelper.fromIdentity(null));
    }

    @Test void theStoredFieldWinsOverTheIdentityText() {
        String identity = "Canonical identity: Rin Neko; Nekorian Mage; age 19; "
                + "pronouns she/her. Keep identity consistent.";

        assertEquals("they/them", PronounHelper.resolve(" They/Them ", identity));
    }

    @Test void theIdentityTextIsTheFallbackForOldCharacters() {
        String identity = "Canonical identity: Vexis; Dungeon Entity; age 1000; "
                + "pronouns it/it. Keep identity consistent.";

        assertEquals("it/it", PronounHelper.resolve(null, identity));
        assertEquals("it/it", PronounHelper.resolve("  ", identity));
        assertNull(PronounHelper.resolve("", "You are a mysterious figure."));
    }
}
