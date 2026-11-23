package com.eonmux.chatllm.agents;

import io.vertx.core.json.JsonObject;
import java.util.Set;

/** A request selects one domain; its response proposes exactly one operation or explicit NONE. */
enum BookkeepingDecision {
    INVENTORY("inventoryChanges", "One add/remove/equip/unequip/use operation on one existing owner's item. Fields: exactly one characterId or userId, action, itemName; optional itemDescription, quantity, category, properties, inventoryId, targetCharacterId. Never move locations or grant stats. Use applies declared effects on the server; remove is loss/discard. Natural body features, hairstyles and printed emblems are not inventory. An item is a separate object carried, worn or used. Do not duplicate committed inventory actions. Initial inventory must fit each named actor's actual gear, never copy one actor's loadout to another."),
    STATS("statChanges", "One existing story stat change. Fields: characterId, statName, delta, optional reason. Use exact supplied IDs and stat names, finite nonzero delta. Base strength/dexterity/constitution/intelligence/wisdom/charisma are fixed character attributes, never story stat changes. Do not repeat damage/healing applied by item use or combat services."),
    RELATIONSHIPS("relationshipChanges", "One directed relationship change. Fields: fromId, toId, type, intensityDelta. Finite nonzero intensityDelta. Use exact supplied actor IDs, never modify inventory, stats or place."),
    WORLD("worldStateChanges", "One established world-state field change, represented by an object containing exactly one field: weatherChange (string), timeAdvance (true), flagChanges (object with one flag), or, only before separate scene resolution, currentScene/currentLocation/currentArea (string). Never decide combat. When SCENE RESOLUTION ALREADY COMPLETE is present, currentScene/currentLocation/currentArea are forbidden. Initialization must retain the exact starting STAGE room and place; it cannot move the party or generate a map.");

    final String field;
    final String instruction;
    BookkeepingDecision(String field, String instruction) { this.field = field; this.instruction = instruction; }
    String system() {
        return AgentPromptTemplates.ACCOUNTANT_SYSTEM + " Judge ONLY " + name() + ". " + instruction
            + " Return exactly {\"change\":null} for no further change, or {\"change\":{...}} for ONE operation in this domain."
            + " No lists, second operation, other domains, narration, reasoning, or extra fields."
            + " Accepted proposals listed in the request are already planned; never propose them again."
            + " Record only facts grounded in the completed exchange and server state. Existing service effects are authoritative.";
    }
    boolean valid(JsonObject envelope, boolean sceneComplete) {
        if (envelope.size() != 1 || !envelope.containsKey("change")) return false;
        if (envelope.getValue("change") == null) return true;
        if (!(envelope.getValue("change") instanceof JsonObject c)) return false;
        for (String key : new String[]{"characterId","userId","action","itemName","itemDescription","category","inventoryId","targetCharacterId","statName","reason","fromId","toId","type","achievementId"})
            if (c.containsKey(key) && !(c.getValue(key) instanceof String)) return false;
        return switch (this) {
            case INVENTORY -> JudgmentProtocol.keys(c,"characterId","userId","action","itemName","itemDescription","quantity","category","properties","inventoryId","targetCharacterId")
                && (c.containsKey("characterId") ^ c.containsKey("userId"))
                && (JudgmentProtocol.text(c,"characterId") || JudgmentProtocol.text(c,"userId"))
                && JudgmentProtocol.text(c,"itemName") && c.getString("itemName").length() <= 255
                && Set.of("add","remove","equip","unequip","use").contains(c.getString("action", ""))
                && (!c.containsKey("quantity") || (JudgmentProtocol.number(c,"quantity") && c.getDouble("quantity") >= 1 && c.getDouble("quantity") <= 1000000
                    && c.getDouble("quantity") == Math.rint(c.getDouble("quantity"))))
                && (!c.containsKey("properties") || c.getValue("properties") instanceof JsonObject);
            case STATS -> JudgmentProtocol.keys(c,"characterId","statName","delta","reason") && JudgmentProtocol.text(c,"characterId")
                && JudgmentProtocol.text(c,"statName") && JudgmentProtocol.number(c,"delta") && c.getDouble("delta") != 0
                && !Set.of("strength","dexterity","constitution","intelligence","wisdom","charisma").contains(c.getString("statName").toLowerCase(java.util.Locale.ROOT));
            case RELATIONSHIPS -> JudgmentProtocol.keys(c,"fromId","toId","type","intensityDelta")
                && JudgmentProtocol.text(c,"fromId") && JudgmentProtocol.text(c,"toId") && JudgmentProtocol.text(c,"type")
                && JudgmentProtocol.number(c,"intensityDelta") && c.getDouble("intensityDelta") != 0;
            case WORLD -> c.size() == 1 && c.fieldNames().stream().allMatch(key -> switch (key) {
                case "weatherChange" -> JudgmentProtocol.text(c,key);
                case "timeAdvance" -> Boolean.TRUE.equals(c.getValue(key));
                case "flagChanges" -> c.getValue(key) instanceof JsonObject flags && flags.size() == 1;
                case "currentArea", "currentLocation", "currentScene" -> !sceneComplete && JudgmentProtocol.text(c,key);
                default -> false;
            });
        };
    }
    String identity(JsonObject c) {
        return switch (this) {
            case INVENTORY -> new io.vertx.core.json.JsonArray().add(c.getString("userId",c.getString("characterId")))
                .add(c.getString("action")).add(c.getString("inventoryId",c.getString("itemName")).strip().toLowerCase(java.util.Locale.ROOT))
                .add(c.getString("targetCharacterId")).encode();
            case STATS -> new io.vertx.core.json.JsonArray().add(c.getString("characterId")).add(c.getString("statName").toLowerCase(java.util.Locale.ROOT)).encode();
            case RELATIONSHIPS -> new io.vertx.core.json.JsonArray().add(c.getString("fromId")).add(c.getString("toId")).add(c.getString("type")).encode();
            case WORLD -> c.fieldNames().iterator().next();
        };
    }

}
