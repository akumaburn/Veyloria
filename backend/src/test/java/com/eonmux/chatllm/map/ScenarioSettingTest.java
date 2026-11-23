package com.eonmux.chatllm.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioSettingTest {

    @Test void aDungeonStoryOpensAtAnUndergroundPlace() {
        List<String> types = ScenarioSetting.preferredStartingTypes(
                "Dungeon Diving in the ruined lower levels");

        assertEquals("dungeon", types.get(0));
        assertTrue(types.contains("cave"));
    }

    @Test void aCastleStoryOpensAtAFortification() {
        List<String> types = ScenarioSetting.preferredStartingTypes(
                "A siege at the royal castle");
        assertEquals("castle", types.get(0));
    }

    @Test void anAcademyStoryOpensAtASettlement() {
        List<String> types = ScenarioSetting.preferredStartingTypes(
                "First year at Haylon Academy");
        assertEquals("city", types.get(0));
    }

    @Test void unrelatedTextFallsBackToTheSettlementPreference() {
        assertTrue(ScenarioSetting.preferredStartingTypes("A quiet conversation").isEmpty());
        assertTrue(ScenarioSetting.preferredStartingTypes(null).isEmpty());
    }

    @Test void wordBoundariesDoNotMatchSubstrings() {
        assertTrue(ScenarioSetting.preferredStartingTypes("An important portent").isEmpty());
    }
}
