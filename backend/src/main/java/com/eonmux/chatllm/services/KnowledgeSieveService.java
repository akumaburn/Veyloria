package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.models.KnowledgeEntry;
import com.eonmux.chatllm.models.Message;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class KnowledgeSieveService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    KnowledgeService knowledgeService;
    @Inject
    SceneService sceneService;
    @Inject
    RetrievalService retrievalService;
    @Inject
    AppConfig config;

    public List<KnowledgeEntry> filterForCharacter(List<KnowledgeEntry> entries, String characterId) {
        return entries.stream()
            .filter(entry -> entry.isVisibleTo(characterId))
            .collect(Collectors.toList());
    }

    public Uni<List<Message>> filterRecentMessagesForCharacter(String conversationId, String characterId, int tokenLimit) {
        return sceneService.getCharacterScene(conversationId, characterId)
            .chain(currentSceneId -> {
                String query;
                Tuple params;
                if (currentSceneId != null) {
                    query = """
                        SELECT m.* FROM message m
                        WHERE m.conversationId = ? AND (m.sceneId = ? OR m.sceneId IS NULL)
                        AND m.isDeleted = FALSE
                        ORDER BY m.seq DESC LIMIT 50
                        """;
                    params = Tuple.of(conversationId, currentSceneId);
                } else {
                    query = """
                        SELECT m.* FROM message m
                        WHERE m.conversationId = ? AND m.isDeleted = FALSE
                        ORDER BY m.seq DESC LIMIT 50
                        """;
                    params = Tuple.of(conversationId);
                }
                return pool.preparedQuery(query).execute(params);
            })
            .map(rows -> {
                List<Message> messages = new ArrayList<>();
                int approxTokens = 0;
                for (Row row : rows) {
                    String content = row.getString("content");
                    if (content == null) continue;
                    int msgTokens = content.length() / 4; // rough estimate
                    if (approxTokens + msgTokens > tokenLimit) break;
                    approxTokens += msgTokens;
                    Message msg = new Message();
                    msg.setId(java.util.UUID.fromString(row.getString("messageUUID")));
                    msg.setContent(content);
                    msg.setRoleString(row.getString("role"));
                    msg.setDisplayName(row.getString("displayName"));
                    messages.add(msg);
                }
                java.util.Collections.reverse(messages);
                return messages;
            });
    }

    public Uni<JsonObject> buildContextForCharacter(String conversationId, String characterId) {
        Uni<List<Message>> messagesFuture = filterRecentMessagesForCharacter(conversationId, characterId, 1024);

        // Use RAG retrieval when enabled, otherwise fall back to dumping all entries
        Uni<String> longTermFuture;
        if (config != null && config.isRagEnabled() && retrievalService != null) {
            longTermFuture = messagesFuture.chain(messages -> {
                // Build query from recent messages
                StringBuilder queryBuilder = new StringBuilder();
                int start = Math.max(0, messages.size() - 5);
                for (int i = start; i < messages.size(); i++) {
                    queryBuilder.append(messages.get(i).getContent()).append(" ");
                }
                return retrievalService.retrieve(conversationId, queryBuilder.toString().trim(),
                        Collections.emptyList(), characterId, config.getRagMaxRetrievedEntries())
                    .map(scoredEntries -> {
                        if (scoredEntries.isEmpty()) return "";
                        return retrievalService.buildContextString(scoredEntries);
                    });
            });
        } else {
            longTermFuture = knowledgeService.getEntriesForCharacter(conversationId, characterId)
                .map(entries -> {
                    StringBuilder sb = new StringBuilder("=== STORY SO FAR (What you know) ===\n");
                    for (KnowledgeEntry entry : entries) {
                        sb.append("- ").append(entry.getSummary()).append("\n");
                    }
                    return sb.toString();
                });
        }

        return Uni.combine().all().unis(longTermFuture, messagesFuture).asTuple()
            .map(tuple -> {
                String longTermMemory = tuple.getItem1();
                List<Message> messages = tuple.getItem2();

                StringBuilder shortTermMemory = new StringBuilder();
                for (Message msg : messages) {
                    String name = msg.getDisplayName() != null ? msg.getDisplayName() : msg.getRoleString();
                    shortTermMemory.append(name).append(": ").append(msg.getContent()).append("\n");
                }

                return new JsonObject()
                    .put("longTermMemory", longTermMemory)
                    .put("shortTermMemory", shortTermMemory.toString())
                    .put("entryCount", 0)
                    .put("messageCount", messages.size());
            });
    }
}
