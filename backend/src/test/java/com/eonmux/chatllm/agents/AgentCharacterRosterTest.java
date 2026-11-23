package com.eonmux.chatllm.agents;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCharacterRosterTest {

    private AgentContext context() {
        return AgentContext.builder()
                .characterIdsInScene(List.of("elara"))
                .characterNames(new JsonObject().put("elara", "Elara Vane"))
                .characterPronouns(new JsonObject().put("elara", "she/her"))
                .characterDescriptions(new JsonObject().put("elara", "Student Diver at The Royal Academy"))
                .characterProfiles(new JsonObject().put("elara", new JsonObject()
                        .put("gender", "Woman").put("ageYears", 18)
                        .put("height", "5 ft 11 in / 180 cm").put("build", "Slender and athletic")
                        .put("hair", "Dark, long, tight practical braid").put("eyes", "Silver, sharp")))
                .characterAttributes(new JsonObject().put("elara", new JsonObject()
                        .put("strength", 11).put("dexterity", 14).put("constitution", 12)
                        .put("intelligence", 16).put("wisdom", 13).put("charisma", 12)))
                .build();
    }

    @Test void theAccountantSeesTheIdAndTheFacts() {
        StringBuilder prompt = new StringBuilder();
        AccountantAgent.appendCharactersInScene(prompt, context());

        String text = prompt.toString();
        assertTrue(text.contains("Elara Vane (she/her) → characterId: \"elara\""), text);
        assertTrue(text.contains("Role: Student Diver at The Royal Academy"), text);
        assertTrue(text.contains("Facts: gender Woman; age 18; height 5 ft 11 in / 180 cm"), text);
        assertTrue(text.contains(
                "Base attributes (fixed; not story stats; 1-20, 10 is average): STR 11; DEX 14; CON 12; INT 16; WIS 13; CHA 12"), text);
    }

    @Test void theMuseSeesTheRoleAndTheFacts() {
        StringBuilder prompt = new StringBuilder();
        MuseAgent.appendCharactersInScene(prompt, context());

        String text = prompt.toString();
        assertTrue(text.contains("- Elara Vane (she/her)"), text);
        assertTrue(text.contains("Role: Student Diver at The Royal Academy"), text);
        assertTrue(text.contains("Facts: gender Woman"), text);
    }

    @Test void theCasterSeesTheExistingBodyItMustNotDuplicate() {
        StringBuilder prompt = new StringBuilder();
        CastingDirectorAgent.appendCharactersInScene(prompt, context());

        String text = prompt.toString();
        assertTrue(text.contains("- Elara Vane (characterId: elara, she/her)"), text);
        assertTrue(text.contains("Facts: gender Woman; age 18"), text);
    }

    @Test void theHighlighterSeesTheNameToIdMap() {
        StringBuilder prompt = new StringBuilder();
        HighlighterAgent.appendCharactersInScene(prompt, context());

        String text = prompt.toString();
        assertTrue(text.contains("- Elara Vane → elara (she/her)"), text);
    }

    @Test void theSkepticSeesTheFixedBodyAndTheBaseLimits() {
        StringBuilder prompt = new StringBuilder();
        new SkepticAgent(null, null, null, null).appendCharacterFacts(prompt, context(), "elara");

        String text = prompt.toString();
        assertTrue(text.contains("- Elara Vane (she/her)"), text);
        assertTrue(text.contains("Facts: gender Woman; age 18; height 5 ft 11 in / 180 cm"), text);
        assertTrue(text.contains(
                "Base attributes (fixed limits; 1-20, 10 is average): STR 11; DEX 14; CON 12; INT 16; WIS 13; CHA 12"), text);
    }

    @Test void anAgentWithoutACharacterAddsNoRosterBlock() {
        StringBuilder prompt = new StringBuilder();
        AgentContext empty = AgentContext.builder().build();

        NarratorAgent.appendCharactersInScene(prompt, empty);
        AccountantAgent.appendCharactersInScene(prompt, empty);
        MuseAgent.appendCharactersInScene(prompt, empty);
        CastingDirectorAgent.appendCharactersInScene(prompt, empty);
        HighlighterAgent.appendCharactersInScene(prompt, empty);

        assertTrue(prompt.isEmpty());
        assertFalse(prompt.toString().contains("CHARACTERS IN SCENE"));
    }
}
