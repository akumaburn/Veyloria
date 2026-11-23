package com.eonmux.chatllm.utils;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DisplayNameFormatterTest {
    @Test void formatsPersonalNamesWithoutGuessingIntentionalCaseOrHandles() {
        Map<String, String> examples = Map.ofEntries(
                Map.entry("amir", "Amir"), Map.entry("AMIR CHEN", "Amir Chen"),
                Map.entry("  josé\u00a0  da silva  ", "José da Silva"),
                Map.entry("vincent van gogh", "Vincent van Gogh"),
                Map.entry("de la cruz", "De la Cruz"),
                Map.entry("mary-jane o’neill", "Mary-Jane O’Neill"),
                Map.entry("D'ANGELO", "D'Angelo"), Map.entry("mcdonald", "McDonald"),
                Map.entry("j. r. r. tolkien", "J. R. R. Tolkien"),
                Map.entry("henry VIII", "Henry VIII"), Map.entry("AJ smith", "AJ Smith"),
                Map.entry("McKenzie deVries", "McKenzie deVries"),
                Map.entry("van der Waals", "Van der Waals"),
                Map.entry("reg_a23def", "reg_a23def"), Map.entry("agent007", "agent007"),
                Map.entry("john.doe", "john.doe"), Map.entry("name@example.com", "name@example.com"),
                Map.entry("陈美玲", "陈美玲"), Map.entry("أمير", "أمير"));
        examples.forEach((input, expected) -> {
            assertEquals(expected, DisplayNameFormatter.normalize(input), input);
            assertEquals(expected, DisplayNameFormatter.normalize(expected), "Formatting must be idempotent");
        });
        assertNull(DisplayNameFormatter.normalize(null));
        assertEquals("", DisplayNameFormatter.normalize(" \t\n"));
    }
}
