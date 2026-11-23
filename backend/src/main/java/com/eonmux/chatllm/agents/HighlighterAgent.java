package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.services.AgentModelConfigService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class HighlighterAgent extends BaseAgent {

    public HighlighterAgent(Vertx vertx, Pool mysqlPool, AppConfig config,
                            AgentModelConfigService modelConfigService) {
        super("highlighter", vertx, mysqlPool, config, modelConfigService);
    }

    @Override
    protected Uni<AgentResult> execute(AgentContext context, String resolvedModel) {
        if (context.getRecentMessages() == null || context.getRecentMessages().isEmpty()) {
            return Uni.createFrom().item(AgentResult.skipped(agentName, "No messages to summarize"));
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Summarize the following conversation history into tagged bullet points.\n\n");

        // Include character IDs in scene for visibility tagging. Names make the
        // mapping unambiguous, so the model tags the id it actually means.
        appendCharactersInScene(userPrompt, context);
        if (context.getUserId() != null) userPrompt.append("Player visibility id: ").append(context.getUserId()).append("\n");

        userPrompt.append("=== CONVERSATION HISTORY ===\n");
        int start = 0;
        for (int i = context.getRecentMessages().size()-1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(context.getRecentMessages().getJsonObject(i).getString("role"))) { start = i; break; }
        }
        for (int i = start; i < context.getRecentMessages().size(); i++) {
            JsonObject msg = context.getRecentMessages().getJsonObject(i);
            String role = msg.getString("role", "user");
            String content = msg.getString("content", "");
            String displayName = msg.getString("displayName", role);
            userPrompt.append(displayName).append(": ").append(content).append("\n");
        }

        JsonArray entries = new JsonArray();
        return collectEntry(context, userPrompt.toString(), resolvedModel, entries)
            .call(() -> AgentPipeline.requireCurrentRound(mysqlPool, context))
            .call(() -> cacheHighlighterOutput(context.getConversationId(), entries, context.getTriggerMessageId()))
            .replaceWith(() -> AgentResult.builder().agentName(agentName).status("success")
                .content(entries.encode()).structuredOutput(new JsonObject().put("entries",entries)).build());
    }

    private Uni<Void> collectEntry(AgentContext context, String prompt, String model, JsonArray accepted) {
        return callJudgment(AgentPromptTemplates.HIGHLIGHTER_SYSTEM,
            prompt + "\nAccepted entries (do not repeat): " + accepted.encode(), 550, model, value -> validEntry(value,context))
            .chain(response -> {
                JsonObject entry = JudgmentProtocol.object(extractContent(response)).getJsonObject("entry");
                if (entry == null) return Uni.createFrom().voidItem();
                if (accepted.size() >= 8) return Uni.createFrom().failure(new IllegalStateException("Memory extraction exceeded its entry limit"));
                for (Object previous : accepted) if (((JsonObject) previous).getString("summary").strip().equalsIgnoreCase(entry.getString("summary").strip()))
                    return Uni.createFrom().failure(new IllegalStateException("Repeated memory entry"));
                accepted.add(entry);
                return collectEntry(context,prompt,model,accepted);
            });
    }
    static boolean validEntry(JsonObject value, AgentContext context) {
        if (value.size() != 1 || !value.containsKey("entry")) return false;
        if (value.getValue("entry") == null) return true;
        if (!(value.getValue("entry") instanceof JsonObject entry) || entry.size() != 4
                || !JudgmentProtocol.keys(entry,"summary","visibility","importance","tags")
                || !JudgmentProtocol.text(entry,"summary") || !JudgmentProtocol.number(entry,"importance")
                || entry.getDouble("importance") < 0 || entry.getDouble("importance") > 1
                || !(entry.getValue("tags") instanceof JsonArray tags)) return false;
        for (Object tag : tags) if (!(tag instanceof String text) || text.isBlank()) return false;
        Object visibility = entry.getValue("visibility");
        if ("ALL".equals(visibility)) return true;
        if (!(visibility instanceof JsonArray ids) || ids.isEmpty()) return false;
        java.util.Set<String> known = new java.util.HashSet<>(context.getCharacterIdsInScene() == null ? java.util.List.of() : context.getCharacterIdsInScene());
        if (context.getUserId() != null) known.add(context.getUserId());
        for (Object id : ids) if (!(id instanceof String text) || !known.contains(text)) return false;
        return true;
    }

    /**
     * Cache highlighter output to database and update knowledge entries.
     */
    private Uni<Void> cacheHighlighterOutput(String conversationId, JsonArray entries, String lastMessageId) {
        String cacheId = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO highlighter_cache (cacheId, conversationId, entries, lastMessageId, messageCount, version)
            VALUES (?, ?, ?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE entries = VALUES(entries), lastMessageId = VALUES(lastMessageId),
                messageCount = VALUES(messageCount), version = version + 1, updatedAt = NOW()
            """;
        return mysqlPool.preparedQuery(upsert)
            .execute(Tuple.of(cacheId, conversationId, entries.encode(), lastMessageId, entries.size())).replaceWithVoid();
    }

    /**
     * The visibility tag is an id, so the summarizer needs the name-to-id
     * mapping for the characters actually present.
     */
    static void appendCharactersInScene(StringBuilder userPrompt, AgentContext context) {
        if (context.getCharacterIdsInScene() == null || context.getCharacterIdsInScene().isEmpty()) {
            return;
        }
        userPrompt.append("Characters present in the current scene (name → visibility id):\n");
        for (String charId : context.getCharacterIdsInScene()) {
            String name = context.getCharacterNames() != null
                ? context.getCharacterNames().getString(charId, charId) : charId;
            userPrompt.append("- ").append(name).append(" → ").append(charId);
            String pronouns = context.getCharacterPronouns() != null
                ? context.getCharacterPronouns().getString(charId) : null;
            if (pronouns != null && !pronouns.isBlank()) {
                userPrompt.append(" (").append(pronouns).append(")");
            }
            userPrompt.append("\n");
        }
        userPrompt.append("\n");
    }
}
