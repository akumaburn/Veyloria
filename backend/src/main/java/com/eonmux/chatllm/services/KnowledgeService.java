package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.KnowledgeEntry;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class KnowledgeService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<List<KnowledgeEntry>> getEntries(String conversationId) {
        String query = "SELECT * FROM knowledge_entry WHERE conversationId = ? AND isActive = TRUE ORDER BY createdAt";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                List<KnowledgeEntry> entries = new ArrayList<>();
                for (Row row : rows) entries.add(mapRowToEntry(row));
                return entries;
            });
    }

    public Uni<List<KnowledgeEntry>> getEntriesForCharacter(String conversationId, String characterId) {
        return getEntries(conversationId)
            .map(entries -> entries.stream()
                .filter(e -> e.isVisibleTo(characterId))
                .collect(Collectors.toList()));
    }

    public Uni<KnowledgeEntry> addEntry(String conversationId, String summary, Object visibility,
                                            Double importance, String sourceMessageId, Integer dayNumber) {
        String id = UUID.randomUUID().toString();
        String visJson;
        if (visibility instanceof String) {
            visJson = "\"" + visibility + "\"";
        } else if (visibility instanceof JsonArray) {
            visJson = ((JsonArray) visibility).encode();
        } else {
            visJson = "\"ALL\"";
        }

        String insert = """
            INSERT INTO knowledge_entry (entryId, conversationId, summary, visibility, importance, sourceMessageId, dayNumber)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(summary).addValue(visJson)
                .addValue(importance != null ? importance : 0.5).addValue(sourceMessageId).addValue(dayNumber))
            .map(v -> KnowledgeEntry.builder()
                .entryId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .summary(summary)
                .visibility(visibility)
                .importance(importance != null ? importance : 0.5)
                .build());
    }

    public Uni<KnowledgeEntry> addEntry(String conversationId, String summary, Object visibility,
                                            Double importance, String sourceMessageId, Integer dayNumber,
                                            String tags, Integer sourceHighlighterVersion, String summaryHash) {
        String id = UUID.randomUUID().toString();
        String visJson;
        if (visibility instanceof String) {
            visJson = "\"" + visibility + "\"";
        } else if (visibility instanceof JsonArray) {
            visJson = ((JsonArray) visibility).encode();
        } else {
            visJson = "\"ALL\"";
        }

        String insert = """
            INSERT INTO knowledge_entry (entryId, conversationId, summary, visibility, importance,
                sourceMessageId, dayNumber, tags, sourceHighlighterVersion, summaryHash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(summary).addValue(visJson)
                .addValue(importance != null ? importance : 0.5).addValue(sourceMessageId).addValue(dayNumber)
                .addValue(tags).addValue(sourceHighlighterVersion).addValue(summaryHash))
            .map(v -> KnowledgeEntry.builder()
                .entryId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .summary(summary)
                .visibility(visibility)
                .importance(importance != null ? importance : 0.5)
                .sourceHighlighterVersion(sourceHighlighterVersion)
                .summaryHash(summaryHash)
                .build());
    }

    public Uni<Void> deactivateEntry(String entryId) {
        return pool.preparedQuery("UPDATE knowledge_entry SET isActive = FALSE WHERE entryId = ?")
            .execute(Tuple.of(entryId))
            .replaceWithVoid();
    }

    private KnowledgeEntry mapRowToEntry(Row row) {
        KnowledgeEntry e = new KnowledgeEntry();
        e.setEntryId(UUID.fromString(row.getString("entryId")));
        e.setConversationId(UUID.fromString(row.getString("conversationId")));
        e.setSummary(row.getString("summary"));
        try {
            Object vis = row.get(Object.class, row.getColumnIndex("visibility"));
            if (vis instanceof String s) {
                if (s.startsWith("[")) e.setVisibility(new JsonArray(s));
                else e.setVisibility(s.replace("\"", ""));
            } else {
                e.setVisibility(vis);
            }
        } catch (Exception ex) {
            e.setVisibility("ALL");
        }
        if (row.getString("sceneId") != null) e.setSceneId(UUID.fromString(row.getString("sceneId")));
        e.setImportance(row.getDouble("importance"));
        if (row.getString("sourceMessageId") != null) e.setSourceMessageId(UUID.fromString(row.getString("sourceMessageId")));
        e.setDayNumber(row.getInteger("dayNumber"));
        if (row.getString("tags") != null) e.setTagsFromString(row.getString("tags"));
        e.setIsActive(row.getBoolean("isActive"));
        try {
            e.setSourceHighlighterVersion(row.getInteger("sourceHighlighterVersion"));
        } catch (Exception ex) { /* column may be null */ }
        try {
            e.setSummaryHash(row.getString("summaryHash"));
        } catch (Exception ex) { /* column may be null */ }
        e.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return e;
    }
}
