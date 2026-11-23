package com.eonmux.chatllm.messaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroupPromptFormatTest {

    @Test void theGroupPromptStatesHowActionAndSpeechAreWritten() throws Exception {
        JsonObject recent = new JsonObject()
                .put("role", "user")
                .put("displayName", "Amir")
                .put("content", "Give me a number I can work with.");

        JsonArray messages = buildGroupMessages("Everyone at the table looks up.", new JsonArray().add(recent));

        String system = systemContent(messages);
        assertTrue(system.contains("=== HOW TO MARK ACTION AND SPEECH ==="), system);
        // The client renders markdown emphasis, so the prompt must ask for the
        // asterisk form. Without this line the model writes flat prose and no
        // action is ever emphasised.
        assertTrue(system.contains("double quotation marks"), system);
        assertTrue(system.contains("single asterisks"), system);
        // Markdown ignores emphasis delimiters that touch a space, so a padded
        // action would print its asterisks literally.
        assertTrue(system.contains("Leave no space inside the asterisks"), system);
        assertTrue(system.contains("in the first person"), system);
    }

    @Test void theGroupPromptKeepsTheSpeakerAndSceneRules() throws Exception {
        JsonArray messages = buildGroupMessages("Everyone at the table looks up.", new JsonArray());
        String system = systemContent(messages);

        assertTrue(system.contains("You are Elara Vance in an ongoing ensemble scene."), system);
        assertTrue(system.contains("Never decide another character's words"), system);
        assertTrue(system.contains("=== END CONTEXT ==="), system);
    }

    @Test void thePromptEndsWithThisCharacterVoiceNotASharedTemplate() throws Exception {
        JsonArray messages = buildGroupMessages("", new JsonArray());
        String system = systemContent(messages);

        assertTrue(system.contains("=== HOW THIS CHARACTER TALKS ==="), system);
        assertTrue(system.contains("Her speech is fast and sharp."), system);
        // The turn shape must stay open. A fixed "always end on a question"
        // rule made every speaker hand the floor over the same way.
        assertTrue(system.contains("A question is one possible ending, not the only one."), system);
        assertFalse(system.contains("End on a question, decision, offer, threat"),
                "the forced handoff is what made every turn identical");
        assertFalse(system.contains("Every turn needs at least one action beat"),
                "a forced action beat on every turn is a template, not a voice");
        // Voice must be the last thing before the markup block, not buried above
        // the generic scene rules.
        assertTrue(system.indexOf("=== HOW THIS CHARACTER TALKS ===")
                > system.indexOf("=== GROUP SCENE ==="), system);
    }

    @Test void aCharacterWithoutASpeechTraitStillGetsItsGreetingAsTheVoiceAnchor() {
        com.eonmux.chatllm.models.Character character = com.eonmux.chatllm.models.Character.builder()
                .name("Jinx O'Malley")
                .personality("Charming, reckless, and pragmatic.")
                .greeting("Hey, stranger. You look like you need a guide.")
                .build();

        String anchor = character.getVoiceAnchor();

        assertEquals("A line this character has said, in their own voice. "
                + "Match its rhythm and vocabulary; do not reuse the line itself:\n"
                + "\"Hey, stranger. You look like you need a guide.\"", anchor);
        assertNull(character.getSpeechTrait());
    }

    @Test void theVoiceAnchorRepeatsTheSpeechTraitFromThePersonality() {
        com.eonmux.chatllm.models.Character character = com.eonmux.chatllm.models.Character.builder()
                .name("Bramm Stonehand")
                .personality("Traditional, stubborn, and honorable. "
                        + "His speech is slow, deliberate, and filled with dwarven idioms.")
                .greeting("Speak your business quickly.")
                .build();

        String anchor = character.getVoiceAnchor();

        assertTrue(anchor.startsWith("His speech is slow, deliberate, and filled with dwarven idioms."), anchor);
        assertTrue(anchor.contains("\"Speak your business quickly.\""), anchor);
    }

    @Test void aMessageAddressedToSomeoneElseIsNotAnsweredForThem() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "Just hum a tune to keep yourself steady. Let's scan for a lever.",
                "Amir", "jr", "Kaelen Vane Jr.");

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("=== THE PLAYER'S LAST MESSAGE ==="), system);
        assertTrue(system.contains("Addressed to Kaelen Vane Jr., not to you."), system);
        assertTrue(system.contains("do not decide their reaction"), system);
        // The player's action is binding; a character may not pre-empt it.
        assertTrue(system.contains("Treat the player's stated action as happening."), system);
        assertTrue(system.contains("never deny it before it resolves"), system);
    }

    @Test void theAddressedCharacterIsToldToAnswerDirectly() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "Kaelen, check your CIDs.", "Amir", "elara", "Elara Vance");

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("Addressed to you. Answer it directly"), system);
        assertFalse(system.contains("not to you"), system);
    }

    @Test void aGroupMessageStaysWithTheGroup() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "We leave at dawn.", "Amir", null, null);

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("Addressed to everyone present. Answer it as yourself."), system);
    }

    @Test void aLaterSpeakerIsToldTheMessageAlreadyHasAnAnswer() throws Exception {
        // The second and third speakers used to be invited to answer the same
        // player line, which made every chain restate the same argument.
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "Let's just move forward.", "Amir", null, null, true, List.of("Elara Vane"));

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("Already answered by Elara Vane."), system);
        assertTrue(system.contains("This message already has an answer. Do not answer it again."), system);
        assertFalse(system.contains("Addressed to everyone present. Answer it as yourself."), system);
    }

    @Test void theAddresseeIsNotAskedToAnswerTwice() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "Kaelen, check the seals.", "Amir", "elara", "Elara Vance", true, List.of("Elara Vance"));

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("You have already answered this message."), system);
        assertFalse(system.contains("Addressed to you. Answer it directly"), system);
    }

    @Test void anOpeningTurnGivesThePlayerRoomWithoutInventingALine() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("The player is present but has not spoken."), system);
        assertTrue(system.contains("Do not invent their words, actions, gear, body, or condition"), system);
    }

    @Test void turnsAreNotRequiredToOpenWithAnActionBeat() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("Do not begin every turn with an italic action"), system);
        assertTrue(system.contains("The marks are the point, not the order"), system);
    }

    @Test void asterisksAreReservedForActionAndNotEmphasis() throws Exception {
        // The model wrote "The Dungeon is a *living* thing". Markdown renders
        // that word with the same muted italics as an action beat, so a reader
        // cannot tell the two apart.
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("Never use them for emphasis"), system);
        assertTrue(system.contains("The app renders the stressed word as an action beat"), system);
    }

    @Test void theOpeningTurnLeavesTheDecisionToThePlayer() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("The choice in front of the group belongs to the player."), system);
        assertTrue(system.contains("You may give an instruction that belongs to your role"), system);
        assertTrue(system.contains("do not choose the party's destination or settle its course"), system);
    }

    @Test void waitingIsAnActionAndNotAnotherArgument() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "We wait until they cannot wait any longer.", "Amir", null, null);

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("waiting is not an empty turn"), system);
        assertTrue(system.contains("Do not reopen the decision"), system);
    }

    @Test void theLastSpeakerIsToldToChangeTheScene() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "We hold position.", "Amir", null, null);

        String system = systemContent(buildGroupMessagesFinal("", new JsonArray(), player, true));

        assertTrue(system.contains("This is the last turn of this round."), system);
        assertTrue(system.contains("Do not end on another question, plea, or request for a decision."), system);
    }

    @Test void anEarlierSpeakerIsNotToldItIsTheLastTurn() throws Exception {
        String system = systemContent(buildGroupMessagesFinal("", new JsonArray(), null, false));

        assertFalse(system.contains("This is the last turn of this round."), system);
    }

    @Test void theOpeningKeepsTheDecisionOpenEvenForItsLastSpeaker() throws Exception {
        // The opening has no player turn yet, so the group must not settle the
        // course. The last voice still has to move the scene and stop asking
        // the silent player for a decision.
        String system = systemContent(buildGroupMessagesFinal("", new JsonArray(), null, true));

        assertFalse(system.contains("This is the last turn of this round."), system);
        assertTrue(system.contains("Do not end the turn by asking the player to decide"), system);
        assertTrue(system.contains("This is the last voice before the player acts."), system);
        assertTrue(system.contains("Do not ask the player to decide"), system);
        assertTrue(system.contains("End on one concrete detail in the scene"), system);
    }

    @Test void appearanceAndIndependentReadsAreRequired() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("YOUR APPEARANCE IS FIXED"), system);
        assertTrue(system.contains("If the sheet calls something an accessory"), system);
        assertTrue(system.contains("give your own reading of it"), system);
        assertTrue(system.contains("Do not adopt the previous speaker's interpretation as fact"), system);
        assertTrue(system.contains("Do not repeat the previous speaker's reason"), system);
        assertTrue(system.contains("Keep an open thread in view"), system);
    }

    @Test void aCharacterOwnsOnlyItselfAndNotTheWorld() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("=== WHAT YOU CONTROL ==="), system);
        assertTrue(system.contains("You do not control the world."), system);
        assertTrue(system.contains("Do not declare that a wall shifts"), system);
        assertTrue(system.contains("You do not control another character."), system);
        assertTrue(system.contains("Do not give another character an expression, a posture, a look"), system);
        assertTrue(system.contains("Do not invent the player's body, gear, or condition."), system);
        assertTrue(system.contains("You do not control the result of your own action."), system);
        assertTrue(system.contains("mark it as your reading"), system);
        assertTrue(system.contains("A learned opinion is still an opinion"), system);
    }

    @Test void theSpeakerIsAMemberUnderThePlayerAndMustComply() throws Exception {
        var player = new com.eonmux.chatllm.services.GroupChatService.PlayerTurn(
                "We hold position. Nobody moves until I say so.", "Amir", null, null);

        String system = systemContent(buildGroupMessages("", new JsonArray(), player));

        assertTrue(system.contains("=== THE PARTY AND ITS LEADER ==="), system);
        assertTrue(system.contains("The player leads this party, and the player's last message sets the course."), system);
        assertTrue(system.contains("You are a member of it, not its leader."), system);
        assertTrue(system.contains("A direct instruction from the player is an order."), system);
        assertTrue(system.contains("the debate is closed: comply, in voice and action."), system);
        assertTrue(system.contains("Never threaten to leave the party"), system);
        assertTrue(system.contains("The player's last message is the party's standing order"), system);
    }

    @Test void theSheetConstrainsTemperamentAndPreferences() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("=== YOUR SHEET IS THE LIMIT ==="), system);
        assertTrue(system.contains("A like or dislike is a reflex, not a speech topic."), system);
        assertTrue(system.contains("A trait is a tendency, not a compulsion."), system);
        assertTrue(system.contains("A character who is eager to please complies."), system);
        assertTrue(system.contains("A mediator tries to bring the group together"), system);
        assertTrue(system.contains("You carry only the inventory listed"), system);
    }

    @Test void aBeliefOnTheSheetIsStillAClaim() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("Your sheet may state a belief about the world."), system);
        assertTrue(system.contains("A belief is still a claim"), system);
    }

    @Test void aTurnDoesNotRepeatTheRequestForADecision() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("If someone already asked the player to decide, do not ask again."),
                system);
        assertTrue(system.contains("Keep the turn to two short paragraphs."), system);
    }

    @Test void speechIsNeverBuriedInsideAnActionBlock() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("Speech never goes inside an action block."), system);
        assertTrue(system.contains("Every first-person action sentence goes inside asterisks."), system);
        assertTrue(system.contains("Never italicize a single word apart from an action."), system);
    }

    @Test void aSensingAbilityIsAReadingAndNotAFact() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("An ability that senses the world gives you a reading, not a fact."), system);
        assertTrue(system.contains("do not say \"the wall shifts\""), system);
        assertTrue(system.contains("The narrator confirms what happens."), system);
    }

    @Test void theLastLineChecksWorldControlAndCompliance() throws Exception {
        String system = systemContent(buildGroupMessages("", new JsonArray()));

        assertTrue(system.contains("=== BEFORE YOU SEND ==="), system);
        assertTrue(system.contains("Write only what this character says, feels, or does."), system);
        assertTrue(system.contains("rewrite it as your own glance or your own reaction"), system);
        assertTrue(system.contains("remove it"), system);
        assertTrue(system.contains("rewrite the turn as compliance"), system);
    }

    @Test void otherSpeakersReachTheModelLabelledByName() throws Exception {
        JsonArray history = new JsonArray()
                .add(new JsonObject().put("role", "assistant").put("characterId", "jax")
                        .put("displayName", "Jax Thorne").put("content", "No swing yet."))
                .add(new JsonObject().put("role", "assistant").put("characterId", "elara")
                        .put("displayName", "Elara Vance").put("content", "I earlier spoke."));

        JsonArray messages = buildGroupMessages("stage", history);

        assertEquals("[Jax Thorne]: No swing yet.", messages.getJsonObject(1).getString("content"));
        // The speaker's own earlier turn stays an assistant turn, so the model
        // continues its own voice instead of reading it as someone else's line.
        assertEquals("assistant", messages.getJsonObject(2).getString("role"));
        assertEquals("I earlier spoke.", messages.getJsonObject(2).getString("content"));
    }

    @Test void theOpeningTurnCarriesAUserCue() throws Exception {
        // The external provider rejects a request that has no user turn, which
        // killed the first speaker of a new scene and left it empty.
        JsonArray messages = buildGroupMessages("", new JsonArray());

        assertEquals(2, messages.size(), messages::toString);
        assertEquals("system", messages.getJsonObject(0).getString("role"));
        assertEquals("user", messages.getJsonObject(1).getString("role"));
        assertEquals("The scene is open. The player has not spoken yet. Play Elara Vance's opening turn.",
                messages.getJsonObject(1).getString("content"));
    }

    @Test void theCueComesAfterTheSpeakersOwnEarlierTurn() throws Exception {
        JsonArray history = new JsonArray()
                .add(new JsonObject().put("role", "assistant").put("characterId", "elara")
                        .put("displayName", "Elara Vance").put("content", "I spoke once."));

        JsonArray messages = buildGroupMessages("", history);

        assertEquals("assistant", messages.getJsonObject(1).getString("role"));
        assertEquals("user", messages.getJsonObject(2).getString("role"));
        assertEquals("The scene is open. The player has not spoken yet. Continue as Elara Vance.",
                messages.getJsonObject(2).getString("content"));
    }

    @Test void aNarratorOpeningIsNotAPlayerTurn() throws Exception {
        // The narrator's opening reaches the model as a user turn. It must not
        // suppress the cue, or the first character answers the narrator in
        // third person instead of playing its own role.
        JsonArray history = new JsonArray()
                .add(new JsonObject().put("role", "system").put("displayName", "Narrator")
                        .put("content", "The hall is dark and the seals hum."));

        JsonArray messages = buildGroupMessages("", history);

        assertEquals(3, messages.size(), messages::toString);
        assertEquals("[Narrator]: The hall is dark and the seals hum.",
                messages.getJsonObject(1).getString("content"));
        assertEquals("user", messages.getJsonObject(2).getString("role"));
        assertEquals("The scene is open. The player has not spoken yet. Play Elara Vance's opening turn.",
                messages.getJsonObject(2).getString("content"));
    }

    @Test void theCueIsNotAddedOnceThePlayerHasSpoken() throws Exception {
        JsonArray history = new JsonArray()
                .add(new JsonObject().put("role", "user").put("displayName", "Amir")
                        .put("content", "We leave at dawn."));

        JsonArray messages = buildGroupMessages("", history);

        assertEquals(2, messages.size(), messages::toString);
        assertEquals("[Amir]: We leave at dawn.", messages.getJsonObject(1).getString("content"));
    }

    @Test void theLastTwoTurnsNameTheImagesToAvoid() {
        JsonArray history = new JsonArray()
                .add(new JsonObject().put("role", "assistant").put("content", "It digests the stone."))
                .add(new JsonObject().put("role", "user").put("content", "We wait."))
                .add(new JsonObject().put("role", "assistant").put("content", "It chews the dark."));

        List<String> images = LLMBridge.recentImages(history);

        assertTrue(images.contains("chew"), images.toString());
        assertTrue(images.contains("digest"), images.toString());
    }

    @Test void anOlderImageIsNotNamed() {
        JsonArray history = new JsonArray()
                .add(new JsonObject().put("role", "assistant").put("content", "The walls taste us."))
                .add(new JsonObject().put("role", "assistant").put("content", "It digests the stone."))
                .add(new JsonObject().put("role", "assistant").put("content", "It chews the dark."));

        List<String> images = LLMBridge.recentImages(history);

        assertTrue(images.contains("chew"), images.toString());
        assertTrue(images.contains("digest"), images.toString());
        assertFalse(images.contains("taste"), images.toString());
    }

    @Test void emphasisAsterisksAreUnwrappedBeforeStorage() {
        // The client renders every <em> as an action beat, so a stressed word
        // written in asterisks looks like a gesture.
        assertEquals("The Dungeon is a living thing.",
                LLMBridge.normalizeRoleplayMarkup("The Dungeon is a *living* thing."));
        assertEquals("Really?", LLMBridge.normalizeRoleplayMarkup("*Really?*"));
        assertEquals("The wall is a metabolic shift.",
                LLMBridge.normalizeRoleplayMarkup("The wall is a *metabolic shift*."));
    }

    @Test void aFirstPersonActionBlockStaysItalic() {
        String turn = "*I set the cup down.* \"Say the number.\" *my fingers find the latch*";

        assertEquals(turn, LLMBridge.normalizeRoleplayMarkup(turn));
    }

    @Test void escapedLineBreaksBecomeRealLineBreaks() {
        assertEquals("The hall was cold.\n\nThe door opened.",
                LLMBridge.normalizeRoleplayMarkup("The hall was cold.\\n\\nThe door opened."));
        assertEquals("One line.\nTwo lines.",
                LLMBridge.normalizeRoleplayMarkup("One line.\nTwo lines."));
    }

    @Test void aLongActionBlockDoesNotSwallowTheSpeechAfterIt() {
        String turn = "*I exhale a shaky breath, the tension in my shoulders dropping just "
                + "an inch as the static fades, and I press my back against the damp crate.* "
                + "\"Thin veil. That is a good plan.\" *I nod once.*";

        assertEquals(turn, LLMBridge.normalizeRoleplayMarkup(turn));
    }

    private static JsonArray buildGroupMessages(String castPresence, JsonArray history) throws Exception {
        return buildGroupMessages(castPresence, history, null);
    }

    private static JsonArray buildGroupMessages(String castPresence, JsonArray history,
                                                com.eonmux.chatllm.services.GroupChatService.PlayerTurn player)
            throws Exception {
        LLMBridge bridge = new LLMBridge();
        Method method = LLMBridge.class.getDeclaredMethod("buildGroupMessagesArray",
                JsonArray.class, String.class, String.class, String.class, String.class,
                JsonObject.class, String.class, String.class,
                com.eonmux.chatllm.map.StageContext.class,
                com.eonmux.chatllm.services.GroupChatService.PlayerTurn.class);
        method.setAccessible(true);
        return (JsonArray) method.invoke(bridge, history, "=== WHO YOU ARE ===\nElara Vance",
                "elara", "Elara Vance", "Her speech is fast and sharp.",
                new JsonObject(), "", castPresence, null, player);
    }

    private static JsonArray buildGroupMessagesFinal(String castPresence, JsonArray history,
                                                     com.eonmux.chatllm.services.GroupChatService.PlayerTurn player,
                                                     boolean finalTurn) throws Exception {
        LLMBridge bridge = new LLMBridge();
        Method method = LLMBridge.class.getDeclaredMethod("buildGroupMessagesArray",
                JsonArray.class, String.class, String.class, String.class, String.class,
                JsonObject.class, String.class, String.class,
                com.eonmux.chatllm.map.StageContext.class,
                com.eonmux.chatllm.services.GroupChatService.PlayerTurn.class,
                boolean.class);
        method.setAccessible(true);
        return (JsonArray) method.invoke(bridge, history, "=== WHO YOU ARE ===\nElara Vance",
                "elara", "Elara Vance", "Her speech is fast and sharp.",
                new JsonObject(), "", castPresence, null, player, finalTurn);
    }

    private static String systemContent(JsonArray messages) {
        return messages.getJsonObject(0).getString("content");
    }
}
