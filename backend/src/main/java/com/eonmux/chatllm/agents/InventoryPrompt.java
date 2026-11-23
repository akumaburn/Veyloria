package com.eonmux.chatllm.agents;

final class InventoryPrompt {
    private InventoryPrompt() {}
    static void appendCommittedActions(StringBuilder prompt, AgentContext context) {
        if (context.getCharacterSheet() != null) {
            var inventory = new io.vertx.core.json.JsonObject();
            context.getCharacterSheet().forEach(entry -> {
                if (entry.getValue() instanceof io.vertx.core.json.JsonObject sheet)
                    inventory.put(entry.getKey(), sheet.getJsonArray("inventory", new io.vertx.core.json.JsonArray()));
            });
            prompt.append("=== CURRENT INVENTORIES (authoritative) ===\n").append(inventory.encodePrettily()).append("\n\n");
        }
        if (context.getInventoryActions() == null || context.getInventoryActions().isEmpty()) return;
        prompt.append("=== RECENT COMMITTED INVENTORY ACTIONS (newest first) ===\n")
            .append("These actions and their effects have ALREADY been applied. Do not repeat additions, consumption, transfers or stat effects. ")
            .append("They are historical facts, not requests for a new action. Current inventory sheets are authoritative.\n")
            .append(context.getInventoryActions().encodePrettily()).append("\n\n");
    }
}
