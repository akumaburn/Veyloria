package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.map.StagePrompt;
import com.eonmux.chatllm.services.AgentModelConfigService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;

/** Two independent, small decisions. Each instance makes only its own request. */
public class SceneDecisionAgent extends BaseAgent {
    public enum Question { TRAVEL, INTERACTION, ENCOUNTER }
    private final Question question;
    private final boolean travel;

    SceneDecisionAgent(boolean travel, Vertx vertx, Pool pool, AppConfig config, AgentModelConfigService models) {
        this(travel ? Question.TRAVEL : Question.INTERACTION, vertx, pool, config, models);
    }
    public SceneDecisionAgent(Question question, Vertx vertx, Pool pool, AppConfig config, AgentModelConfigService models) {
        super(question.name().toLowerCase() + "_decision", vertx, pool, config, models);
        this.question = question;
        this.travel = question == Question.TRAVEL;
    }

    static final String TRAVEL_PROMPT = """
        Resolve ONLY travel for the completed player/character round. Do not track inventory, stats,
        relationships, plot hooks, or write narration. The latest PLAYER ACTION is the authority.
        A committed action like "Let's check the Inn first" means go there now after the replies,
        even if characters only describe setting off. Questions, conditional/future plans, cancelled
        plans and NPC suggestions alone do not authorize travel. Existing connected STAGE areas/places
        are the only destinations. Choose their exact ID or name. Do not invent routes or blockers.
        Return ONLY JSON: {"move":true,"currentArea":"exact area ID/name"} for local travel,
        or {"move":true,"currentLocation":"exact destination name"} for a gateway departure.
        For an established obstacle preventing committed travel return {"move":false,"movementBlockedReason":"concrete obstacle"}.
        Otherwise return {"move":false}. No other fields. Never resolve a fight or active journey in chat.
        """;
    static final String INTERACTION_PROMPT = """
        Resolve ONLY whether this completed round contains a concrete non-travel interaction needing
        a narrated result. Do not decide travel, invent plot hooks, track stats/inventory, or write a full narration.
        The PLAYER ACTION and completed exchange establish what happens. Opening/searching/examining
        something, using or handing over an item, and a concrete request to a present NPC can need a result.
        Pure dialogue/planning, cosmetic gestures, repeating an already resolved action, hypotheticals,
        OOC, and a character merely adjusting their clothes do not need a result block.
        Return ONLY {"occurred":false} or {"occurred":true,"result":"one short third-person past-tense summary of the grounded interaction to resolve"}.
        Do not invent success, payment, damage, loot, an NPC response or secret knowledge not established
        by the exchange. State uncertain outcomes as attempts; the actual inventory/battle services remain authoritative.
        """;

    static final String ENCOUNTER_PROMPT = """
        Judge ONLY whether an immediate hostile encounter is established in this completed round.
        Return {"encounter":false} for discussion, jokes, hypotheticals, memories, friendly meetings,
        vague danger, or an already active battle. Never invent opponents or resolve combat.
        When immediate hostile resistance is established, return exactly
        {"encounter":true,"enemyName":"established opponent","enemyDescription":"brief established details","enemyHp":20}.
        Positive finite enemyHp must be at most 1000000. No other decisions, narration, effects or fields.
        """;
    private String system() {
        return switch (question) { case TRAVEL -> TRAVEL_PROMPT; case INTERACTION -> INTERACTION_PROMPT; case ENCOUNTER -> ENCOUNTER_PROMPT; };
    }

    @Override protected Uni<AgentResult> execute(AgentContext context, String model) {
        StringBuilder prompt = new StringBuilder("=== PLAYER ACTION ===\n").append(context.getPlayerName()).append(": ")
            .append(context.getTriggerContent()).append('\n');
        if (context.getStage() != null && context.getStage().isPresent())
            prompt.append(StagePrompt.format(context.getStage(), travel ? StagePrompt.Audience.TRAVEL : StagePrompt.Audience.STATE));
        if (travel && context.getMapLocations() != null) {
            prompt.append("\n=== KNOWN WORLD DESTINATIONS (server validates the route through a reachable gateway) ===\n");
            for (Object raw : context.getMapLocations()) {
                JsonObject location = (JsonObject) raw;
                if (Boolean.TRUE.equals(location.getBoolean("isDiscovered")))
                    prompt.append(location.getString("name")).append('\n');
            }
        }
        if (context.getWorldState() != null) prompt.append("\n=== ESTABLISHED STATE ===\n").append(context.getWorldState().encode());
        if (context.getRecentMessages() != null) {
            prompt.append("\n=== COMPLETED ROUND ===\n");
            int start = Math.max(0, context.getRecentMessages().size() - 10);
            for (int i = context.getRecentMessages().size() - 1; i >= 0; i--) {
                if ("user".equalsIgnoreCase(context.getRecentMessages().getJsonObject(i).getString("role"))) { start = i; break; }
            }
            for (int i = start; i < context.getRecentMessages().size(); i++) {
                JsonObject message = context.getRecentMessages().getJsonObject(i);
                prompt.append(message.getString("displayName", message.getString("role", "unknown")))
                    .append(": ").append(message.getString("content", "")).append('\n');
            }
        }
        return decide(prompt.toString(), model, false);
    }

    private Uni<AgentResult> decide(String prompt, String model, boolean retry) {
        return callJudgment(system(), prompt, 350, model, this::valid)
            .map(response -> {
                JsonObject value = JudgmentProtocol.object(extractContent(response));
                return AgentResult.builder().agentName(agentName).status("success").content(value.encode()).structuredOutput(value)
                    .inputTokens(extractUsage(response).getInteger("prompt_tokens", 0))
                    .outputTokens(extractUsage(response).getInteger("completion_tokens", 0)).build();
            });
    }

    private boolean valid(JsonObject value) {
        if (question == Question.ENCOUNTER) {
            if (!(value.getValue("encounter") instanceof Boolean)) return false;
            return !value.getBoolean("encounter") ? value.size() == 1
                : value.size() == 4 && JudgmentProtocol.keys(value,"encounter","enemyName","enemyDescription","enemyHp")
                    && JudgmentProtocol.text(value,"enemyName") && value.getValue("enemyDescription") instanceof String
                    && JudgmentProtocol.number(value,"enemyHp") && value.getDouble("enemyHp") > 0 && value.getDouble("enemyHp") <= 1000000;
        }
        if (travel) {
            if (!(value.getValue("move") instanceof Boolean)) return false;
            if (!value.getBoolean("move")) return value.size() == 1
                || (value.size() == 2 && JudgmentProtocol.keys(value,"move","movementBlockedReason")
                    && JudgmentProtocol.text(value,"movementBlockedReason"));
            return value.size() == 2 && JudgmentProtocol.keys(value,"move","currentArea","currentLocation")
                && (JudgmentProtocol.text(value,"currentArea") ^ JudgmentProtocol.text(value,"currentLocation"));
        }
        return value.getValue("occurred") instanceof Boolean && (value.getBoolean("occurred")
            ? value.size() == 2 && JudgmentProtocol.keys(value,"occurred","result") && JudgmentProtocol.text(value,"result")
            : value.size() == 1);
    }
}
