package com.eonmux.chatllm.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLayoutFactoryTest {

    @Test void aCastleHasTheRoomsTheSceneNeeds() {
        LocalLayoutFactory.Layout layout = LocalLayoutFactory.generate(
                "castle-1", "Blackmoor Castle", "castle", "A grim seat above the valley.");

        Set<String> names = names(layout);
        assertTrue(names.contains("Great Hall"), names.toString());
        assertTrue(names.contains("Kitchen"), names.toString());
        assertTrue(names.contains("Gatehouse"), names.toString());
        assertTrue(names.contains("Courtyard"), names.toString());
        assertTrue(layout.areas().stream().anyMatch(LocalLayoutFactory.AreaDraft::entry));
        assertTrue(layout.areas().stream().anyMatch(LocalLayoutFactory.AreaDraft::gateway));
    }

    @Test void layoutIsDeterministicForTheSameNode() {
        LocalLayoutFactory.Layout first = LocalLayoutFactory.generate(
                "node-42", "The Old Keep", "keep", null);
        LocalLayoutFactory.Layout second = LocalLayoutFactory.generate(
                "node-42", "The Old Keep", "keep", null);

        assertEquals(names(first), names(second));
        assertEquals(first.connections(), second.connections());
    }

    @Test void aGenericTerrainSentenceDoesNotReplaceTheNodesType() {
        // The procedural description appends a biome line. A dungeon whose
        // description mentions thin mountain air still has an interior, not an
        // outdoor switchback with a peak above it.
        LocalLayoutFactory.Layout layout = LocalLayoutFactory.generate(
                "dungeon-node", "Hearthlane", "dungeon",
                "A dark and treacherous underground complex sprawls beneath the surface. "
                        + "Rocky terrain and thin mountain air define this harsh landscape.");

        Set<String> names = names(layout);
        assertTrue(names.contains("Collapsed Hall"), names.toString());
        assertTrue(names.contains("Entrance"), names.toString());
        assertFalse(names.contains("Switchback"), names.toString());
    }

    @Test void aGenericPlaceNameDoesNotReplaceTheNodesType() {
        // The generated map names a dungeon "Weawick's Crossing". The word
        // "crossing" must not turn the dungeon into a river layout.
        LocalLayoutFactory.Layout layout = LocalLayoutFactory.generate(
                "dungeon-node", "Weawick's Crossing", "dungeon", null);

        Set<String> names = names(layout);
        assertTrue(names.contains("Collapsed Hall"), names.toString());
        assertFalse(names.contains("Pier"), names.toString());
    }

    @Test void aRenamedCityCanBecomeAnAcademy() {
        LocalLayoutFactory.Layout layout = LocalLayoutFactory.generate(
                "city-node", "Haylon Academy", "city",
                "One of the four adventuring schools that train divers.");

        Set<String> names = names(layout);
        assertTrue(names.contains("Lecture Hall"), names.toString());
        assertTrue(names.contains("Dormitory"), names.toString());
        assertTrue(names.contains("Training Yard"), names.toString());
        assertFalse(names.contains("Market Square"), names.toString());
    }

    @Test void aCommonWordDoesNotReplaceTheNodesOwnType() {
        // "keeps" is not a castle. The substring test used to match "keep"
        // and gave this village a Great Hall, Barracks and a Dungeon Stair.
        LocalLayoutFactory.Layout village = LocalLayoutFactory.generate(
                "village-node", "Rabretongrobury", "village",
                "Thin trails of woodsmoke mark this tiny settlement. "
                        + "The lowland setting keeps the area sheltered from harsh winds.");

        Set<String> villageNames = names(village);
        assertTrue(villageNames.contains("Village Green"), villageNames.toString());
        assertFalse(villageNames.contains("Great Hall"), villageNames.toString());

        // "important" is not a port, and "determine" is not a mine.
        LocalLayoutFactory.Layout town = LocalLayoutFactory.generate(
                "town-node", "Somewhere", "town",
                "An important stop where the elders determine who may enter.");
        Set<String> townNames = names(town);
        assertTrue(townNames.contains("Market Square"), townNames.toString());
        assertFalse(townNames.contains("Docks"), townNames.toString());
        assertFalse(townNames.contains("Mine Entrance"), townNames.toString());
    }

    @Test void everyLayoutIsConnectedAndHasAWayOut() {
        List<String> types = List.of("castle", "city", "village", "dungeon", "cave", "mine",
                "forest", "mountain", "port", "camp", "ruins", "temple", "tower", "swamp", "oasis", "");
        for (String type : types) {
            for (int variant = 0; variant < 3; variant++) {
                LocalLayoutFactory.Layout layout = LocalLayoutFactory.generate(
                        type + "-" + variant, "Somewhere", type, null);
                Map<String, Set<String>> graph = graph(layout);
                String entry = layout.areas().stream()
                        .filter(LocalLayoutFactory.AreaDraft::entry)
                        .map(LocalLayoutFactory.AreaDraft::key)
                        .findFirst()
                        .orElseThrow();
                assertEquals(layout.areas().size(), reachable(entry, graph).size(),
                        "disconnected layout for type=" + type + " variant=" + variant);
                assertTrue(layout.areas().stream().anyMatch(LocalLayoutFactory.AreaDraft::gateway),
                        "no gateway for type=" + type + " variant=" + variant);
                for (LocalLayoutFactory.ConnectionDraft link : layout.connections()) {
                    assertTrue(graph.get(link.fromKey()).contains(link.toKey()));
                    assertTrue(graph.get(link.toKey()).contains(link.fromKey()));
                }
            }
        }
    }

    @Test void areaNamesAreUniqueWithinALayout() {
        for (String type : List.of("castle", "town", "dungeon", "temple", "academy")) {
            LocalLayoutFactory.Layout layout = LocalLayoutFactory.generate(
                    type, type, type, type);
            Set<String> names = new HashSet<>();
            for (LocalLayoutFactory.AreaDraft area : layout.areas()) {
                assertTrue(names.add(area.name()), "duplicate area name: " + area.name());
            }
        }
    }

    private static Set<String> names(LocalLayoutFactory.Layout layout) {
        Set<String> names = new HashSet<>();
        for (LocalLayoutFactory.AreaDraft area : layout.areas()) names.add(area.name());
        return names;
    }

    private static Map<String, Set<String>> graph(LocalLayoutFactory.Layout layout) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (LocalLayoutFactory.AreaDraft area : layout.areas()) {
            graph.put(area.key(), new HashSet<>());
        }
        for (LocalLayoutFactory.ConnectionDraft link : layout.connections()) {
            graph.get(link.fromKey()).add(link.toKey());
            graph.get(link.toKey()).add(link.fromKey());
        }
        return graph;
    }

    private static Set<String> reachable(String start, Map<String, Set<String>> graph) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : graph.getOrDefault(current, Set.of())) {
                if (seen.add(next)) queue.addLast(next);
            }
        }
        return seen;
    }
}
