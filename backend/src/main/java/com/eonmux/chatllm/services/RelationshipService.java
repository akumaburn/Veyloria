package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.CharacterRelationship;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class RelationshipService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<List<CharacterRelationship>> getRelationships(String conversationId, String characterId) {
        String query;
        Tuple params;
        if (characterId != null) {
            query = "SELECT * FROM character_relationship WHERE conversationId = ? AND (fromCharacterId = ? OR toCharacterId = ?)";
            params = Tuple.of(conversationId, characterId, characterId);
        } else {
            query = "SELECT * FROM character_relationship WHERE conversationId = ?";
            params = Tuple.of(conversationId);
        }
        return pool.preparedQuery(query)
            .execute(params)
            .map(rows -> {
                List<CharacterRelationship> rels = new ArrayList<>();
                for (Row row : rows) rels.add(mapRowToRelationship(row));
                return rels;
            });
    }

    public Uni<CharacterRelationship> getRelationship(String conversationId, String fromCharacterId, String toCharacterId) {
        String query = "SELECT * FROM character_relationship WHERE conversationId = ? AND fromCharacterId = ? AND toCharacterId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, fromCharacterId, toCharacterId))
            .map(rows -> rows.size() > 0 ? mapRowToRelationship(rows.iterator().next()) : null);
    }

    public Uni<CharacterRelationship> setRelationship(String conversationId, String fromCharacterId,
                                                          String toCharacterId, String relationshipType,
                                                          Double intensity, String description) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO character_relationship (relationshipId, conversationId, fromCharacterId, toCharacterId, relationshipType, intensity, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE relationshipType = VALUES(relationshipType), intensity = VALUES(intensity), description = VALUES(description)
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(fromCharacterId).addValue(toCharacterId)
                .addValue(relationshipType).addValue(intensity != null ? intensity : 0.5).addValue(description))
            .chain(v -> {
                String query = "SELECT * FROM character_relationship WHERE conversationId = ? AND fromCharacterId = ? AND toCharacterId = ?";
                return pool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, fromCharacterId, toCharacterId))
                    .map(rows -> rows.size() > 0 ? mapRowToRelationship(rows.iterator().next()) : null);
            });
    }

    /**
     * Atomically adjust relationship intensity by a delta, avoiding read-modify-write races.
     * Creates the relationship if it doesn't exist.
     */
    public Uni<CharacterRelationship> adjustRelationship(String conversationId, String fromCharacterId,
                                                              String toCharacterId, String relationshipType,
                                                              double intensityDelta, String description) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO character_relationship (relationshipId, conversationId, fromCharacterId, toCharacterId, relationshipType, intensity, description)
            VALUES (?, ?, ?, ?, ?, LEAST(1.0, GREATEST(0.0, 0.5 + ?)), ?)
            ON DUPLICATE KEY UPDATE
                relationshipType = VALUES(relationshipType),
                intensity = LEAST(1.0, GREATEST(0.0, intensity + ?)),
                description = VALUES(description)
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(fromCharacterId).addValue(toCharacterId)
                .addValue(relationshipType).addValue(intensityDelta).addValue(description).addValue(intensityDelta))
            .chain(v -> {
                String query = "SELECT * FROM character_relationship WHERE conversationId = ? AND fromCharacterId = ? AND toCharacterId = ?";
                return pool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, fromCharacterId, toCharacterId))
                    .map(rows -> rows.size() > 0 ? mapRowToRelationship(rows.iterator().next()) : null);
            });
    }

    private CharacterRelationship mapRowToRelationship(Row row) {
        CharacterRelationship r = new CharacterRelationship();
        r.setRelationshipId(UUID.fromString(row.getString("relationshipId")));
        r.setConversationId(UUID.fromString(row.getString("conversationId")));
        r.setFromCharacterId(UUID.fromString(row.getString("fromCharacterId")));
        r.setToCharacterId(UUID.fromString(row.getString("toCharacterId")));
        r.setRelationshipType(row.getString("relationshipType"));
        r.setIntensity(row.getDouble("intensity"));
        r.setDescription(row.getString("description"));
        r.setHistory(ServiceHelper.safeGetJsonObject(row, "history"));
        r.setIsReciprocal(row.getBoolean("isReciprocal"));
        r.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        r.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return r;
    }
}
