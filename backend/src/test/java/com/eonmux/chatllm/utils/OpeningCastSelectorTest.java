package com.eonmux.chatllm.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningCastSelectorTest {
    private static OpeningCastSelector.Candidate candidate(String id, String name, int order,
                                                            String role, String text) {
        return new OpeningCastSelector.Candidate(id, name, order, role, text);
    }

    private static Map<String, Map<String, Integer>> feelings(Object... values) {
        Map<String, Map<String, Integer>> affinity = new java.util.HashMap<>();
        for (int i = 0; i < values.length; i += 3) {
            String from = (String) values[i];
            String to = (String) values[i + 1];
            int points = (Integer) values[i + 2];
            affinity.computeIfAbsent(from, ignored -> new java.util.HashMap<>()).put(to, points);
        }
        return affinity;
    }

    @Test void selectsTheConnectedCastThatMatchesTheScenario() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Sasha", 0, "Dungeon guide", "guides expeditions through dungeon ruins"),
                candidate("b", "Mira", 1, "Dungeon scholar", "studies dungeon history and lore"),
                candidate("c", "Nia", 2, "Dungeon healer", "heals divers hurt in the dungeon"),
                candidate("d", "Lyra", 3, "Court diplomat", "negotiates treaties for a noble house"),
                candidate("e", "Oren", 4, "Harbor merchant", "sells cargo at the docks"));
        Map<String, Map<String, Integer>> affinity = feelings(
                "a", "b", 60, "b", "a", 40,
                "b", "c", 40, "c", "b", 20,
                "a", "c", 20, "c", "a", 20,
                "d", "e", 60, "e", "d", 60);

        List<String> selected = OpeningCastSelector.select(cast, affinity, "Dungeon Diving", 3);

        assertEquals(List.of("a", "b", "c"), selected);
    }

    @Test void prefersADiverseGroupWhenRelationshipsAreEqual() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Rex", 0, "Mercenary", ""),
                candidate("b", "Sasha", 1, "Sellsword", ""),
                candidate("c", "Bramm", 2, "Guard", ""),
                candidate("d", "Nia", 3, "Healer", ""),
                candidate("e", "Mira", 4, "Scholar", ""),
                candidate("f", "Oren", 5, "Merchant", ""));
        Map<String, Map<String, Integer>> affinity = new java.util.HashMap<>();
        for (OpeningCastSelector.Candidate from : cast) {
            for (OpeningCastSelector.Candidate to : cast) {
                if (from.id().equals(to.id())) continue;
                affinity.computeIfAbsent(from.id(), ignored -> new java.util.HashMap<>()).put(to.id(), 20);
            }
        }

        List<String> selected = OpeningCastSelector.select(cast, affinity, "", 3);
        Set<String> families = selected.stream()
                .map(id -> cast.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow())
                .map(c -> OpeningCastSelector.roleFamily(c.role()))
                .collect(Collectors.toSet());

        assertEquals(3, families.size());
        assertNotEquals(Set.of("combat"), families);
    }

    @Test void keepsRosterOrderWhenTheCastCarriesNoSignal() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("e", "", 4, "", ""),
                candidate("b", "", 1, "", ""),
                candidate("a", "", 0, "", ""),
                candidate("d", "", 3, "", ""),
                candidate("c", "", 2, "", ""));

        List<String> selected = OpeningCastSelector.select(cast, Map.of(), "", 3);

        assertEquals(List.of("a", "b", "c"), selected);
    }

    @Test void aStrongRivalryStillCountsAsAPlausibleReasonToOpenTogether() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Sasha", 0, "Mercenary", ""),
                candidate("b", "Bramm", 1, "Mercenary", ""),
                candidate("c", "Nia", 2, "Mercenary", ""),
                candidate("d", "Mira", 3, "Mercenary", ""),
                candidate("e", "Oren", 4, "Mercenary", ""));
        Map<String, Map<String, Integer>> affinity = feelings(
                "a", "b", -60, "b", "a", -60);

        List<String> selected = OpeningCastSelector.select(cast, affinity, "", 3);

        assertEquals(List.of("a", "b", "c"), selected);
    }

    @Test void requestsAtMostTheWholeCast() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "A", 0, "", ""),
                candidate("b", "B", 1, "", ""));

        assertEquals(List.of("a", "b"), OpeningCastSelector.select(cast, Map.of(), "", 3));
        assertEquals(List.of("a"), OpeningCastSelector.select(cast, Map.of(), "", 1));
    }

    @Test void aScenarioThatNamesOneMemberPutsThatMemberOnstage() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Sasha Viper", 0, "Mercenary", "keeps to the shadows"),
                candidate("b", "Mira Solis", 1, "Scholar", "studies old maps"),
                candidate("c", "Nia Firetail", 2, "Healer", "tends the wounded"),
                candidate("d", "Oren Blackwood", 3, "Merchant", "prices every risk"));

        List<String> selected = OpeningCastSelector.select(cast, Map.of(),
                "Mira Solis discovers a sealed door beneath the city", 3);

        assertTrue(selected.contains("b"));
    }

    @Test void aUniqueFirstNameIsEnoughToRequireAMember() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Sasha Viper", 0, "Mercenary", ""),
                candidate("b", "Rex Storm", 1, "Warlord", ""),
                candidate("c", "Mira Solis", 2, "Scholar", ""),
                candidate("d", "Nia Firetail", 3, "Healer", ""));
        Map<String, Map<String, Integer>> affinity = feelings(
                "a", "b", 60, "b", "a", 60);

        List<String> selected = OpeningCastSelector.select(cast, affinity,
                "Mira waits at the sealed door", 3);

        assertTrue(selected.contains("c"));
    }

    @Test void everyNamedMemberIsRequiredEvenAgainstAStrongerPair() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Sasha", 0, "Mercenary", ""),
                candidate("b", "Rex", 1, "Warlord", ""),
                candidate("c", "Mira", 2, "Scholar", ""),
                candidate("d", "Nia", 3, "Healer", ""));
        Map<String, Map<String, Integer>> affinity = feelings(
                "a", "b", 60, "b", "a", 60);

        List<String> selected = OpeningCastSelector.select(cast, affinity,
                "Mira and Nia wait at the sealed door", 3);

        assertTrue(selected.contains("c"));
        assertTrue(selected.contains("d"));
        assertEquals(3, selected.size());
    }

    @Test void moreNamedMembersThanSlotsStillChoosesOnlyNamedMembers() {
        List<OpeningCastSelector.Candidate> cast = List.of(
                candidate("a", "Sasha", 0, "Mercenary", ""),
                candidate("b", "Rex", 1, "Warlord", ""),
                candidate("c", "Mira", 2, "Scholar", ""),
                candidate("d", "Nia", 3, "Healer", ""),
                candidate("e", "Oren", 4, "Merchant", ""));
        List<String> selected = OpeningCastSelector.select(cast, Map.of(),
                "Sasha, Mira, Nia, and Oren enter the hall", 3);

        assertEquals(3, selected.size());
        assertTrue(Set.of("a", "c", "d", "e").containsAll(selected));
    }
}
