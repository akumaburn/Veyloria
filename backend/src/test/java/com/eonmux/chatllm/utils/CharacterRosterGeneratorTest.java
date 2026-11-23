package com.eonmux.chatllm.utils;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CharacterRosterGeneratorTest {
    static final String CONTEXT = "A medieval harbor rebuilding after a naval siege, with rival guilds and a fragile peace.";
    public static JsonArray cast(int count) {
        JsonArray characters = new JsonArray();
        for (int i = 0; i < count; i++) characters.add(new JsonObject().put("name", "Person " + i)
                .put("role", "Harbor guild member").put("ageYears", 30 + i).put("pronouns", "they/them")
                .put("description", "A dockworker with competing loyalties.")
                .put("systemPrompt", "Speak as a weary but determined harbor resident.")
                .put("backstory", "Helped rebuild the docks after the siege.")
                .put("relationships", new JsonArray().add(new JsonObject().put("targetName", "Person " + ((i + 1) % count))
                        .put("description", "Rebuilt the east quay together after the siege; a debt remains unpaid."))));
        return characters;
    }
    private String json(JsonArray cast) { return new JsonObject().put("characters", cast).encode(); }

    @Test void minimumAndMaximumRostersRetainSettingAndNamedRelationships() {
        for (int count : new int[]{2, 12}) {
            JsonArray parsed = CharacterRosterGenerator.parse(json(cast(count)), CONTEXT, count);
            assertEquals(count, parsed.size());
            for (int i = 0; i < count; i++) {
                JsonObject character = parsed.getJsonObject(i);
                assertTrue(character.getString("systemPrompt").contains(CONTEXT));
                assertTrue(character.getString("systemPrompt").contains("Person " + ((i + 1) % count)));
                assertTrue(character.getString("backstory").contains("a debt remains unpaid"));
            }
        }
    }
    @Test void rejectsCountMismatch() {
        assertThrows(IllegalArgumentException.class, () -> CharacterRosterGenerator.parse(json(cast(2)), CONTEXT, 3));
    }

    @Test void renamesDuplicateNamesInsteadOfFailing() {
        JsonArray cast = cast(2);
        cast.getJsonObject(0).put("name", "Elara Vane").put("role", "Professor of History");
        cast.getJsonObject(1).put("name", "Elara Vane").put("role", "First-Year Student");
        for (int i = 0; i < 2; i++)
            cast.getJsonObject(i).getJsonArray("relationships").getJsonObject(0)
                    .put("targetName", "Elara Vane");
        JsonArray parsed = CharacterRosterGenerator.parse(json(cast), CONTEXT, 2);
        assertEquals("Elara Vane", parsed.getJsonObject(0).getString("name"));
        assertNotEquals("Elara Vane", parsed.getJsonObject(1).getString("name"));
        assertTrue(parsed.getJsonObject(1).getString("name").contains("Student"));
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < 2; i++)
            assertTrue(unique.add(parsed.getJsonObject(i).getString("name").toLowerCase()));
    }
    @Test void repairsUnknownSelfDuplicateAndDisconnectedRelationships() {
        for (String target : new String[]{"Missing person", "Person 0"}) {
            JsonArray cast = cast(2);
            cast.getJsonObject(0).getJsonArray("relationships").getJsonObject(0).put("targetName", target);
            JsonArray parsed = CharacterRosterGenerator.parse(json(cast), CONTEXT, 2);
            assertEquals("Person 1", parsed.getJsonObject(0).getJsonArray("relationships")
                    .getJsonObject(0).getString("targetName"));
        }
        JsonArray disconnected = cast(4);
        disconnected.getJsonObject(1).getJsonArray("relationships").getJsonObject(0).put("targetName", "Person 0");
        disconnected.getJsonObject(3).getJsonArray("relationships").getJsonObject(0).put("targetName", "Person 2");
        JsonArray parsed = CharacterRosterGenerator.parse(json(disconnected), CONTEXT, 4);
        assertEquals(4, parsed.size());
        for (int i = 0; i < 4; i++)
            assertFalse(parsed.getJsonObject(i).getJsonArray("relationships").isEmpty(),
                    "member " + i + " keeps a connection");
    }

    @Test void keepsTheFirstDuplicateTargetAndDropsSelfReferences() {
        JsonArray cast = cast(3);
        cast.getJsonObject(0).put("relationships", new JsonArray()
                .add(new JsonObject().put("targetIndex", 2).put("feeling", "LIKE")
                        .put("description", "Shared a trial"))
                .add(new JsonObject().put("targetIndex", 2).put("feeling", "HATE")
                        .put("description", "A later rivalry"))
                .add(new JsonObject().put("targetIndex", 0).put("feeling", "LOVE")
                        .put("description", "Self-reference")));
        JsonArray relationships = CharacterRosterGenerator.parse(json(cast), CONTEXT, 3)
                .getJsonObject(0).getJsonArray("relationships");
        assertEquals(1, relationships.size());
        assertEquals(2, relationships.getJsonObject(0).getInteger("targetIndex"));
        assertEquals("LIKE", relationships.getJsonObject(0).getString("feeling"));
    }
    @Test void acceptsFencedJsonAndStripsProviderThinking() {
        assertEquals(2, CharacterRosterGenerator.parse("<think>Plan the cast.</think>\n```json\n" + json(cast(2)) + "\n```", CONTEXT, 2).size());
    }
    @Test void rejectsIncompleteContentAndInvalidRequests() {
        assertThrows(IllegalArgumentException.class, () -> CharacterRosterGenerator.parse("{\"characters\":[", CONTEXT, 2));
        JsonArray missing = cast(2); missing.getJsonObject(0).remove("backstory");
        assertThrows(IllegalArgumentException.class, () -> CharacterRosterGenerator.parse(json(missing), CONTEXT, 2));
        for (int count : new int[]{1, 13}) assertThrows(IllegalArgumentException.class, () -> CharacterRosterGenerator.prompt(CONTEXT, count));
        assertThrows(IllegalArgumentException.class, () -> CharacterRosterGenerator.prompt("short", 2));
        assertThrows(IllegalArgumentException.class, () -> CharacterRosterGenerator.prompt("x".repeat(6001), 2));
    }
    @Test void indexedConnectionsResolveToTheActualGeneratedNamesAndSchemaFixesTheCount() {
        JsonArray cast = cast(12);
        for (int i = 0; i < 12; i++) {
            JsonObject relationship = cast.getJsonObject(i).getJsonArray("relationships").getJsonObject(0);
            relationship.remove("targetName"); relationship.put("targetIndex", (i + 1) % 12);
        }
        JsonArray parsed = CharacterRosterGenerator.parse(json(cast), CONTEXT, 12);
        assertTrue(parsed.getJsonObject(0).getString("backstory").contains("Person 1"));
        JsonObject array = CharacterRosterGenerator.responseFormat(12).getJsonObject("json_schema")
                .getJsonObject("schema").getJsonObject("properties").getJsonObject("characters");
        assertEquals(12, array.getInteger("minItems")); assertEquals(12, array.getInteger("maxItems"));
        JsonArray invalid = cast(2);
        invalid.getJsonObject(0).put("relationships", new JsonArray().add(new JsonObject().put("targetIndex", 4).put("description", "Unknown")));
        JsonArray repaired = CharacterRosterGenerator.parse(json(invalid), CONTEXT, 2);
        assertEquals("Person 1", repaired.getJsonObject(0).getJsonArray("relationships")
                .getJsonObject(0).getString("targetName"));
    }

    @Test void generatedAlignmentsAndFeelingsAreCanonicalAndDefaultSafely() {
        JsonArray cast = cast(3);
        cast.getJsonObject(0).put("alignment", "Lawful Good");
        cast.getJsonObject(1).put("alignment", "chaotic-evil");
        cast.getJsonObject(0).getJsonArray("relationships").getJsonObject(0).put("feeling", "like");
        cast.getJsonObject(1).getJsonArray("relationships").getJsonObject(0).put("feeling", "HATE");
        JsonArray parsed = CharacterRosterGenerator.parse(json(cast), CONTEXT, 3);

        assertEquals("LAWFUL_GOOD", parsed.getJsonObject(0).getString("alignment"));
        assertEquals("CHAOTIC_EVIL", parsed.getJsonObject(1).getString("alignment"));
        assertEquals("TRUE_NEUTRAL", parsed.getJsonObject(2).getString("alignment"));
        JsonObject first = parsed.getJsonObject(0).getJsonArray("relationships").getJsonObject(0);
        assertEquals("LIKE", first.getString("feeling"));
        assertEquals("Person 1", first.getString("targetName"));
        assertEquals("HATE", parsed.getJsonObject(1).getJsonArray("relationships").getJsonObject(0).getString("feeling"));
    }

    @Test void schemaRequiresAnAlignmentChoiceAndAFeelingForEachConnection() {
        JsonObject schema = CharacterRosterGenerator.responseFormat(4).getJsonObject("json_schema")
                .getJsonObject("schema");
        JsonObject member = schema.getJsonObject("properties").getJsonObject("characters")
                .getJsonObject("items");
        assertTrue(member.getJsonArray("required").contains("alignment"));
        assertEquals(9, member.getJsonObject("properties").getJsonObject("alignment").getJsonArray("enum").size());
        JsonObject relation = member.getJsonObject("properties").getJsonObject("relationships")
                .getJsonObject("items");
        assertTrue(relation.getJsonArray("required").contains("targetName"));
        assertTrue(relation.getJsonArray("required").contains("feeling"));
        assertFalse(relation.getJsonObject("properties").containsKey("targetIndex"));
        assertEquals(5, relation.getJsonObject("properties").getJsonObject("feeling").getJsonArray("enum").size());
    }

    @Test void profileFactsAndAttributesAreKeptInsideTheirRange() {
        JsonArray cast = cast(2);
        JsonObject subject = cast.getJsonObject(0)
                .put("gender", "Woman").put("height", "5 ft 6 in").put("build", "Slight")
                .put("hair", "Red, braided").put("eyes", "Green")
                .put("strength", 25).put("dexterity", "14").put("constitution", 11)
                .put("intelligence", 15).put("wisdom", 12).put("charisma", 9);

        JsonObject parsed = CharacterRosterGenerator.parse(json(cast), CONTEXT, 2).getJsonObject(0);

        assertEquals("Woman", parsed.getString("gender"));
        assertEquals("5 ft 6 in", parsed.getString("height"));
        assertEquals("Slight", parsed.getString("build"));
        assertEquals("Red, braided", parsed.getString("hair"));
        assertEquals("Green", parsed.getString("eyes"));
        assertEquals(18, parsed.getInteger("strength"));
        assertEquals(14, parsed.getInteger("dexterity"));
        assertEquals(9, parsed.getInteger("charisma"));
        assertTrue(parsed.getString("systemPrompt").contains("gender Woman"));
    }

    @Test void schemaRequiresProfileFactsAndBaseAttributes() {
        JsonObject member = CharacterRosterGenerator.responseFormat(4).getJsonObject("json_schema")
                .getJsonObject("schema").getJsonObject("properties").getJsonObject("characters")
                .getJsonObject("items");
        JsonArray required = member.getJsonArray("required");
        for (String field : new String[]{"gender", "pronouns", "ageYears", "height", "build", "hair", "eyes",
                "strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"}) {
            assertTrue(required.contains(field), field + " must be required");
        }
        JsonObject strength = member.getJsonObject("properties").getJsonObject("strength");
        assertEquals(3, strength.getInteger("minimum"));
        assertEquals(18, strength.getInteger("maximum"));
    }

    @Test void identityFieldsDriveThePortableCastSummary() {
        JsonArray details = new JsonArray()
                .add(new JsonObject().put("name", "Mara Venn").put("role", "Wharfinger").put("ageYears", 45)
                        .put("pronouns", "she/her").put("description", "A dock official.")
                        .put("systemPrompt", "Speak as a wharfinger.").put("backstory", "Kept the harbor records.")
                        .put("relationships", new JsonArray().add(new JsonObject().put("targetName", "Tomas Reed")
                                .put("description", "Rebuilt the docks together."))))
                .add(new JsonObject().put("name", "Tomas Reed").put("role", "Shipwright").put("ageYears", 50)
                        .put("pronouns", "he/him").put("description", "A shipwright.")
                        .put("systemPrompt", "Speak as a shipwright.").put("backstory", "Repaired the east quay.")
                        .put("relationships", new JsonArray().add(new JsonObject().put("targetName", "Mara Venn")
                                .put("description", "Rebuilt the docks together."))));
        JsonArray parsed = CharacterRosterGenerator.parse(new JsonObject().put("characters", details).encode(), CONTEXT, 2);
        assertEquals("Mara Venn", parsed.getJsonObject(0).getString("name"));
        String instructions = parsed.getJsonObject(0).getString("systemPrompt");
        assertTrue(instructions.contains("age 45; pronouns she/her"));
        assertTrue(instructions.contains("Tomas Reed — Shipwright (age 50; he/him)"));
        assertTrue(parsed.getJsonObject(0).getString("backstory").contains("Tomas Reed: Rebuilt"));
    }

    @Test void replacesFacultyReasonsThatContradictTheCurrentRole() {
        JsonArray cast = cast(2);
        JsonObject teacher = cast.getJsonObject(0);
        teacher.put("name", "Professor Elara Vane").put("role", "Professor of History").put("pronouns", "she/her")
                .put("backstory", "She has taught history at the academy for twenty years.");
        teacher.put("relationships", new JsonArray().add(new JsonObject().put("targetName", "Person 1")
                .put("feeling", "HATE").put("description", "She never forgave him for her expulsion from the academy.")));
        JsonObject relation = CharacterRosterGenerator.parse(json(cast), CONTEXT, 2)
                .getJsonObject(0).getJsonArray("relationships").getJsonObject(0);
        assertEquals("A serious conflict makes this relationship hostile.", relation.getString("description"));
        assertEquals("HATE", relation.getString("feeling"));
    }

    @Test void keepsAStudentPastThatTheBackstoryEstablishes() {
        JsonArray cast = cast(2);
        JsonObject teacher = cast.getJsonObject(0);
        teacher.put("name", "Professor Elara Vane").put("role", "Professor of History").put("pronouns", "she/her")
                .put("backstory", "She was expelled as a student, then returned years later to teach history.");
        teacher.put("relationships", new JsonArray().add(new JsonObject().put("targetName", "Person 1")
                .put("feeling", "HATE").put("description", "He caused her expulsion, and she never forgave him.")));
        JsonObject relation = CharacterRosterGenerator.parse(json(cast), CONTEXT, 2)
                .getJsonObject(0).getJsonArray("relationships").getJsonObject(0);
        assertEquals("He caused her expulsion, and she never forgave him.", relation.getString("description"));
    }

    @Test void matchesTitlesAndShortNamesWithoutUsingModelIndices() {
        JsonArray cast = cast(2);
        cast.getJsonObject(0).put("name", "Archivist Mara Vane");
        cast.getJsonObject(1).put("name", "Tomas Reed");
        cast.getJsonObject(0).getJsonArray("relationships").getJsonObject(0).put("targetName", "tomas reed");
        cast.getJsonObject(1).getJsonArray("relationships").getJsonObject(0).put("targetName", "Archivist Mara Vane");
        JsonArray parsed = CharacterRosterGenerator.parse(json(cast), CONTEXT, 2);
        assertEquals("Tomas Reed", parsed.getJsonObject(0).getJsonArray("relationships")
                .getJsonObject(0).getString("targetName"));
        assertEquals("Archivist Mara Vane", parsed.getJsonObject(1).getJsonArray("relationships")
                .getJsonObject(0).getString("targetName"));
    }

}
