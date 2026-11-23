package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.map.StagePrompt;
import com.eonmux.chatllm.services.AgentModelConfigService;
import com.eonmux.chatllm.utils.CharacterFacts;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class SkepticAgent extends BaseAgent {

    public enum Pass {
        NARRATION,   // Pass 1: evaluate narrator output for plot realism
        CHARACTER,   // Pass 2: evaluate character response for metagaming/OOC
        CASTING      // Pass 3: evaluate character proposal for lore/balance/fit
    }

    public SkepticAgent(Vertx vertx, Pool mysqlPool, AppConfig config,
                        AgentModelConfigService modelConfigService) {
        super("skeptic", vertx, mysqlPool, config, modelConfigService);
    }

    @Override
    protected Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
        // Default to character pass if no specific pass requested.
        // evaluate() handles its own logging, so mark the result to skip BaseAgent's log.
        return evaluate(context, Pass.CHARACTER, resolvedModel)
            .map(result -> {
                result.addAnnotation("_skipBaseLog", "true");
                return result;
            });
    }

    /**
     * Evaluate content with a specific pass (NARRATION, CHARACTER, or CASTING).
     * Legacy overload — resolves model internally.
     */
    public Uni<AgentResult> evaluate(AgentContext context, Pass pass) {
        return evaluate(context, pass, null);
    }

    /**
     * Evaluate content with a specific pass and an explicitly resolved model.
     * Resolves model internally if resolvedModel is null/empty.
     */
    public Uni<AgentResult> evaluate(AgentContext context, Pass pass, String resolvedModel) {
        // Check if this pass is enabled
        if (pass == Pass.NARRATION && !config.isAgentSkepticNarrationGateEnabled()) {
            log.debug("[skeptic] Narration gate disabled, skipping");
            return Uni.createFrom().item(AgentResult.skipped(agentName, "Narration gate disabled"));
        }
        if (pass == Pass.CHARACTER && !config.isAgentSkepticCharacterGateEnabled()) {
            log.debug("[skeptic] Character gate disabled, skipping");
            return Uni.createFrom().item(AgentResult.skipped(agentName, "Character gate disabled"));
        }
        if (pass == Pass.CASTING && !config.isAgentCastingEnabled()) {
            log.debug("[skeptic] Casting gate disabled, skipping");
            return Uni.createFrom().item(AgentResult.skipped(agentName, "Casting gate disabled"));
        }

        long startTime = System.currentTimeMillis();

        // Resolve model: use the explicitly passed model, or resolve per-agent.
        // Does NOT write to context — avoids race conditions with parallel agents.
        Uni<String> modelUni;
        if (resolvedModel != null && !resolvedModel.isEmpty()) {
            modelUni = Uni.createFrom().item(resolvedModel);
        } else if (context.getConversationId() != null) {
            modelUni = resolveModel(context.getConversationId());
        } else {
            modelUni = Uni.createFrom().item("");
        }

        return modelUni.chain(model -> {
            String systemPrompt = switch (pass) {
                case NARRATION -> AgentPromptTemplates.SKEPTIC_NARRATION_SYSTEM;
                case CHARACTER -> AgentPromptTemplates.SKEPTIC_CHARACTER_SYSTEM;
                case CASTING -> AgentPromptTemplates.SKEPTIC_CASTING_SYSTEM;
            };

            StringBuilder userPrompt = new StringBuilder();

            switch (pass) {
                case NARRATION -> buildNarrationEvalPrompt(userPrompt, context);
                case CHARACTER -> buildCharacterEvalPrompt(userPrompt, context);
                case CASTING -> buildCastingEvalPrompt(userPrompt, context);
            }

            return callJudgment(systemPrompt, userPrompt.toString(), 500, model, JudgmentProtocol::review)
                .map(response -> {
                    String content = extractContent(response);
                    JsonObject usage = extractUsage(response);

                    SkepticVerdict verdict = parseVerdict(content);

                    JsonObject structured = new JsonObject()
                        .put("pass", pass.name().toLowerCase())
                        .put("approved", verdict.isApproved())
                        .put("reason", verdict.getReason())
                        .put("severity", verdict.getSeverity())
                        .put("category", verdict.getCategory())
                        .put("suggestion", verdict.getSuggestion());

                    log.info("[skeptic:{}] Verdict: {} - {}", pass.name().toLowerCase(),
                        verdict.isApproved() ? "APPROVED" : "REJECTED", verdict.getReason());

                    AgentResult result = AgentResult.builder()
                        .agentName(agentName + ":" + pass.name().toLowerCase())
                        .content(content)
                        .structuredOutput(structured)
                        .status(verdict.isApproved() ? "success" : "rejected")
                        .modelUsed(model)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .inputTokens(usage.getInteger("prompt_tokens", 0))
                        .outputTokens(usage.getInteger("completion_tokens", 0))
                        .build();

                    // Log the run asynchronously (fire-and-forget)
                    logSkepticRun(context.getConversationId(), context.getTriggerMessageId(), result);

                    return result;
                });
        }).onFailure().call(error -> logRun(context.getConversationId(), context.getTriggerMessageId(),
            AgentResult.error("skeptic", error.getMessage()), "skeptic_" + pass.name().toLowerCase()));
    }

    /**
     * Log a Skeptic evaluation run to the agent_run table.
     */
    private void logSkepticRun(String conversationId, String triggerMessageId, AgentResult result) {
        if (conversationId == null) return;
        String runId = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO agent_run (runId, conversationId, triggerMessageId, agentName, agentPass, modelUsed,
                inputTokens, outputTokens, durationMs, output, status, retryCount, errorMessage)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        String agentPass = null;
        if (result.getAgentName() != null && result.getAgentName().contains(":")) {
            agentPass = result.getAgentName().substring(result.getAgentName().indexOf(':') + 1);
        }
        String outputJson = result.getStructuredOutput() != null
            ? result.getStructuredOutput().encode()
            : new JsonObject().put("content", result.getContent() != null ? result.getContent() : "").encode();

        mysqlPool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(runId).addValue(conversationId).addValue(triggerMessageId).addValue(agentName)
                .addValue(agentPass).addValue(result.getModelUsed()).addValue(result.getInputTokens())
                .addValue(result.getOutputTokens()).addValue(result.getDurationMs()).addValue(outputJson)
                .addValue(result.getStatus()).addValue(0).addValue(null))
            .subscribe().with(
                rows -> {},
                err -> log.warn("[skeptic] Failed to log run: {}", err.getMessage()));
    }

    /**
     * Parse the Skeptic's LLM output into a SkepticVerdict.
     * Only a complete, schema-valid object is a verdict; malformed output is never inferred.
     */
    public SkepticVerdict parseVerdict(String content) {
        JsonObject json = JudgmentProtocol.object(content);
        if (!JudgmentProtocol.review(json)) throw new IllegalArgumentException("Invalid review verdict");
        return SkepticVerdict.builder().approved(json.getBoolean("approved"))
            .reason(json.getString("reason", "" )).severity(json.getString("severity", "minor"))
            .category(json.getString("category", "")).suggestion(json.getString("suggestion", "")).build();
    }

    void buildNarrationEvalPrompt(StringBuilder sb, AgentContext context) {
        if (context.getPlayerName() != null && !context.getPlayerName().isBlank()) {
            sb.append("=== PLAYER PRESENT IN SCENE ===\n").append(context.getPlayerName())
                .append(" is present in addition to the NPC roster below. Do not count only NPCs or remove the player from the scene.\n\n");
        }
        if (context.getStageMoveDescription() != null)
            sb.append("=== SERVER-CONFIRMED TRAVEL THIS ROUND ===\n").append(context.getStageMoveDescription())
                .append("\nThis already happened and MUST have a passage/arrival block. Its origin can differ from the current STAGE destination.\n\n");
        if (context.getInteractionResult() != null)
            sb.append("=== RESOLVED INTERACTION REQUIRING A RESULT BLOCK ===\n").append(context.getInteractionResult()).append("\n\n");
        // Narrator output to evaluate
        if (context.getNarratorOutput() != null) {
            sb.append("=== NARRATOR OUTPUT TO EVALUATE ===\n");
            sb.append(context.getNarratorOutput()).append("\n\n");
        }

        if (context.getStage() != null && context.getStage().isPresent()) {
            sb.append(StagePrompt.format(context.getStage(), StagePrompt.Audience.SKEPTIC));
        }

        // Fixed character facts let the gate catch a changed height, build,
        // hair color, or eye color instead of approving any description.
        if (context.getCharacterIdsInScene() != null && !context.getCharacterIdsInScene().isEmpty()) {
            sb.append("=== CHARACTERS IN SCENE (fixed facts) ===\n");
            for (String charId : context.getCharacterIdsInScene()) {
                appendCharacterFacts(sb, context, charId);
            }
            sb.append("\n");
        }

        // World state for context
        if (context.getWorldState() != null) {
            sb.append("=== WORLD STATE ===\n");
            sb.append("Location: ").append(context.getWorldState().getString("currentLocation", "unknown")).append("\n");
            sb.append("Time: ").append(context.getWorldState().getString("timeOfDay", "unknown")).append("\n");
            sb.append("Mood: ").append(context.getWorldState().getString("mood", "neutral")).append("\n\n");
        }

        // Template rules
        if (context.getTemplateRules() != null) {
            sb.append("=== STORY RULES (must not be violated) ===\n");
            sb.append(context.getTemplateRules().encodePrettily()).append("\n\n");
        }

        // Established story facts from RAG
        if (context.getRetrievedKnowledge() != null && !context.getRetrievedKnowledge().isEmpty()) {
            sb.append("=== ESTABLISHED STORY FACTS ===\n");
            sb.append(context.getRetrievedKnowledge()).append("\n");
        }

        sb.append("Evaluate the narrator output above. Does it maintain plot realism and consistency?");
    }

    private void buildCharacterEvalPrompt(StringBuilder sb, AgentContext context) {
        // Character response to evaluate
        if (context.getTriggerContent() != null) {
            sb.append("=== CHARACTER RESPONSE TO EVALUATE ===\n");
            sb.append(context.getTriggerContent()).append("\n\n");
        }

        // What the character knows (sieved memory)
        if (context.getHighlighterOutput() != null && !context.getHighlighterOutput().isEmpty()) {
            sb.append("=== WHAT THIS CHARACTER KNOWS (sieved memory) ===\n");
            for (int i = 0; i < context.getHighlighterOutput().size(); i++) {
                JsonObject entry = context.getHighlighterOutput().getJsonObject(i);
                sb.append("• ").append(entry.getString("summary", "")).append("\n");
            }
            sb.append("\n");
        }

        // Character personality/traits
        if (context.getCharacterId() != null) {
            boolean known = (context.getCharacterNames() != null
                    && context.getCharacterNames().containsKey(context.getCharacterId()))
                || (context.getCharacterProfiles() != null
                    && context.getCharacterProfiles().containsKey(context.getCharacterId()))
                || (context.getCharacterAttributes() != null
                    && context.getCharacterAttributes().containsKey(context.getCharacterId()));
            if (known) {
                sb.append("=== THIS CHARACTER ===\n");
                appendCharacterFacts(sb, context, context.getCharacterId());
                sb.append("\n");
            }
        }

        if (context.getCharacterSheet() != null) {
            sb.append("=== CHARACTER SHEET ===\n");
            sb.append(context.getCharacterSheet().encodePrettily()).append("\n\n");
        }

        sb.append("Evaluate the character response above. Does it stay in character and only use knowledge the character actually has?");
    }

    private void buildCastingEvalPrompt(StringBuilder sb, AgentContext context) {
        // Character proposal to evaluate (stored in casterOutput)
        if (context.getCasterOutput() != null && !context.getCasterOutput().isEmpty()) {
            sb.append("=== CHARACTER PROPOSAL TO EVALUATE ===\n");
            sb.append(context.getCasterOutput()).append("\n\n");
        }

        // World state for context
        if (context.getWorldState() != null) {
            sb.append("=== WORLD STATE ===\n");
            sb.append("Location: ").append(context.getWorldState().getString("currentLocation", "unknown")).append("\n");
            sb.append("Time: ").append(context.getWorldState().getString("timeOfDay", "unknown")).append("\n");
            sb.append("Mood: ").append(context.getWorldState().getString("mood", "neutral")).append("\n\n");
        }

        // Template rules
        if (context.getTemplateRules() != null) {
            sb.append("=== STORY RULES (must not be violated) ===\n");
            sb.append(context.getTemplateRules().encodePrettily()).append("\n\n");
        }

        // Established story facts from RAG
        if (context.getRetrievedKnowledge() != null && !context.getRetrievedKnowledge().isEmpty()) {
            sb.append("=== ESTABLISHED STORY FACTS ===\n");
            sb.append(context.getRetrievedKnowledge()).append("\n");
        }

        sb.append("Evaluate the character proposal above. Does it fit the world, maintain lore consistency, and have appropriate power level?");
    }

    /**
     * One compact block of facts the story already fixed about a character.
     * Empty sections are skipped, so a partial record stays honest.
     */
    void appendCharacterFacts(StringBuilder sb, AgentContext context, String charId) {
        String name = context.getCharacterNames() != null
            ? context.getCharacterNames().getString(charId) : null;
        sb.append("- ").append(name == null || name.isBlank() ? charId : name);
        String pronouns = context.getCharacterPronouns() != null
            ? context.getCharacterPronouns().getString(charId) : null;
        if (pronouns != null && !pronouns.isBlank()) {
            sb.append(" (").append(pronouns).append(")");
        }
        sb.append("\n");
        String description = context.getCharacterDescriptions() != null
            ? context.getCharacterDescriptions().getString(charId) : null;
        if (description != null && !description.isBlank()) {
            sb.append("  Role: ").append(description).append("\n");
        }
        String profile = context.getCharacterProfiles() != null
            ? CharacterFacts.describeProfile(context.getCharacterProfiles().getJsonObject(charId)) : "";
        if (!profile.isBlank()) {
            sb.append("  Facts: ").append(profile).append("\n");
        }
        String attributes = context.getCharacterAttributes() != null
            ? CharacterFacts.describeAttributes(context.getCharacterAttributes().getJsonObject(charId)) : "";
        if (!attributes.isBlank()) {
            sb.append("  Base attributes (fixed limits; 1-20, 10 is average): ").append(attributes).append("\n");
        }
    }
}
