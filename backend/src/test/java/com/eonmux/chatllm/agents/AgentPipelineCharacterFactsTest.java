package com.eonmux.chatllm.agents;

import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPipelineCharacterFactsTest {

    @Test void everyPromptMapIsBuiltFromOneCharacterRow() {
        io.vertx.sqlclient.Row delegate = mock(io.vertx.sqlclient.Row.class);
        when(delegate.getString("characterUUID")).thenReturn("elara");
        when(delegate.getString("name")).thenReturn("Elara Vane");
        when(delegate.getString("pronouns")).thenReturn("She/Her");
        when(delegate.getString("identity")).thenReturn("pronouns they/them");
        when(delegate.getString("description")).thenReturn("Student Diver at The Royal Academy");
        when(delegate.getString("appearance")).thenReturn("A thin field coat and a tight braid.");
        when(delegate.getString("gender")).thenReturn("Woman");
        when(delegate.getInteger("ageYears")).thenReturn(19);
        when(delegate.getString("height")).thenReturn("5 ft 8 in");
        when(delegate.getString("build")).thenReturn("Lean and quick");
        when(delegate.getString("hair")).thenReturn("Dark brown, braided");
        when(delegate.getString("eyes")).thenReturn("Silver-gray");
        when(delegate.getInteger("strength")).thenReturn(9);
        when(delegate.getInteger("dexterity")).thenReturn(13);
        when(delegate.getInteger("constitution")).thenReturn(11);
        when(delegate.getInteger("intelligence")).thenReturn(16);
        when(delegate.getInteger("wisdom")).thenReturn(12);
        when(delegate.getInteger("charisma")).thenReturn(10);

        JsonObject facts = AgentPipeline.characterFactsFromRows(List.of(new Row(delegate)));

        assertEquals("Elara Vane", facts.getJsonObject("names").getString("elara"));
        assertEquals("she/her", facts.getJsonObject("pronouns").getString("elara"));
        assertEquals("Student Diver at The Royal Academy",
                facts.getJsonObject("descriptions").getString("elara"));
        assertEquals("A thin field coat and a tight braid.",
                facts.getJsonObject("appearances").getString("elara"));
        JsonObject profile = facts.getJsonObject("profiles").getJsonObject("elara");
        assertEquals("Woman", profile.getString("gender"));
        assertEquals(19, profile.getInteger("ageYears"));
        assertEquals("5 ft 8 in", profile.getString("height"));
        assertEquals("Lean and quick", profile.getString("build"));
        assertEquals("Dark brown, braided", profile.getString("hair"));
        assertEquals("Silver-gray", profile.getString("eyes"));
        JsonObject attributes = facts.getJsonObject("attributes").getJsonObject("elara");
        assertEquals(9, attributes.getInteger("strength"));
        assertEquals(16, attributes.getInteger("intelligence"));
    }

    @Test void anOlderCharacterFallsBackToItsIdentityLine() {
        io.vertx.sqlclient.Row delegate = mock(io.vertx.sqlclient.Row.class);
        when(delegate.getString("characterUUID")).thenReturn("old");
        when(delegate.getString("name")).thenReturn("Old Timer");
        when(delegate.getString("identity")).thenReturn(
                "Canonical identity: Old Timer; Guide; age 60; pronouns he/him.");
        when(delegate.getInteger("ageYears")).thenReturn(null);
        for (String field : List.of("strength", "dexterity", "constitution",
                "intelligence", "wisdom", "charisma")) {
            when(delegate.getInteger(field)).thenReturn(null);
        }

        JsonObject facts = AgentPipeline.characterFactsFromRows(List.of(new Row(delegate)));

        assertEquals("he/him", facts.getJsonObject("pronouns").getString("old"));
        assertFalse(facts.getJsonObject("profiles").containsKey("old"));
        assertFalse(facts.getJsonObject("attributes").containsKey("old"));
    }

    @Test void aCharacterWithoutAFactsStillHasItsName() {
        io.vertx.sqlclient.Row delegate = mock(io.vertx.sqlclient.Row.class);
        when(delegate.getString("characterUUID")).thenReturn("assistant");
        when(delegate.getString("name")).thenReturn("Assistant");
        when(delegate.getInteger("ageYears")).thenReturn(null);
        for (String field : List.of("strength", "dexterity", "constitution",
                "intelligence", "wisdom", "charisma")) {
            when(delegate.getInteger(field)).thenReturn(null);
        }

        JsonObject facts = AgentPipeline.characterFactsFromRows(List.of(new Row(delegate)));

        assertEquals("Assistant", facts.getJsonObject("names").getString("assistant"));
        assertTrue(facts.getJsonObject("pronouns").isEmpty());
        assertTrue(facts.getJsonObject("profiles").isEmpty());
        assertTrue(facts.getJsonObject("attributes").isEmpty());
    }
}
