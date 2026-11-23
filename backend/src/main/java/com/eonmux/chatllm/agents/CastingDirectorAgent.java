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
public class CastingDirectorAgent extends BaseAgent {

    public CastingDirectorAgent(Vertx vertx, Pool mysqlPool, AppConfig config,
                                 AgentModelConfigService modelConfigService) {
        super("caster", vertx, mysqlPool, config, modelConfigService);
    }

    @Override
    protected Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
        return executeCasting(context, context.getCastingPrompt(), resolvedModel);
    }

    /**
     * Generate a character proposal from a [CAST: ...] trigger description.
     * Legacy overload — resolves model internally.
     */
    public Uni<AgentResult> executeCasting(AgentContext context, String castDescription) {
        return executeCasting(context, castDescription, null);
    }

    /**
     * Generate a character proposal with an explicitly resolved model.
     */
    public Uni<AgentResult> executeCasting(AgentContext context, String castDescription, String resolvedModel) {
        StringBuilder userPrompt = new StringBuilder();

        // World state
        if (context.getWorldState() != null) {
            userPrompt.append("=== WORLD STATE ===\n");
            userPrompt.append("Location: ").append(context.getWorldState().getString("currentLocation", "unknown")).append("\n");
            userPrompt.append("Time: ").append(context.getWorldState().getString("timeOfDay", "unknown")).append("\n");
            userPrompt.append("Weather: ").append(context.getWorldState().getString("weather", "clear")).append("\n");
            userPrompt.append("Mood: ").append(context.getWorldState().getString("mood", "neutral")).append("\n\n");
        }

        // Story template rules
        if (context.getStage() != null && context.getStage().isPresent()) {
            userPrompt.append(StagePrompt.format(context.getStage(), StagePrompt.Audience.CASTING));
        }

        // Story template rules
        if (context.getTemplateRules() != null) {
            userPrompt.append("=== STORY RULES ===\n");
            userPrompt.append(context.getTemplateRules().encodePrettily()).append("\n\n");
        }

        // Retrieved long-term knowledge (RAG)
        if (context.getRetrievedKnowledge() != null && !context.getRetrievedKnowledge().isEmpty()) {
            userPrompt.append(context.getRetrievedKnowledge()).append("\n");
        }

        // Existing characters in scene for differentiation
        appendCharactersInScene(userPrompt, context);

        // Recent messages for narrative context
        if (context.getRecentMessages() != null && !context.getRecentMessages().isEmpty()) {
            userPrompt.append("=== RECENT EVENTS ===\n");
            int start = Math.max(0, context.getRecentMessages().size() - 6);
            for (int i = start; i < context.getRecentMessages().size(); i++) {
                JsonObject msg = context.getRecentMessages().getJsonObject(i);
                userPrompt.append(msg.getString("displayName", msg.getString("role", "unknown")));
                userPrompt.append(": ").append(msg.getString("content", "")).append("\n");
            }
            userPrompt.append("\n");
        }

        // The casting trigger
        userPrompt.append("=== CHARACTER REQUEST ===\n");
        userPrompt.append("The narrator has requested a new character: ").append(castDescription).append("\n\n");
        userPrompt.append("Generate a complete character proposal as a JSON object.");

        // Resolve model: use the explicitly passed model, or resolve per-agent
        Uni<String> modelUni;
        if (resolvedModel != null && !resolvedModel.isEmpty()) {
            modelUni = Uni.createFrom().item(resolvedModel);
        } else if (context.getConversationId() != null) {
            modelUni = resolveModel(context.getConversationId());
        } else {
            modelUni = Uni.createFrom().item("");
        }

        return modelUni.chain(model ->
            callLlmJson(AgentPromptTemplates.CASTER_SYSTEM, userPrompt.toString(), 0.7f, 1000, model)
            .map(response -> {
                String content = extractContent(response);
                JsonObject usage = extractUsage(response);

                // Parse JSON using BaseAgent's robust parser (handles markdown fences + truncated JSON repair)
                JsonObject structured = parseJsonResponse(content);
                if (structured == null) {
                    log.warn("[caster] Failed to parse character JSON, wrapping as raw");
                    structured = new JsonObject().put("raw", content);
                }

                return AgentResult.builder()
                    .agentName(agentName)
                    .content(content)
                    .structuredOutput(structured)
                    .status("success")
                    .inputTokens(usage.getInteger("prompt_tokens", 0))
                    .outputTokens(usage.getInteger("completion_tokens", 0))
                    .build();
            })
        );
    }

    /**
     * The caster must know who already exists, not just their names, so the
     * proposal does not duplicate an age, build, or role already on stage.
     */
    static void appendCharactersInScene(StringBuilder userPrompt, AgentContext context) {
        if (context.getCharacterIdsInScene() == null || context.getCharacterIdsInScene().isEmpty()) {
            return;
        }
        userPrompt.append("=== EXISTING CHARACTERS IN SCENE ===\n");
        for (String charId : context.getCharacterIdsInScene()) {
            String name = context.getCharacterNames() != null
                ? context.getCharacterNames().getString(charId, charId) : charId;
            userPrompt.append("- ").append(name).append(" (characterId: ").append(charId);
            String pronouns = context.getCharacterPronouns() != null
                ? context.getCharacterPronouns().getString(charId) : null;
            if (pronouns != null && !pronouns.isBlank()) {
                userPrompt.append(", ").append(pronouns);
            }
            userPrompt.append(")\n");
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
        userPrompt.append("New character must be DISTINCT from existing characters.\n\n");
    }
}
