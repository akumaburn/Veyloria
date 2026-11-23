package com.eonmux.chatllm.models.inputs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Name("KeyboardSettingsInput")
public class KeyboardSettingsInput {
    // Keyboard shortcuts are dynamic key-value pairs. Frontend sends as a list
    // of entries to preserve ordering. Backend converts to Map internally.
    private List<ShortcutEntry> shortcuts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShortcutEntry {
        private String key;
        private String value;
    }
}
