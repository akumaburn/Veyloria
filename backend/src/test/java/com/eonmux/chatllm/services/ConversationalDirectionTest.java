package com.eonmux.chatllm.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversational direction. The speaker is chosen before any model sees the
 * prompt, so these rules decide who answers the player.
 */
class ConversationalDirectionTest {

    private static final List<GroupChatService.CastMember> CAST = List.of(
            new GroupChatService.CastMember("kaelen", "Kaelen Vane"),
            new GroupChatService.CastMember("elara", "Elara Vane"),
            new GroupChatService.CastMember("jr", "Kaelen Vane Jr."),
            new GroupChatService.CastMember("jinx", "Jinx O'Malley"),
            new GroupChatService.CastMember("bramm", "Bramm Stonehand"),
            new GroupChatService.CastMember("mara", "Mara Stonehand"),
            new GroupChatService.CastMember("rin", "Rin Neko"));

    @Test void aUniqueFirstNameAddressesThatCharacter() {
        assertEquals("elara", GroupChatService.matchAddressee("Elara, what do you make of it?", CAST));
        assertEquals("rin", GroupChatService.matchAddressee("Rin, hold the illusion.", CAST));
        assertEquals("jinx", GroupChatService.matchAddressee("Jinx, you first.", CAST));
    }

    @Test void aFullNamePicksTheCharacterWhoOwnsIt() {
        assertEquals("bramm", GroupChatService.matchAddressee("Bramm Stonehand, a word.", CAST));
        assertEquals("mara", GroupChatService.matchAddressee("Mara Stonehand, stay close.", CAST));
    }

    @Test void aSharedFirstNameAddressesNobody() {
        // Father and son share "Kaelen". Saying only that names a family, not
        // one person, so the caller falls back to the previous question.
        assertNull(GroupChatService.matchAddressee("Kaelen, check your CIDs.", CAST));
    }

    @Test void aSharedSurnameAddressesNobody() {
        assertNull(GroupChatService.matchAddressee("Vane, stay behind me.", CAST));
        assertNull(GroupChatService.matchAddressee("Stonehand, hold the line.", CAST));
    }

    @Test void aFullNameWithSuffixBeatsThePrefixMatch() {
        // The father's name is a prefix of the son's. The longer full name wins.
        assertEquals("jr", GroupChatService.matchAddressee("Kaelen Vane Jr., are you ready?", CAST));
        assertEquals("kaelen", GroupChatService.matchAddressee("Kaelen Vane, take point.", CAST));
    }

    @Test void aFirstNameWithSuffixNamesTheSon() {
        assertEquals("jr", GroupChatService.matchAddressee("Kaelen Jr., hold the line.", CAST));
    }

    @Test void aPossessiveStillNamesItsOwner() {
        assertEquals("jinx", GroupChatService.matchAddressee("Jinx's plan is reckless.", CAST));
    }

    @Test void aWordThatMerelyStartsWithANameIsNotAnAddress() {
        assertNull(GroupChatService.matchAddressee("Bring the rope and maraud nothing.", CAST));
    }

    @Test void aNameOutsideTheCastAddressesNobody() {
        assertNull(GroupChatService.matchAddressee("Gandalf, open the gate.", CAST));
    }

    @Test void aMessageWithNoNameAddressesNobody() {
        assertNull(GroupChatService.matchAddressee(
                "I hum a tune and scan the room for a lever.", CAST));
    }

    @Test void onlyTheLastSpokenLineDecidesWhetherAQuestionWasAsked() {
        assertTrue(GroupChatService.endsWithQuestion(
                "*I fumble with the buckle.* \"If the walls shift early, will it hurt if I am not ready?\""));
        assertFalse(GroupChatService.endsWithQuestion(
                "*I fumble with the buckle.* \"I do not want to mess this up.\""));
        // A question mark inside an action beat is not addressed to anyone.
        assertFalse(GroupChatService.endsWithQuestion(
                "*Do I look ready? I tighten the strap.* \"We go.\""));
        assertFalse(GroupChatService.endsWithQuestion(""));
        assertFalse(GroupChatService.endsWithQuestion(null));
    }
}
