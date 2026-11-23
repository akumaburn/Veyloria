package com.eonmux.chatllm.agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPromptTemplatesTest {

    @Test void theNarratorDoesNotTakeACharacterSide() {
        String prompt = AgentPromptTemplates.NARRATOR_SYSTEM;

        assertTrue(prompt.contains("## Neutrality"), prompt);
        assertTrue(prompt.contains("A character's theory is that character's claim, not your fact."), prompt);
        assertTrue(prompt.contains("Never stage an event to prove a character right."), prompt);
        assertTrue(prompt.contains("Do not describe a character as obeying, defying, or ignoring an order."), prompt);
        assertTrue(prompt.contains("Do not raise the tension because the characters are afraid."), prompt);
    }

    @Test void theNarratorStaysSilentDuringArgument() {
        String prompt = AgentPromptTemplates.NARRATOR_SYSTEM;

        assertTrue(prompt.contains("Discussion is not an action"), prompt);
        assertTrue(prompt.contains("The server has already decided"), prompt);
        assertTrue(prompt.contains("Do not judge whether to narrate"), prompt);
    }

    @Test void theNarratorDoesNotChoreographTheParty() {
        String prompt = AgentPromptTemplates.NARRATOR_SYSTEM;

        assertTrue(prompt.contains("Do not give a party member a new action, gesture, position, or emotion."), prompt);
        assertTrue(prompt.contains("Do not move the party."), prompt);
    }

    @Test void theNarratorTreatsTheBodyAsFixed() {
        String prompt = AgentPromptTemplates.NARRATOR_SYSTEM;

        assertTrue(prompt.contains(
                "The appearance line and the physical facts are fixed."), prompt);
        assertTrue(prompt.contains("Do not add a tail, ears, a scar, a height, a build, a hair color, an eye color"), prompt);
    }

    @Test void theNarratorMustWriteTheOpening() {
        String prompt = AgentPromptTemplates.NARRATOR_SYSTEM;

        assertTrue(prompt.contains("## Opening the Story"), prompt);
        assertTrue(prompt.contains("never output `[NO_NARRATION]` for it"), prompt);
        assertTrue(prompt.contains("The opening of a story is not a moment to wait for a change"), prompt);
    }

    @Test void accountantRequestsOneDomainAndOneOperation() {
        String prompt = AgentPromptTemplates.ACCOUNTANT_SYSTEM;
        assertTrue(prompt.contains("exactly one proposed operation"));
        assertTrue(prompt.contains("Never combine domains"));
        assertTrue(BookkeepingDecision.STATS.system().contains("Base strength"));
    }
    @Test void inventoryCannotTurnBodyFeaturesIntoItems() {
        assertTrue(BookkeepingDecision.INVENTORY.system().contains("body features"));
        assertTrue(BookkeepingDecision.INVENTORY.system().contains("Do not duplicate committed inventory actions"));
    }
    @Test void initializationRetainsTheStartingStage() {
        assertTrue(BookkeepingDecision.WORLD.system().contains("retain the exact starting STAGE"));
        assertTrue(BookkeepingDecision.WORLD.system().contains("cannot move the party"));
    }
}
