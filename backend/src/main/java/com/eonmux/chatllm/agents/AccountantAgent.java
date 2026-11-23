package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.map.StagePrompt;
import com.eonmux.chatllm.services.AgentModelConfigService;
import com.eonmux.chatllm.utils.CharacterFacts;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AccountantAgent extends BaseAgent {

    public AccountantAgent(Vertx vertx, Pool mysqlPool, AppConfig config,
                           AgentModelConfigService modelConfigService) {
        super("accountant", vertx, mysqlPool, config, modelConfigService);
    }

    @Override
    protected Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
        StringBuilder userPrompt = new StringBuilder();
        if (context.getBattleContext() != null) {
            userPrompt.append("=== AUTHORITATIVE ENCOUNTER ===\n").append(context.getBattleContext().encodePrettily())
                .append("\nRounds and their consequences are already committed. Do not duplicate damage, item use or rewards. "
                    + "While ACTIVE, only the battle actions resolve combat; ordinary chat may express intent or character reactions, "
                    + "but must not decide the player's actions, move the party, declare an outcome, or change combat stats/inventory. "
                    + "After a terminal outcome, acknowledge the consequences and continue from the scene left by the fight.\n\n");
        }

        if (context.isSceneResolutionComplete()) {
            userPrompt.append("=== SCENE RESOLUTION ALREADY COMPLETE ===\n")
                .append("Travel and interaction decisions were handled in separate earlier steps. The STAGE is authoritative. ")
                .append("Do not emit currentArea, currentLocation, currentScene or movementBlockedReason. ")
                .append("Only track bookkeeping consequences (stats, inventory, relationships, time, weather and flags).\n")
                .append("Confirmed travel: ").append(context.getStageMoveDescription()).append("\n")
                .append("Interaction: ").append(context.getInteractionResult()).append("\n\n");
        } else if (context.isPostDialogue()) {
            userPrompt.append("=== COMPLETED CHARACTER ROUND ===\n")
                .append("The player initiated this round; the characters' replies are complete. ")
                .append("Answer only the single bookkeeping-domain question at the end of this request.\n\n");
        }

        // Roleplay scenario description (helps Accountant ground world state changes)
        if (context.getRoleplayDescription() != null && !context.getRoleplayDescription().isBlank()) {
            userPrompt.append("=== ROLEPLAY SCENARIO ===\n");
            userPrompt.append(context.getRoleplayDescription()).append("\n\n");
        }

        // Current world state
        if (context.getWorldState() != null) {
            userPrompt.append("=== CURRENT WORLD STATE ===\n");
            userPrompt.append(context.getWorldState().encodePrettily()).append("\n\n");
        }

        // Characters in scene with their IDs — Accountant MUST use these exact IDs
        appendCharactersInScene(userPrompt, context);

        // Character sheets
        if (context.getCharacterSheet() != null) {
            userPrompt.append("=== CHARACTER SHEETS ===\n");
            userPrompt.append(context.getCharacterSheet().encodePrettily()).append("\n\n");
        }

        InventoryPrompt.appendCommittedActions(userPrompt, context);

        // Map context for spatial awareness
        if (context.getStage() != null && context.getStage().isPresent()) {
            userPrompt.append(StagePrompt.format(context.getStage(), context.isSceneResolutionComplete() ? StagePrompt.Audience.STATE : StagePrompt.Audience.ACCOUNTANT));
        }

        // Map context for spatial awareness
        if (context.getMapLocations() != null && !context.getMapLocations().isEmpty()) {
            userPrompt.append("=== MAP CONTEXT ===\n");
            if (context.getMapName() != null) {
                userPrompt.append("Map: ").append(context.getMapName());
                if (context.getMapScale() != null) {
                    userPrompt.append(" (").append(context.getMapScale()).append(" scale)");
                }
                userPrompt.append("\n");
            }
            if (context.getCurrentMapLocation() != null) {
                JsonObject loc = context.getCurrentMapLocation();
                userPrompt.append("Current location: ").append(loc.getString("name", "Unknown"));
                String locType = loc.getString("locationType");
                if (locType != null) userPrompt.append(" [").append(locType).append("]");
                userPrompt.append("\n");
            }
            if (context.getNearbyLocations() != null && !context.getNearbyLocations().isEmpty()) {
                userPrompt.append("Nearby locations:\n");
                for (int i = 0; i < context.getNearbyLocations().size(); i++) {
                    JsonObject nearby = context.getNearbyLocations().getJsonObject(i);
                    userPrompt.append("  - ").append(nearby.getString("name", "Unknown"));
                    String type = nearby.getString("locationType");
                    if (type != null) userPrompt.append(" [").append(type).append("]");
                    Float dist = nearby.getFloat("distance");
                    if (dist != null) userPrompt.append(" (~").append(Math.round(dist)).append(" units away)");
                    userPrompt.append("\n");
                }
            }
            // List all discovered locations for reference
            JsonArray allLocs = context.getMapLocations();
            userPrompt.append("Known locations: ");
            boolean first = true;
            for (int i = 0; i < allLocs.size(); i++) {
                JsonObject loc = allLocs.getJsonObject(i);
                if (Boolean.TRUE.equals(loc.getBoolean("isDiscovered"))) {
                    if (!first) userPrompt.append(", ");
                    userPrompt.append(loc.getString("name", "?"));
                    first = false;
                }
            }
            userPrompt.append("\n\n");
        }

        // Recent messages
        if (context.getRecentMessages() != null && !context.getRecentMessages().isEmpty()) {
            userPrompt.append("=== RECENT MESSAGES ===\n");
            int start = Math.max(0, context.getRecentMessages().size() - 10);
            for (int i = start; i < context.getRecentMessages().size(); i++) {
                JsonObject msg = context.getRecentMessages().getJsonObject(i);
                userPrompt.append(msg.getString("displayName", msg.getString("role", "unknown")));
                userPrompt.append(": ").append(msg.getString("content", "")).append("\n");
            }
            userPrompt.append("\n");
        }

        // Latest trigger message
        if (context.getTriggerContent() != null) {
            userPrompt.append("=== LATEST MESSAGE ===\n");
            userPrompt.append(context.getTriggerContent()).append("\n\n");
        }

        return collect(context, userPrompt.toString(), resolvedModel);
    }

    /** Each call returns one operation. Aggregation is server code, never a compound model judgment. */
    Uni<AgentResult> collect(AgentContext context, String prompt, String model) {
        JsonObject aggregate = new JsonObject();
        Uni<Void> sequence = Uni.createFrom().voidItem();
        for (BookkeepingDecision scope : BookkeepingDecision.values()) {
            sequence = sequence.chain(() -> collectScope(context, prompt, model, scope, aggregate, new JsonArray(), 0));
        }
        return sequence.replaceWith(() -> AgentResult.builder().agentName(agentName).status("success")
            .content(aggregate.encode()).structuredOutput(aggregate).build());
    }

    private Uni<Void> collectScope(AgentContext context, String prompt, String model, BookkeepingDecision scope,
                                   JsonObject aggregate, JsonArray accepted, int count) {
        if (context.getProgressReporter() != null) context.getProgressReporter().accept("accountant_"+scope.name().toLowerCase(),
            switch (scope) { case INVENTORY -> "Updating inventories…"; case STATS -> "Updating character stats…";
                case RELATIONSHIPS -> "Updating relationships…"; case WORLD -> "Updating the world state…"; });
        int limit = scope == BookkeepingDecision.INVENTORY && AgentPipeline.isSessionStart(context) ? 24 : 8;
        String request = prompt + "\n=== ACCEPTED PROPOSALS (do not repeat) ===\n" + aggregate.encode()
            + "\n=== CURRENT DOMAIN ===\n" + scope.name() + "\n";
        boolean requireStartingInventory = scope == BookkeepingDecision.INVENTORY && AgentPipeline.isSessionStart(context) && count == 0;
        if (requireStartingInventory) request += "Starting inventory is required; propose the first grounded item.\n";
        return callJudgment(scope.system(), request, 650, model, value -> scope.valid(value, context.isSceneResolutionComplete())
                && (!requireStartingInventory || value.getValue("change") != null))
            .chain(response -> {
                JsonObject wire = JudgmentProtocol.object(extractContent(response));
                AgentResult step = AgentResult.builder().agentName("accountant_" + scope.name().toLowerCase()).status("success")
                    .content(wire.encode()).structuredOutput(wire).modelUsed(model)
                    .inputTokens(extractUsage(response).getInteger("prompt_tokens",0))
                    .outputTokens(extractUsage(response).getInteger("completion_tokens",0)).build();
                return logRun(context.getConversationId(), context.getTriggerMessageId(), step, step.getAgentName()).replaceWith(wire);
            }).chain(wire -> {
                JsonObject change = wire.getJsonObject("change");
                if (change == null) return Uni.createFrom().voidItem();
                if (count >= limit) return Uni.createFrom().failure(new IllegalStateException("Accountant " + scope + " exceeded its operation limit"));
                for (Object previous : accepted) if (scope != BookkeepingDecision.WORLD && scope.identity(change).equals(scope.identity((JsonObject) previous)))
                    return Uni.createFrom().failure(new IllegalStateException("Accountant repeated an accepted " + scope + " operation"));
                accepted.add(change.copy());
                if (scope == BookkeepingDecision.WORLD) {
                    JsonObject world = aggregate.getJsonObject(scope.field, new JsonObject());
                    String key = change.fieldNames().iterator().next();
                    if ("flagChanges".equals(key)) {
                        JsonObject flags = world.getJsonObject(key, new JsonObject());
                        String flag = change.getJsonObject(key).fieldNames().iterator().next();
                        if (flags.containsKey(flag)) throw new IllegalStateException("Repeated world flag decision");
                        flags.mergeIn(change.getJsonObject(key)); world.put(key, flags);
                    } else {
                        if (world.containsKey(key)) throw new IllegalStateException("Repeated world field decision");
                        world.mergeIn(change);
                    }
                    aggregate.put(scope.field, world);
                } else {
                    JsonArray changes = aggregate.getJsonArray(scope.field, new JsonArray());
                    changes.add(change);
                    aggregate.put(scope.field, changes);
                }
                return collectScope(context, prompt, model, scope, aggregate, accepted, count + 1);
            });
    }

    Uni<AgentResult> reviewLoadout(AgentContext context) {
        String system = "Judge ONLY whether this proposed character's starting equipment is plausible and balanced in the supplied world. "
            + "Do not create inventory, stats, movement or another character. Return exactly one review object: approved (boolean), reason (string). "
            + "A rejection requires a concrete reason. No other fields.";
        return resolveModel(context.getConversationId()).chain(model -> callJudgment(system,
            "World: " + context.getWorldState() + "\nRules: " + context.getTemplateRules() + "\nProposed loadout: " + context.getCasterOutput(),
            350, model, value -> JudgmentProtocol.keys(value,"approved","reason") && JudgmentProtocol.review(value)))
            .map(response -> {
                JsonObject verdict = JudgmentProtocol.object(extractContent(response));
                return AgentResult.builder().status("success").structuredOutput(verdict).content(verdict.encode()).build();
            });
    }

    /**
     * Names, stored pronouns, roles and physical facts for every character in
     * the scene. The accountant needs the ID mapping; the extra facts let it
     * resolve a description such as "the tall dwarf" to the right ID.
     */
    static void appendCharactersInScene(StringBuilder userPrompt, AgentContext context) {
        if (context.getCharacterIdsInScene() == null || context.getCharacterIdsInScene().isEmpty()) {
            return;
        }
        userPrompt.append("=== CHARACTERS IN SCENE (use these exact characterId values in your output) ===\n");
        for (String charId : context.getCharacterIdsInScene()) {
            String name = context.getCharacterNames() != null
                ? context.getCharacterNames().getString(charId, "Unknown") : "Unknown";
            userPrompt.append("- ").append(name);
            String pronouns = context.getCharacterPronouns() != null
                ? context.getCharacterPronouns().getString(charId) : null;
            if (pronouns != null && !pronouns.isBlank()) {
                userPrompt.append(" (").append(pronouns).append(")");
            }
            userPrompt.append(" → characterId: \"").append(charId).append("\"\n");
            String description = context.getCharacterDescriptions() != null
                ? context.getCharacterDescriptions().getString(charId) : null;
            if (description != null && !description.isBlank()) {
                userPrompt.append("  Role: ").append(description).append("\n");
            }
            String profile = context.getCharacterProfiles() != null
                ? CharacterFacts.describeProfile(context.getCharacterProfiles().getJsonObject(charId)) : "";
            if (!profile.isBlank()) {
                userPrompt.append("  Facts: ").append(profile).append("\n");
            }
            String attributes = context.getCharacterAttributes() != null
                ? CharacterFacts.describeAttributes(context.getCharacterAttributes().getJsonObject(charId)) : "";
            if (!attributes.isBlank()) {
                userPrompt.append("  Base attributes (fixed; not story stats; 1-20, 10 is average): ")
                    .append(attributes).append("\n");
            }
        }
        userPrompt.append("\n");
    }
}
