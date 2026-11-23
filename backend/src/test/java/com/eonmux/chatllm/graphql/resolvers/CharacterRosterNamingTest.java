package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Character;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterRosterNamingTest {

    @Test void keepsAShortSettingSentence() {
        assertEquals("A harbor city rebuilding after a siege",
                CharacterRosterResolver.rosterName(null,
                        "A harbor city rebuilding after a siege.", List.of(member("Maya"))));
    }

    @Test void namesALongSettingAfterItsCast() {
        String setting = "Decades ago, hundreds of portals appeared across the planet, "
                + "connecting human worlds.";

        assertEquals("Sylvanis Moonwhisper and 2 others",
                CharacterRosterResolver.rosterName(null, setting,
                        List.of(member("Sylvanis Moonwhisper"), member("Kaelen Drift"),
                                member("Mira Sol"))));
    }

    @Test void handlesASingleMember() {
        assertEquals("Sable Quinn", CharacterRosterResolver.rosterName(null,
                "A very long setting that will not fit in a name.", List.of(member("Sable Quinn"))));
    }

    @Test void honorsAnExplicitName() {
        assertEquals("Harbor Guild", CharacterRosterResolver.rosterName("Harbor Guild",
                "A harbor city rebuilding after a siege.", List.of(member("Maya"))));
    }

    private static Character member(String name) {
        return Character.builder().name(name).build();
    }
}
