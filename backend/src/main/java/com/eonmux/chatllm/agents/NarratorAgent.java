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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class NarratorAgent extends BaseAgent {

    public NarratorAgent(Vertx vertx, Pool mysqlPool, AppConfig config,
                         AgentModelConfigService modelConfigService) {
        super("narrator", vertx, mysqlPool, config, modelConfigService);
    }

    @Override
    protected Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
        return executeWithFeedback(context, null, resolvedModel);
    }

    /**
     * Execute with optional Skeptic feedback for retry.
     * Legacy overload — resolves model internally.
     */
    public Uni<AgentResult> executeWithFeedback(AgentContext context, String skepticFeedback) {
        return executeWithFeedback(context, skepticFeedback, null);
    }

    /**
     * Execute with optional Skeptic feedback and an explicitly resolved model.
     */
    public Uni<AgentResult> executeWithFeedback(AgentContext context, String skepticFeedback, String resolvedModel) {
        if (!isOpening(context) && context.getStageMoveDescription() == null
                && context.getStageMoveFailure() == null && context.getInteractionResult() == null) {
            return Uni.createFrom().item(AgentResult.builder().agentName(agentName).status("skipped").content("")
                .structuredOutput(new JsonObject().put("skipped",true)).build());
        }
        if (context.getProgressReporter() != null) context.getProgressReporter().accept("narrator",
            skepticFeedback == null ? "Narrator is writing the scene…" : "Narrator is revising the scene…");
        StringBuilder userPrompt = new StringBuilder();
        if (context.getBattleContext() != null) {
            userPrompt.append("=== AUTHORITATIVE ENCOUNTER ===\n").append(context.getBattleContext().encodePrettily())
                .append("\nRounds and their consequences are already committed. Do not duplicate damage, item use or rewards. "
                    + "While ACTIVE, only the battle actions resolve combat; ordinary chat may express intent or character reactions, "
                    + "but must not decide the player's actions, move the party, declare an outcome, or change combat stats/inventory. "
                    + "After a terminal outcome, acknowledge the consequences and continue from the scene left by the fight.\n\n");
        }

        // Roleplay scenario description — sets the foundation for the scene
        if (context.getRoleplayDescription() != null && !context.getRoleplayDescription().isBlank()) {
            userPrompt.append("=== ROLEPLAY SCENARIO ===\n");
            userPrompt.append(context.getRoleplayDescription()).append("\n");
            userPrompt.append("Your narration must be grounded in this scenario. Use the Place and Room named in the STAGE block; do not invent a different location.\n\n");
        }

        // The player is a character in the scene, not an observer. Naming where
        // they stand keeps the opening from describing only the NPCs.
        String playerName = context.getPlayerName();
        if (playerName != null && !playerName.isBlank()) {
            userPrompt.append("=== THE PLAYER ===\n");
            userPrompt.append(playerName)
                .append(" is present in this scene. Place them in the scene: say where they stand ")
                .append("and what they can see. Never write their dialogue or decide their actions.\n\n");
        }

        InventoryPrompt.appendCommittedActions(userPrompt, context);

        // World state
        if (context.getWorldState() != null) {
            userPrompt.append("=== WORLD STATE ===\n");
            userPrompt.append("Location: ").append(context.getWorldState().getString("currentLocation", "unknown")).append("\n");
            appendIfPresent(userPrompt, "Area", context.getWorldState().getString("currentArea"));
            appendIfPresent(userPrompt, "Scene so far", context.getWorldState().getString("currentScene"));
            userPrompt.append("Time: ").append(context.getWorldState().getString("timeOfDay", "unknown")).append("\n");
            userPrompt.append("Weather: ").append(context.getWorldState().getString("weather", "clear")).append("\n");
            userPrompt.append("Mood: ").append(context.getWorldState().getString("mood", "neutral")).append("\n");
            if (context.getWorldState().getString("currentScene") != null
                    && !context.getWorldState().getString("currentScene").isBlank()) {
                userPrompt.append("Scene so far records what already happened. It is not a plan, ")
                        .append("and it does not give you a new event to narrate.\n");
            }
            userPrompt.append("\n");
        }

        // Map spatial context — locations, coordinates, nearby places
        if (context.getStage() != null && context.getStage().isPresent()) {
            userPrompt.append(StagePrompt.format(context.getStage(), StagePrompt.Audience.NARRATOR));
            userPrompt.append("The STAGE block above is authoritative for the current scene. ")
                    .append("MAP CONTEXT describes the wider world beyond it.\n\n");
        }

        // The stage moved this turn: the STAGE block above already shows the
        // new room, so name the passage explicitly. This is the one turn that
        // must narrate the move, even if the player only gave an order.
        if (context.getStageMoveDescription() != null && !context.getStageMoveDescription().isBlank()) {
            userPrompt.append("=== THE PARTY JUST MOVED ===\n");
            userPrompt.append("This turn the party moved ").append(context.getStageMoveDescription())
                    .append(". Do not output [NO_NARRATION] this turn. ")
                    .append("Open the narration by showing that passage in one sentence, ")
                    .append("then continue from the new room named in the STAGE block.\n\n");
        }

        if (context.getArrivalResident() != null) {
            userPrompt.append("=== LOCAL RESIDENT PRESENT ===\n").append(context.getArrivalResident().getString("description"))
                .append("\nThis resident belongs to the destination, not the traveling party. Describe meeting them after arrival. ")
                .append("Their greeting will follow in their own character message. Do not write their dialogue or add another duplicate host.\n\n");
        }

        if (context.getStageMoveFailure() != null) {
            userPrompt.append("=== MOVEMENT WAS NOT APPLIED ===\n")
                .append(context.getStageMoveFailure()).append("\nThe STAGE still names the actual room. ")
                .append("Do not narrate an arrival or turn a rejected move into an invented passage. ")
                .append("Briefly describe the established obstacle if there is one; do not invent a new threat.\n\n");
        }

        if (context.getInteractionResult() != null) {
            userPrompt.append("=== RESOLVED INTERACTION ===\n").append(context.getInteractionResult())
                .append("\nNarrate the observable result of this interaction, using the committed inventory/world facts. ")
                .append("Do not output [NO_NARRATION] or invent additional player choices, payments or rewards.\n\n");
        }

        // Map spatial context — locations, coordinates, nearby places
        if (context.getMapLocations() != null && !context.getMapLocations().isEmpty()) {
            userPrompt.append("=== MAP CONTEXT ===\n");
            if (context.getMapName() != null) {
                userPrompt.append("Map: ").append(context.getMapName());
                if (context.getMapScale() != null) {
                    userPrompt.append(" (").append(context.getMapScale()).append(" scale)");
                }
                userPrompt.append("\n");
            }

            // Current location details
            if (context.getCurrentMapLocation() != null) {
                JsonObject loc = context.getCurrentMapLocation();
                userPrompt.append("Current position: ").append(loc.getString("name"));
                userPrompt.append(" [").append(loc.getString("locationType", "unknown")).append("]");
                userPrompt.append(" at (").append(loc.getFloat("x", 0f)).append(", ")
                    .append(loc.getFloat("y", 0f)).append(", ").append(loc.getFloat("z", 0f)).append(")\n");
                if (loc.getString("description") != null && !loc.getString("description").isEmpty()) {
                    userPrompt.append("  ").append(loc.getString("description")).append("\n");
                }
                float elevation = loc.getFloat("z", 0f);
                if (elevation > 5) {
                    userPrompt.append("  Elevated terrain (height: ").append(elevation).append(")\n");
                } else if (elevation < -1) {
                    userPrompt.append("  Underground (depth: ").append(Math.abs(elevation)).append(")\n");
                }
            }

            // Nearby locations
            if (context.getNearbyLocations() != null && !context.getNearbyLocations().isEmpty()) {
                userPrompt.append("Nearby locations:\n");
                for (int i = 0; i < context.getNearbyLocations().size(); i++) {
                    JsonObject nearby = context.getNearbyLocations().getJsonObject(i);
                    userPrompt.append("  - ").append(nearby.getString("name"));
                    userPrompt.append(" [").append(nearby.getString("locationType", "unknown")).append("]");
                    if (nearby.containsKey("distance")) {
                        userPrompt.append(" (~").append(nearby.getFloat("distance")).append(" units away)");
                    }
                    if (!nearby.getBoolean("isDiscovered", true)) {
                        userPrompt.append(" [undiscovered]");
                    }
                    userPrompt.append("\n");
                }
            }

            // Entity positions on map
            if (context.getEntityMapPositions() != null && !context.getEntityMapPositions().isEmpty()) {
                userPrompt.append("Entities on map:\n");
                for (int i = 0; i < context.getEntityMapPositions().size(); i++) {
                    JsonObject pos = context.getEntityMapPositions().getJsonObject(i);
                    String entityLabel = pos.containsKey("characterId") ? "Character" : "Player";
                    userPrompt.append("  - ").append(entityLabel);
                    userPrompt.append(" at (").append(pos.getFloat("x")).append(", ")
                        .append(pos.getFloat("y")).append(", ").append(pos.getFloat("z")).append(")\n");
                }
            }

            userPrompt.append("Use this spatial data to ground your descriptions in the physical world. ")
                .append("Reference nearby landmarks and terrain as seen from the STAGE Room, but keep the party ")
                .append("in that Room unless they pass through a listed exit.\n\n");
        }

        // Characters in scene — Narrator must only reference these characters
        appendCharactersInScene(userPrompt, context);

        // Retrieved long-term knowledge (RAG)
        if (context.getRetrievedKnowledge() != null && !context.getRetrievedKnowledge().isEmpty()) {
            userPrompt.append(context.getRetrievedKnowledge()).append("\n");
        }

        // Muse suggestions
        if (context.getMuseOutput() != null && !context.getMuseOutput().isEmpty()) {
            userPrompt.append("=== CREATIVE GUIDANCE (from Muse) ===\n");
            userPrompt.append(context.getMuseOutput()).append("\n\n");
        }

        // Story template rules
        if (context.getTemplateRules() != null) {
            userPrompt.append("=== STORY RULES ===\n");
            userPrompt.append(context.getTemplateRules().encodePrettily()).append("\n\n");
        }

        // Recent messages
        if (context.getRecentMessages() != null && !context.getRecentMessages().isEmpty()) {
            userPrompt.append("=== RECENT EVENTS ===\n");
            int start = Math.max(0, context.getRecentMessages().size() - 8);
            for (int i = start; i < context.getRecentMessages().size(); i++) {
                JsonObject msg = context.getRecentMessages().getJsonObject(i);
                userPrompt.append(msg.getString("displayName", msg.getString("role", "unknown")));
                userPrompt.append(": ").append(msg.getString("content", "")).append("\n");
            }
            userPrompt.append("\n");
        }

        // Player's latest action — the trigger that started this pipeline run
        if (context.getTriggerContent() != null && !context.getTriggerContent().isBlank()
                && !context.getTriggerContent().contains("[SESSION_START]")) {
            userPrompt.append("=== PLAYER ACTION (this is what you must respond to) ===\n");
            userPrompt.append(context.getTriggerContent()).append("\n\n");
        }

        // Skeptic feedback (if this is a retry)
        if (skepticFeedback != null && !skepticFeedback.isEmpty()) {
            userPrompt.append("=== IMPORTANT: REVISION REQUIRED ===\n");
            userPrompt.append("Your previous narration was rejected for the following reason:\n");
            userPrompt.append(skepticFeedback).append("\n");
            userPrompt.append("Please revise your narration to address this issue.\n\n");
        }

        boolean opening = isOpening(context);

        if (opening) {
            userPrompt.append("This is the opening of the story, not a later turn. You must write it. ")
                .append("The stage is new, so the usual reason to stay silent does not apply. ")
                .append("Do not output [NO_NARRATION] for the opening. ")
                .append("Write the opening narration to set the scene, in third person, past tense. ")
                .append("Place ")
                .append(playerName != null && !playerName.isBlank() ? playerName : "the player")
                .append(" in the scene and say where they stand and what they can see. ")
                .append("Establish why these people are together and what is in front of them. ")
                .append("Do not write the player's dialogue or actions. ")
                .append("Every sentence is past tense: write \"The sun cut through the haze\" and \"Elara stood\", never \"cuts\" or \"stands\".");
        } else if (context.getStageMoveDescription() != null && !context.getStageMoveDescription().isBlank()) {
            userPrompt.append("The server has confirmed the move above after the character responses. Narrate the route and arrival now. ")
                .append("Do not output [NO_NARRATION], do not repeat the dialogue, and do not ask for the same decision again. ")
                .append("Keep the prose in third person, past tense and end at the confirmed STAGE Room.");
        } else if (context.getStageMoveFailure() != null) {
            userPrompt.append("The attempted travel was blocked. Briefly explain the established obstacle above, in third person, past tense. ")
                .append("Keep the party at the actual STAGE Room. Do not output [NO_NARRATION] or invent an arrival.");
        } else if (context.getInteractionResult() != null) {
            userPrompt.append("Write the result of the resolved interaction above, in third person, past tense. ")
                .append("This is a physical event needing a result block; do not output [NO_NARRATION].");
        }

        // Resolve model: use the explicitly passed model, or resolve per-agent
        Uni<String> modelUni;
        if (resolvedModel != null && !resolvedModel.isEmpty()) {
            modelUni = Uni.createFrom().item(resolvedModel);
        } else if (context.getConversationId() != null) {
            modelUni = resolveModel(context.getConversationId());
        } else {
            modelUni = Uni.createFrom().item("");
        }

        String prompt = userPrompt.toString();
        return modelUni.chain(model -> callProseOnce(prompt, model)
            .chain(response -> {
                if (opening && openingProducedNothing(extractContent(response))) {
                    // A narrator told to stay silent unless the world changes
                    // can read a brand-new scene as "nothing changed". The
                    // opening is the one turn that must always narrate.
                    log.warn("[narrator] Opening returned no narration; retrying with a direct opening instruction");
                    return callProseOnce(prompt + "\n\n" + OPENING_RETRY, model)
                        .map(retry -> openingProducedNothing(extractContent(retry))
                            ? openingFallbackResponse(context) : retry);
                }
                if (context.getStageMoveDescription() != null && openingProducedNothing(extractContent(response))) {
                    return callProseOnce(prompt + "\nThe movement already committed. Write its passage and arrival; do not stay silent.", model)
                        .map(retry -> openingProducedNothing(extractContent(retry))
                            ? proseResponse(movementFallback(context)) : retry);
                }
                if (context.getStageMoveFailure() != null && openingProducedNothing(extractContent(response))) {
                    return Uni.createFrom().item(proseResponse(movementFallback(context)));
                }
                return Uni.createFrom().item(response);
            })
            .map(this::toResult)
            .invoke(result -> {
                context.setNarratorOutput(result.getContent());
                AgentPipeline.ensureRequiredNarration(context);
                if (!java.util.Objects.equals(context.getNarratorOutput(), result.getContent())) {
                    result.setContent(context.getNarratorOutput());
                    result.setStructuredOutput(new JsonObject().put("narration", result.getContent()).put("fallback", true));
                }
            }));
    }

    /**
     * One prose call. A reasoning model can spend the whole completion budget
     * on the reasoning pass and return no prose, so an empty body gets one
     * retry with that pass disabled.
     */
    private Uni<JsonObject> callProseOnce(String userPrompt, String model) {
        return callLlmProse(AgentPromptTemplates.NARRATOR_SYSTEM, userPrompt, 0.7f, 1000, model)
            .chain(response -> {
                String content = extractContent(response);
                if (content == null || content.isBlank()) {
                    log.warn("[narrator] Empty response, retrying with thinking disabled");
                    return callLlmJson(AgentPromptTemplates.NARRATOR_SYSTEM,
                            userPrompt, 0.7f, 1000, model);
                }
                return Uni.createFrom().item(response);
            });
    }

    /** True for the first turn of a scene, when no player action exists yet. */
    static String movementFallback(AgentContext context) {
        String name = context.getPlayerName();
        String subject = name == null || name.isBlank() ? "The party" : name + " and the party";
        if (context.getStageMoveDescription() == null && context.getStageMoveFailure() != null) {
            String reason = context.getStageMoveFailure().strip();
            return "The party could not proceed: " + reason + (reason.endsWith(".") ? "" : ".");
        }
        return subject + " moved " + context.getStageMoveDescription() + ".";
    }

    private static JsonObject proseResponse(String content) {
        return new JsonObject().put("choices", new JsonArray().add(new JsonObject()
                .put("message", new JsonObject().put("content", content))));
    }

    static boolean isOpening(AgentContext context) {
        String trigger = context.getTriggerContent();
        return trigger == null || trigger.isBlank() || trigger.contains("[SESSION_START]");
    }

    private static boolean openingProducedNothing(String content) {
        return content == null || content.isBlank() || isNoNarration(content);
    }

    /**
     * True when the model answered with the silent-turn sentinel. Models
     * sometimes wrap it in markdown emphasis, as in {@code **[NO_NARRATION]**},
     * so compare the sentinel after leading emphasis and whitespace.
     */
    static boolean isNoNarration(String content) {
        if (content == null) return false;
        String text = content.strip().replaceFirst("^[*_`\"'\\s]+", "");
        return text.regionMatches(true, 0, "[NO_NARRATION]", 0, "[NO_NARRATION]".length());
    }

    private static final String OPENING_RETRY = """
            THE STORY HAS NOT OPENED YET. This is not a moment to skip.
            Write the opening narration now: place the scene in the STAGE Room, put the player in it,
            and show what is in front of the group. Output prose only.
            Do not output [NO_NARRATION].""";

    /**
     * Last resort when the model refuses to open the story twice. A short,
     * factual stage line is better than a conversation that begins with
     * characters standing nowhere.
     */
    static String openingFallback(AgentContext context) {
        String place = null;
        String area = null;
        var stage = context.getStage();
        if (stage != null && stage.isPresent()) {
            place = stage.getNode().getName();
            area = stage.getArea().getName();
        } else if (context.getWorldState() != null) {
            place = context.getWorldState().getString("currentLocation");
            area = context.getWorldState().getString("currentArea");
        }

        StringBuilder text = new StringBuilder("The story opened");
        if (area != null && !area.isBlank()) {
            text.append(" in the ").append(area.strip());
        }
        if (place != null && !place.isBlank()) {
            text.append(area != null && !area.isBlank() ? " of " : " at ").append(place.strip());
        }
        text.append('.');

        List<String> names = new ArrayList<>();
        if (context.getCharacterIdsInScene() != null && context.getCharacterNames() != null) {
            for (String id : context.getCharacterIdsInScene()) {
                String name = context.getCharacterNames().getString(id);
                if (name != null && !name.isBlank()) names.add(name.strip());
            }
        }
        if (context.getPlayerName() != null && !context.getPlayerName().isBlank()) {
            names.add(context.getPlayerName().strip());
        }
        if (!names.isEmpty()) {
            text.append(' ').append(joinNames(names)).append(" were present.");
        }
        return text.toString();
    }

    private static String joinNames(List<String> names) {
        if (names.size() == 1) return names.get(0);
        if (names.size() == 2) return names.get(0) + " and " + names.get(1);
        return String.join(", ", names.subList(0, names.size() - 1))
                + ", and " + names.get(names.size() - 1);
    }

    private static JsonObject openingFallbackResponse(AgentContext context) {
        JsonObject message = new JsonObject()
                .put("role", "assistant")
                .put("content", openingFallback(context));
        return new JsonObject().put("choices", new JsonArray().add(
                new JsonObject().put("message", message)));
    }

    private AgentResult toResult(JsonObject response) {
        String content = extractContent(response);
        if (content == null) content = "";
        JsonObject usage = extractUsage(response);

        // Narrator decided no narration is needed for this turn
        if (isNoNarration(content)) {
            log.info("[narrator] Skipping narration (pure dialogue or no action detected)");
            JsonObject structured = new JsonObject().put("skipped", true);
            return AgentResult.builder()
                .agentName(agentName)
                .content("")
                .structuredOutput(structured)
                .status("success")
                .inputTokens(usage.getInteger("prompt_tokens", 0))
                .outputTokens(usage.getInteger("completion_tokens", 0))
                .build();
        }

        // Extract [IMAGE: ...] tags
        JsonObject structured = new JsonObject();
        Pattern imagePattern = Pattern.compile("\\[IMAGE:\\s*([^\\]]+)\\]");
        Matcher matcher = imagePattern.matcher(content);
        JsonArray imageTriggers = new JsonArray();
        while (matcher.find()) {
            imageTriggers.add(matcher.group(1).trim());
        }
        if (!imageTriggers.isEmpty()) {
            structured.put("imageTriggers", imageTriggers);
        }

        // Extract [CAST: ...] tags
        Pattern castPattern = Pattern.compile("\\[CAST:\\s*([^\\]]+)\\]");
        Matcher castMatcher = castPattern.matcher(content);
        JsonArray castingTriggers = new JsonArray();
        while (castMatcher.find()) {
            castingTriggers.add(castMatcher.group(1).trim());
        }
        if (!castingTriggers.isEmpty()) {
            structured.put("castingTriggers", castingTriggers);
        }

        // Strip both [IMAGE: ...] and [CAST: ...] from clean output
        String cleanContent = content
            .replaceAll("\\[IMAGE:\\s*[^\\]]+\\]", "")
            .replaceAll("\\[CAST:\\s*[^\\]]+\\]", "")
            .trim();

        structured.put("narration", cleanContent);

        return AgentResult.builder()
            .agentName(agentName)
            .content(cleanContent)
            .structuredOutput(structured)
            .status("success")
            .inputTokens(usage.getInteger("prompt_tokens", 0))
            .outputTokens(usage.getInteger("completion_tokens", 0))
            .build();
    }

    /**
     * The scene roster. Facts are printed only when the character has them, so
     * a partial record stays honest and the model never fills a gap by guessing.
     */
    static void appendCharactersInScene(StringBuilder userPrompt, AgentContext context) {
        if (context.getCharacterIdsInScene() == null || context.getCharacterIdsInScene().isEmpty()) {
            return;
        }
        userPrompt.append("=== CHARACTERS IN SCENE ===\n");
        for (String charId : context.getCharacterIdsInScene()) {
            String name = context.getCharacterNames() != null
                ? context.getCharacterNames().getString(charId, charId) : charId;
            userPrompt.append("- ").append(name);
            String pronouns = context.getCharacterPronouns() != null
                ? context.getCharacterPronouns().getString(charId) : null;
            if (pronouns != null && !pronouns.isBlank()) {
                userPrompt.append(" (pronouns: ").append(pronouns).append(")");
            }
            String description = context.getCharacterDescriptions() != null
                ? context.getCharacterDescriptions().getString(charId) : null;
            if (description != null && !description.isBlank()) {
                userPrompt.append(" — ").append(description);
            }
            if (context.getCharacterSheet() != null) {
                JsonObject sheet = context.getCharacterSheet().getJsonObject(charId);
                if (sheet != null) {
                    userPrompt.append(" (").append(sheet.getJsonArray("inventory", new JsonArray()).size())
                        .append(" items, ").append(sheet.getJsonArray("stats", new JsonArray()).size())
                        .append(" stats)");
                }
            }
            userPrompt.append("\n");
            String appearance = context.getCharacterAppearances() != null
                ? context.getCharacterAppearances().getString(charId) : null;
            if (appearance != null && !appearance.isBlank()) {
                userPrompt.append("  Looks like: ").append(appearance).append("\n");
            }
            String profile = context.getCharacterProfiles() != null
                ? CharacterFacts.describeProfile(context.getCharacterProfiles().getJsonObject(charId)) : "";
            if (!profile.isBlank()) {
                userPrompt.append("  Facts: ").append(profile).append("\n");
            }
        }
        userPrompt.append("IMPORTANT: Only reference characters listed above. Do NOT invent new characters.\n");
        userPrompt.append("Use each full name above. Do not replace it with a label like \"the stranger\", a shortened name, or a family name. ");
        userPrompt.append("Use the pronouns listed for each character. Use \"they\" only when no pronouns are listed. ");
        userPrompt.append("Use the role and relationship in each description. Do not invent a family relationship. ");
        userPrompt.append("The appearance line and the physical facts are fixed. Use only what they name. Do not add a tail, ears, a scar, a height, a build, a hair color, an eye color, or another body part they do not name. ");
        userPrompt.append("If an appearance line calls something an accessory, it is clothing, not a body part.\n\n");
    }

    private static void appendIfPresent(StringBuilder prompt, String label, String value) {
        if (value != null && !value.isBlank()) {
            prompt.append(label).append(": ").append(value).append("\n");
        }
    }
}
