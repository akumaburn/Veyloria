package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.map.StagePrompt;
import com.eonmux.chatllm.services.AgentModelConfigService;
import com.eonmux.chatllm.utils.CharacterFacts;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MuseAgent extends BaseAgent {

    public MuseAgent(Vertx vertx, Pool mysqlPool, AppConfig config,
                     AgentModelConfigService modelConfigService) {
        super("muse", vertx, mysqlPool, config, modelConfigService);
    }

    @Override
    protected Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
        StringBuilder userPrompt = new StringBuilder();

        // Roleplay scenario description
        if (context.getRoleplayDescription() != null && !context.getRoleplayDescription().isBlank()) {
            userPrompt.append("=== ROLEPLAY SCENARIO ===\n");
            userPrompt.append(context.getRoleplayDescription()).append("\n\n");
        }

        // Story template rules
        if (context.getTemplateRules() != null) {
            userPrompt.append("=== STORY RULES ===\n");
            userPrompt.append(context.getTemplateRules().encodePrettily()).append("\n\n");
        }

        // World state
        if (context.getWorldState() != null) {
            userPrompt.append("=== CURRENT WORLD STATE ===\n");
            userPrompt.append("Location: ").append(context.getWorldState().getString("currentLocation", "unknown")).append("\n");
            userPrompt.append("Time: ").append(context.getWorldState().getString("timeOfDay", "unknown")).append("\n");
            userPrompt.append("Day: ").append(context.getWorldState().getInteger("dayNumber", 1)).append("\n");
            userPrompt.append("Mood: ").append(context.getWorldState().getString("mood", "neutral")).append("\n\n");
        }

        // Retrieved long-term knowledge (RAG)
        if (context.getStage() != null && context.getStage().isPresent()) {
            userPrompt.append(StagePrompt.format(context.getStage(), StagePrompt.Audience.MUSE));
        }

        // Retrieved long-term knowledge (RAG)
        if (context.getRetrievedKnowledge() != null && !context.getRetrievedKnowledge().isEmpty()) {
            userPrompt.append(context.getRetrievedKnowledge()).append("\n");
        }

        // Characters in scene — Muse should only suggest plots involving these characters
        appendCharactersInScene(userPrompt, context);

        // Recent highlights (short-term summary)
        if (context.getHighlighterOutput() != null && !context.getHighlighterOutput().isEmpty()) {
            userPrompt.append("=== RECENT HIGHLIGHTS ===\n");
            for (int i = 0; i < context.getHighlighterOutput().size(); i++) {
                JsonObject entry = context.getHighlighterOutput().getJsonObject(i);
                userPrompt.append("• ").append(entry.getString("summary", "")).append("\n");
            }
            userPrompt.append("\n");
        }

        // Recent messages (short-term memory)
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

        // Player's latest action — helps Muse suggest relevant plot hooks
        if (context.getTriggerContent() != null && !context.getTriggerContent().isBlank()
                && !context.getTriggerContent().contains("[SESSION_START]")) {
            userPrompt.append("=== LATEST PLAYER ACTION ===\n");
            userPrompt.append(context.getTriggerContent()).append("\n\n");
        }

        userPrompt.append("Offer one optional grounded storytelling suggestion, without making any decisions.");
        return callLlmProse(AgentPromptTemplates.MUSE_SYSTEM, userPrompt.toString(), 0.8f, 400, resolvedModel)
            .map(response -> {
                String suggestion = extractContent(response);
                return AgentResult.builder().agentName(agentName).status("success").content(suggestion)
                    .structuredOutput(new JsonObject().put("suggestion", suggestion))
                    .inputTokens(extractUsage(response).getInteger("prompt_tokens",0))
                    .outputTokens(extractUsage(response).getInteger("completion_tokens",0)).build();
            });
    }

    /**
     * The Muse needs to know who is present to suggest a hook that fits them.
     * Names alone are not enough for a character-driven suggestion.
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
                userPrompt.append(" (").append(pronouns).append(")");
            }
            userPrompt.append("\n");
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
        }
        userPrompt.append("Only suggest plot hooks and opportunities involving these characters.\n\n");
    }
}
